"""Validated runtime configuration."""

import os

from pydantic import BaseModel, ConfigDict, Field, HttpUrl


class Settings(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid")

    execution_url: HttpUrl
    market_data_url: HttpUrl
    max_in_flight: int = Field(default=32, ge=1, le=256)
    heartbeat_seconds: float = Field(default=5.0, gt=0)

    @classmethod
    def from_env(cls) -> Settings:
        return cls(
            execution_url=os.environ["MSE_EXECUTION_URL"],
            market_data_url=os.environ["MSE_MARKET_DATA_URL"],
        )
