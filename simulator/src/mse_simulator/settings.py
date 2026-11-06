"""Validated runtime configuration."""

import os
import socket
from pathlib import Path

from pydantic import BaseModel, ConfigDict, Field, HttpUrl, SecretStr


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
            "simulator_token": "MSE_SIMULATOR_TOKEN",
            "worker_id": "MSE_WORKER_ID",
        }
        for field, variable in optional.items():
            if os.environ.get(variable):
                values[field] = os.environ[variable]
        return cls(**values)
