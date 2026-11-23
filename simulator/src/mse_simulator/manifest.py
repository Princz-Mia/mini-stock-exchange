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
                "halfSpreadBp": settings.maker_half_spread_bp,
                "skewBp": settings.maker_skew_bp,
                "requoteBp": settings.maker_requote_bp,
                "quoteLifetimeSeconds": settings.maker_quote_lifetime_seconds,
            },
            "NOISE": {
                "spreadBp": settings.noise_spread_bp,
                "valuePull": settings.noise_value_pull,
                "maxOpenOrders": settings.max_open_orders,
                "quoteLifetimeSeconds": settings.quote_lifetime_seconds,
            },
            "MOMENTUM": {
                "shortPolls": settings.momentum_short_polls,
                "longPolls": settings.momentum_long_polls,
                "threshold": settings.momentum_threshold,
                "minTicks": settings.momentum_min_ticks,
                "follow": settings.momentum_follow,
                "reachBp": settings.momentum_reach_bp,
                "cooldownSeconds": settings.momentum_cooldown_seconds,
                "orderLifetimeSeconds": settings.quote_lifetime_seconds,
            },
        },
        "defaults": {
            "fairValueHalfLifeHours": settings.fair_value_half_life_hours,
            "fairValueVolatility": settings.fair_value_volatility,
            "viewSpreadBp": settings.view_spread_bp,
            "marketPollSeconds": settings.market_poll_seconds,
            "maxDataAgeSeconds": settings.max_data_age_seconds,
            "maxInFlight": settings.max_in_flight,
            "maxReadsInFlight": settings.max_reads_in_flight,
        },
    }
