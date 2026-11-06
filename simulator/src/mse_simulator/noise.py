"""The noise strategy: seeded random limit orders near the current price, within the bot's means."""

from dataclasses import dataclass

import numpy as np

from mse_simulator.market import MarketView
from mse_simulator.strategy import (
    Cancel,
    Holdings,
    Intent,
    OpenOrder,
    Place,
    Positions,
    affordable,
    clamp,
    oldest_stale,
)


@dataclass(frozen=True)
class NoiseConfig:
    max_quantity: int
    spread_ticks: int
    max_open_orders: int
    quote_lifetime: float
    max_position: int = 1_000_000


class NoiseBot:
    """Decisions depend only on the seed and what the bot observes: same inputs, same intents."""

    def __init__(self, rng: np.random.Generator, config: NoiseConfig) -> None:
        self._rng = rng
        self._config = config
        self._positions = Positions(config.max_position)

    def decide(
        self,
        market: MarketView,
        tradable: bool,
        holdings: Holdings,
        orders: list[OpenOrder],
        now: float,
    ) -> Intent | None:
        # Old quotes go first, even while new exposure is paused: cancelling is always allowed.
        stale = oldest_stale(orders, now, self._config.quote_lifetime)
        if stale is not None:
            return stale
        if not tradable:
            return None
        if len(orders) >= self._config.max_open_orders:
            return Cancel(min(orders, key=lambda order: order.created_at).order_id)
        instrument = market.instruments[int(self._rng.integers(len(market.instruments)))]
        side = "BUY" if self._rng.random() < 0.5 else "SELL"
        offset = int(self._rng.integers(-self._config.spread_ticks, self._config.spread_ticks + 1))
        limit = clamp(instrument.anchor() + offset * instrument.tick, instrument)
        wanted = int(self._rng.integers(1, self._config.max_quantity + 1))
        quantity = min(
            wanted,
            affordable(instrument, side, limit, holdings),
            self._positions.room(
                instrument.instrument_id, side, holdings, orders, market.instruments
            ),
            instrument.max_quantity,
        )
        if quantity < instrument.min_quantity:
            return None
        return Place(instrument.instrument_id, side, quantity, limit)
