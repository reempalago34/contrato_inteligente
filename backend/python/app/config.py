"""Configuración central del microservicio Web3 (12-factor / variables de entorno)."""

from functools import lru_cache
from pathlib import Path

from pydantic import field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

# backend/python/
BASE_DIR = Path(__file__).resolve().parent.parent


class Settings(BaseSettings):
    """Valores leídos de backend/python/.env o del entorno del proceso."""

    model_config = SettingsConfigDict(
        env_file=BASE_DIR / ".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # Nodo JSON-RPC (Hardhat local por defecto).
    web3_rpc_url: str = "http://127.0.0.1:8545"

    # Dirección del contrato desplegado. Se valida/checksum en blockchain.py.
    contract_address: str = "0x5FbDB2315678afecb367f032d93F642f64180aa3"

    # Chain ID esperado (31337 = Hardhat).
    chain_id: int = 31337

    # ABI exportado por `npx hardhat run scripts/export-abi.ts`.
    abi_path: Path = BASE_DIR / "abi" / "GymMembership.json"

    # Persistencia de ejemplo: SQLite. En producción usa PostgreSQL/MySQL.
    database_url: str = f"sqlite:///{BASE_DIR / 'gym.db'}"

    # Timeout de los requests HTTP al nodo (segundos).
    rpc_timeout_seconds: int = 5

    @field_validator("abi_path")
    @classmethod
    def resolve_abi_path(cls, value: Path) -> Path:
        """Permite ABI_PATH relativo (se resuelve contra backend/python/)."""
        return value if value.is_absolute() else BASE_DIR / value


@lru_cache
def get_settings() -> Settings:
    """Singleton de configuración (se cachea para no releer el .env)."""
    return Settings()
