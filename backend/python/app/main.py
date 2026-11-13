"""
API FastAPI de ejemplo: reserva de clases protegida por la membresía NFT.

Flujo de POST /classes/{class_id}/book:
  1. Lee getMembership(tokenId) del contrato (1 sola llamada RPC).
  2. Verifica que el NFT exista y pertenezca a la wallet que reserva.
  3. Verifica que la membresía esté vigente (expiry > block.timestamp).
  4. Inserta la reserva en la base de datos.

Ejecutar (desde backend/python):
  uvicorn app.main:app --reload --port 8000
Docs interactivas:
  http://127.0.0.1:8000/docs
"""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, HTTPException, status
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session
from web3.exceptions import ContractLogicError, Web3Exception

from .blockchain import (
    BlockchainUnavailable,
    MembershipGateway,
    get_gateway,
)
from .database import Base, engine, get_db
from .models import Booking
from .schemas import BookingRequest, BookingResponse, MembershipStatus

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s :: %(message)s",
)
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Demo: crea tablas al arrancar. En producción usa Alembic/Flyway.
    Base.metadata.create_all(bind=engine)
    logger.info("Base de datos lista")
    yield


app = FastAPI(
    title="Gym Membership Web3 API",
    version="1.0.0",
    description="Reservas de clases validadas contra el NFT de membresía",
    lifespan=lifespan,
)


# ---------------------------------------------------------------------
# Dependencias
# ---------------------------------------------------------------------
def gateway_dependency() -> MembershipGateway:
    """Convierte errores de infraestructura en HTTP 503."""
    try:
        return get_gateway()
    except BlockchainUnavailable as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=str(exc),
        ) from exc


# ---------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------
@app.get("/health", tags=["ops"])
def health() -> dict[str, object]:
    try:
        connected = get_gateway().is_connected()
    except Exception:  # noqa: BLE001
        connected = False
    return {
        "status": "ok" if connected else "degraded",
        "rpc_connected": connected,
    }


@app.get(
    "/memberships/{token_id}",
    response_model=MembershipStatus,
    tags=["memberships"],
)
def get_membership(
    token_id: int,
    gw: MembershipGateway = Depends(gateway_dependency),
) -> MembershipStatus:
    try:
        info = gw.get_membership(token_id)
    except (ContractLogicError, Web3Exception) as exc:
        logger.exception("Error RPC al leer la membresía %s", token_id)
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="Error consultando la blockchain",
        ) from exc

    if info is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"El token {token_id} no existe",
        )

    return MembershipStatus(
        token_id=info.token_id,
        owner=info.owner,
        expires_at=info.expires_at,
        is_valid=info.is_valid,
        seconds_remaining=info.seconds_remaining,
    )


@app.post(
    "/classes/{class_id}/book",
    response_model=BookingResponse,
    status_code=status.HTTP_201_CREATED,
    tags=["classes"],
)
def book_class(
    class_id: str,
    payload: BookingRequest,
    db: Session = Depends(get_db),
    gw: MembershipGateway = Depends(gateway_dependency),
) -> Booking:
    # 1) Leer la membresía on-chain.
    try:
        info = gw.get_membership(payload.token_id)
    except (ContractLogicError, Web3Exception) as exc:
        logger.exception("Error RPC validando token %s", payload.token_id)
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="Error consultando la blockchain",
        ) from exc

    if info is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Membresía no encontrada",
        )

    # 2) El NFT debe pertenecer a la wallet que reserva.
    if info.owner.lower() != payload.wallet_address.lower():
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="La membresía no pertenece a esta wallet",
        )

    # 3) La membresía debe estar vigente.
    if not info.is_valid:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Membresía expirada; renueva antes de reservar",
        )

    # 4) Persistir la reserva.
    booking = Booking(
        class_id=class_id,
        wallet_address=payload.wallet_address.lower(),
        token_id=payload.token_id,
    )
    db.add(booking)
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Ya existe una reserva para esta clase y wallet",
        ) from None

    db.refresh(booking)
    logger.info(
        "Reserva creada class=%s wallet=%s token=%s",
        class_id,
        payload.wallet_address,
        payload.token_id,
    )
    return booking
