"""Validated runtime configuration."""

import os
import socket
from pathlib import Path

from pydantic import BaseModel, ConfigDict, Field, HttpUrl, SecretStr, model_validator


class Settings(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid")

    execution_url: HttpUrl
    market_data_url: HttpUrl
    # Trading calls (orders and cancels) in flight at once, across every bot.
    max_in_flight: int = Field(default=32, ge=1, le=256)
    # Reads (balances, open orders) in flight at once, apart from trading calls, so a run's reads
    # never hold back its commands.
    max_reads_in_flight: int = Field(default=64, ge=1, le=512)
    heartbeat_seconds: float = Field(default=5.0, gt=0)
    # One <accountId>.token file per bot, written once by the exchange at provisioning.
    token_directory: Path = Path("/run/mse/bot-tokens")
    # The control credential for the assignment and heartbeat. Without it the bots stay idle.
    simulator_token: SecretStr | None = None
    # Names this worker in its lease; a restarted worker with the same name takes its run back.
    worker_id: str = Field(default_factory=socket.gethostname, pattern=r"^[A-Za-z0-9._:-]{1,100}$")
    control_poll_seconds: float = Field(default=2.0, gt=0)
    # Each instrument's fair value wanders around its reference price: it closes half of a gap
    # to it in this many hours, and moves by about this fraction of the price over an hour. At
    # rest a value then stays within a few percent of its reference (2.5% is one standard
    # deviation with these defaults).
    fair_value_half_life_hours: float = Field(default=4.0, gt=0, le=1_000)
    fair_value_volatility: float = Field(default=0.015, ge=0, le=1)
    # Each bot's own idea of a value lies within this many basis points of the fair value.
    view_spread_bp: float = Field(default=20.0, ge=0, le=1_000)
    # Noise: how many basis points either side of its centre a noise bot may price an order, and
    # how far that centre is pulled from the market's price toward the bot's idea of the value.
    noise_spread_bp: float = Field(default=30.0, gt=0, le=10_000)
    noise_value_pull: float = Field(default=0.25, ge=0, le=1)
    max_open_orders: int = Field(default=5, ge=1, le=20)
    quote_lifetime_seconds: float = Field(default=10.0, gt=0)
    # Momentum: moving averages over this many market polls; a trend must move the short one at
    # least this fraction of the price, and this many ticks, from the long one; the odds of
    # following a trend rise at most this far above even; an order reaches at most this many basis
    # points past the current price; and a bot waits this long after each order.
    momentum_short_polls: int = Field(default=10, ge=1, le=1_000)
    momentum_long_polls: int = Field(default=60, ge=2, le=10_000)
    momentum_threshold: float = Field(default=0.002, gt=0, lt=1)
    momentum_min_ticks: float = Field(default=2.0, ge=0, le=1_000)
    momentum_follow: float = Field(default=0.25, ge=0, le=0.5)
    momentum_reach_bp: float = Field(default=20.0, gt=0, le=10_000)
    momentum_cooldown_seconds: float = Field(default=15.0, ge=0)
    # Market maker: basis points from the reservation price to each quote; basis points the
    # reservation leans per quote size of inventory; basis points a quote may drift before it is
    # replaced; and quote lifetime.
    maker_half_spread_bp: float = Field(default=10.0, gt=0, le=10_000)
    maker_skew_bp: float = Field(default=10.0, ge=0, le=10_000)
    maker_requote_bp: float = Field(default=5.0, gt=0, le=10_000)
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
