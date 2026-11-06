"""Validated runtime configuration."""

import os
import socket
from pathlib import Path

from pydantic import BaseModel, ConfigDict, Field, HttpUrl, SecretStr, model_validator


class Settings(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid")

    execution_url: HttpUrl
    market_data_url: HttpUrl
    max_in_flight: int = Field(default=32, ge=1, le=256)
    heartbeat_seconds: float = Field(default=5.0, gt=0)
    # One <accountId>.token file per bot, written once by the exchange at provisioning.
    token_directory: Path = Path("/run/mse/bot-tokens")
    # The control credential for the assignment and heartbeat. Without it the bots stay idle.
    simulator_token: SecretStr | None = None
    # Names this worker in its lease; a restarted worker with the same name takes its run back.
    worker_id: str = Field(default_factory=socket.gethostname, pattern=r"^[A-Za-z0-9._:-]{1,100}$")
    control_poll_seconds: float = Field(default=2.0, gt=0)
    # How many ticks either side of the anchor price a noise bot may quote.
    spread_ticks: int = Field(default=5, ge=0, le=1_000)
    max_open_orders: int = Field(default=5, ge=1, le=20)
    quote_lifetime_seconds: float = Field(default=10.0, gt=0)
    # Momentum: moving averages over this many market polls; a trend must move the short one at
    # least this fraction from the long one; and a bot waits this long after each order.
    momentum_short_polls: int = Field(default=5, ge=1, le=1_000)
    momentum_long_polls: int = Field(default=20, ge=2, le=10_000)
    momentum_threshold: float = Field(default=0.001, gt=0, lt=1)
    momentum_cooldown_seconds: float = Field(default=5.0, ge=0)
    # Market maker: ticks from the reservation price to each quote; ticks the reservation leans per
    # quote size of inventory; ticks a quote may drift before it is replaced; and quote lifetime.
    maker_half_spread_ticks: int = Field(default=2, ge=1, le=1_000)
    maker_skew_ticks: int = Field(default=1, ge=0, le=1_000)
    maker_requote_ticks: int = Field(default=2, ge=1, le=1_000)
    maker_quote_lifetime_seconds: float = Field(default=30.0, gt=0)
    market_poll_seconds: float = Field(default=1.0, gt=0)
    # New exposure pauses when market data is older than this or not live.
    max_data_age_seconds: float = Field(default=2.0, gt=0)
    token_rescan_seconds: float = Field(default=10.0, gt=0)

    @model_validator(mode="after")
    def _windows(self) -> Settings:
        if self.momentum_short_polls >= self.momentum_long_polls:
            raise ValueError("momentum_short_polls must be below momentum_long_polls")
        return self

    @classmethod
    def from_env(cls) -> Settings:
        values: dict[str, object] = {
            "execution_url": os.environ["MSE_EXECUTION_URL"],
            "market_data_url": os.environ["MSE_MARKET_DATA_URL"],
        }
        optional = {
            "token_directory": "MSE_BOT_TOKEN_DIRECTORY",
            "simulator_token": "MSE_SIMULATOR_TOKEN",
            "worker_id": "MSE_WORKER_ID",
        }
        for field, variable in optional.items():
            if os.environ.get(variable):
                values[field] = os.environ[variable]
        return cls(**values)
