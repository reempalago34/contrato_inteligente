"""Cliente de solo lectura para el contrato GymMembership (web3.py v7)."""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from datetime import datetime, timezone
from functools import lru_cache
from pathlib import Path
from typing import Any

from web3 import Web3
from web3.contract import Contract

from .config import Settings, get_settings

logger = logging.getLogger(__name__)


class BlockchainUnavailable(RuntimeError):
    """El nodo RPC no responde o el contrato no está desplegado."""


@dataclass(frozen=True)
class MembershipInfo:
    """Vista inmutable de una membresía para la capa HTTP."""

    token_id: int
    owner: str
    expires_at: datetime
    is_valid: bool

    @property
    def seconds_remaining(self) -> int:
        now = datetime.now(timezone.utc)
        return max(0, int((self.expires_at - now).total_seconds()))


def _load_abi(path: Path) -> list[dict[str, Any]]:
    """Acepta tanto un ABI plano (array) como un artifact de Hardhat ({abi: []})."""
    if not path.exists():
        raise BlockchainUnavailable(
            f"No existe el ABI en {path}. Ejecuta: npx hardhat run scripts/export-abi.ts"
        )

    data = json.loads(path.read_text(encoding="utf-8"))
    abi = data.get("abi") if isinstance(data, dict) else data
    if not isinstance(abi, list):
        raise BlockchainUnavailable(f"Formato de ABI inválido en {path}")
    return abi


class MembershipGateway:
    """Encapsula las llamadas `eth_call` que usa el endpoint de reservas."""

    def __init__(self, w3: Web3, contract: Contract):
        self._w3 = w3
        self._contract = contract

    # ------------------------------------------------------------------
    # Construcción
    # ------------------------------------------------------------------
    @classmethod
    def from_settings(cls, settings: Settings | None = None) -> "MembershipGateway":
        settings = settings or get_settings()

        w3 = Web3(
            Web3.HTTPProvider(
                settings.web3_rpc_url,
                request_kwargs={"timeout": settings.rpc_timeout_seconds},
            )
        )

        if not w3.is_connected():
            raise BlockchainUnavailable(
                f"No se pudo conectar al nodo RPC en {settings.web3_rpc_url}"
            )

        try:
            address = Web3.to_checksum_address(settings.contract_address)
        except ValueError as exc:
            raise BlockchainUnavailable(
                f"CONTRACT_ADDRESS inválida: {settings.contract_address}"
            ) from exc

        if not w3.eth.get_code(address):
            raise BlockchainUnavailable(
                f"No hay contrato desplegado en {address} (¿red equivocada?)"
            )

        abi = _load_abi(settings.abi_path)
        contract = w3.eth.contract(address=address, abi=abi)

        logger.info("Conectado a %s | contrato=%s", settings.web3_rpc_url, address)
        return cls(w3, contract)

    # ------------------------------------------------------------------
    # Lecturas
    # ------------------------------------------------------------------
    @property
    def w3(self) -> Web3:
        return self._w3

    def is_connected(self) -> bool:
        try:
            return self._w3.is_connected()
        except Exception:  # noqa: BLE001 - health check nunca debe tumbar la app
            return False

    def membership_price_wei(self) -> int:
        return int(self._contract.functions.membershipPrice().call())

    def is_membership_valid(self, token_id: int) -> bool:
        """Llama a isMembershipValid(uint256) del contrato."""
        return bool(self._contract.functions.isMembershipValid(token_id).call())

    def owner_of(self, token_id: int) -> str:
        return Web3.to_checksum_address(
            self._contract.functions.ownerOf(token_id).call()
        )

    def get_membership(self, token_id: int) -> MembershipInfo | None:
        """
        Una sola llamada RPC que devuelve dueño + expiración + validez.
        Devuelve None si el token no existe.
        """
        member, expiry, valid = self._contract.functions.getMembership(
            token_id
        ).call()

        if int(member, 16) == 0:
            return None

        return MembershipInfo(
            token_id=token_id,
            owner=Web3.to_checksum_address(member),
            expires_at=datetime.fromtimestamp(int(expiry), tz=timezone.utc),
            is_valid=bool(valid),
        )


@lru_cache(maxsize=1)
def get_gateway() -> MembershipGateway:
    """Singleton perezoso: si el RPC cae, el próximo request reintenta."""
    return MembershipGateway.from_settings()
