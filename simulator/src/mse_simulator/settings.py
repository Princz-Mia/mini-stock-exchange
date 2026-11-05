"""Validated runtime configuration."""

import os
from pathlib import Path

from pydantic import BaseModel, ConfigDict, Field, HttpUrl


class Settings(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid")

    execution_url: HttpUrl
    market_data_url: HttpUrl
    max_in_flight: int = Field(default=32, ge=1, le=256)
    heartbeat_seconds: float = Field(default=5.0, gt=0)
    # One <accountId>.token file per bot, written once by the exchange at provisioning.
    token_directory: Path = Path("/run/mse/bot-tokens")
    # Trading commands per second across all bots. Zero keeps the worker idle.
    rate: float = Field(default=0.0, ge=0, le=500)
    seed: int = Field(default=1, ge=0)
    max_order_quantity: int = Field(default=10, ge=1, le=1_000)
    # How many ticks either side of the anchor price a noise bot may quote.
    spread_ticks: int = Field(default=5, ge=0, le=1_000)
    max_open_orders: int = Field(default=5, ge=1, le=20)
    quote_lifetime_seconds: float = Field(default=10.0, gt=0)
    market_poll_seconds: float = Field(default=1.0, gt=0)
    # New exposure pauses when market data is older than this or not live.
    max_data_age_seconds: float = Field(default=2.0, gt=0)
    token_rescan_seconds: float = Field(default=10.0, gt=0)

    @classmethod
    def from_env(cls) -> Settings:
        values: dict[str, object] = {
            "execution_url": os.environ["MSE_EXECUTION_URL"],
            "market_data_url": os.environ["MSE_MARKET_DATA_URL"],
        }
        optional = {
            "token_directory": "MSE_BOT_TOKEN_DIRECTORY",
            "rate": "MSE_BOT_RATE",
            "seed": "MSE_BOT_SEED",
        }
        for field, variable in optional.items():
            if os.environ.get(variable):
                values[field] = os.environ[variable]
        return cls(**values)
