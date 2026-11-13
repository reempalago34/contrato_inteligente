"""Modelos de persistencia del servicio Python (reservas de ejemplo)."""

from datetime import datetime

from sqlalchemy import BigInteger, DateTime, String, UniqueConstraint, func
from sqlalchemy.orm import Mapped, mapped_column

from .database import Base


class Booking(Base):
    """
    Reserva de una clase.

    La unicidad (class_id, wallet_address) evita dobles reservas y sirve de
    ejemplo de idempotencia a nivel de base de datos. La validación Web3 se
    hace ANTES de insertar (ver app/main.py).
    """

    __tablename__ = "bookings"
    __table_args__ = (
        UniqueConstraint(
            "class_id", "wallet_address", name="uq_booking_class_wallet"
        ),
    )

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    class_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    wallet_address: Mapped[str] = mapped_column(
        String(42), nullable=False, index=True
    )
    token_id: Mapped[int] = mapped_column(BigInteger, nullable=False, index=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
