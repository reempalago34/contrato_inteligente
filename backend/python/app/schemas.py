"""Schemas Pydantic de entrada/salida del API."""

from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field


class MembershipStatus(BaseModel):
    token_id: int
    owner: str
    expires_at: datetime
    is_valid: bool
    seconds_remaining: int


class BookingRequest(BaseModel):
    """El cliente debe enviar el tokenId y la wallet que firma la reserva."""

    token_id: int = Field(..., ge=1, description="NFT de membresía")
    wallet_address: str = Field(
        ...,
        pattern=r"^0x[a-fA-F0-9]{40}$",
        description="Dirección EVM del socio",
    )


class BookingResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    class_id: str
    wallet_address: str
    token_id: int
    created_at: datetime
