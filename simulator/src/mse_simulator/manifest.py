"""What a worker reports about itself with each run it takes: its version, the runtime its
decisions depend on, and the settings its strategies run with. Two runs on different code or
defaults then look different. Values the run itself sets (order size, position limit) are not
here: Execution keeps them with the run."""

import logging
import os
import platform
import re
from importlib import metadata
from typing import Any

import numpy as np

from mse_simulator.settings import Settings

logger = logging.getLogger(__name__)

# Set at build time: the image has no git history to ask.
REVISION_VARIABLE = "MSE_SIMULATOR_REVISION"

# A commit, optionally marked as built from uncommitted changes, as the build scripts write it.
REVISION = re.compile(r"^[0-9a-f]{7,40}(\.dirty)?$")


def version() -> str:
    """The package version and the commit the image was built from, as a local version label:
    ``0.1.0+27a70d6c1e2f``, ``+27a70d6c1e2f.dirty`` for a build of uncommitted changes, and
    ``+unknown`` when the build named none or named something that isn't a commit."""
    revision = os.environ.get(REVISION_VARIABLE, "")
    if not REVISION.fullmatch(revision):
        if revision:
            logger.warning(
                "%s is not a commit; reporting the version as unknown", REVISION_VARIABLE
            )
        revision = "unknown"
    return f"{metadata.version('mse-simulator')}+{revision}"


def manifest(settings: Settings) -> dict[str, Any]:
    """The manifest, from an explicit list of settings: the rest of them (the control token, the
    addresses, the worker's name) never leave the process this way."""
    return {
        "version": version(),
        # numpy draws every bot's decisions; its generators are not promised stable across versions.
        "runtime": {"python": platform.python_version(), "numpy": np.__version__},
        "strategies": {
            "MARKET_MAKER": {
                "halfSpreadTicks": settings.maker_half_spread_ticks,
                "skewTicks": settings.maker_skew_ticks,
                "requoteTicks": settings.maker_requote_ticks,
                "quoteLifetimeSeconds": settings.maker_quote_lifetime_seconds,
            },
            "NOISE": {
                "spreadTicks": settings.spread_ticks,
                "maxOpenOrders": settings.max_open_orders,
                "quoteLifetimeSeconds": settings.quote_lifetime_seconds,
            },
            "MOMENTUM": {
                "shortPolls": settings.momentum_short_polls,
                "longPolls": settings.momentum_long_polls,
                "threshold": settings.momentum_threshold,
                "cooldownSeconds": settings.momentum_cooldown_seconds,
                "orderLifetimeSeconds": settings.quote_lifetime_seconds,
            },
        },
        "defaults": {
            "marketPollSeconds": settings.market_poll_seconds,
            "maxDataAgeSeconds": settings.max_data_age_seconds,
            "maxInFlight": settings.max_in_flight,
            "maxReadsInFlight": settings.max_reads_in_flight,
        },
    }
