"""The noise strategy: seeded random limit orders near the current price, within the bot's means."""

from dataclasses import dataclass

import numpy as np

from mse_simulator.market import NARROW_BOOK_BP, MarketView
from mse_simulator.prices import BASIS_POINT, limit_price
from mse_simulator.strategy import (
    Cancel,
    Holdings,
    Intent,
    OpenOrder,
    Place,
    Positions,
    affordable,
    oldest_stale,
)
from mse_simulator.values import View


@dataclass(frozen=True)
class NoiseConfig:
    max_quantity: int
    # How far either side of its centre an order may be priced, in basis points of the price, so
    # the reach is the same share of a cheap instrument's price as of a dear one's. At least a tick.
    spread_bp: float
    max_open_orders: int
    quote_lifetime: float
    max_position: int = 1_000_000
    # The centre is the market's price moved this share of the way to the bot's own idea of the
    # value: 0 follows the market alone.
    value_pull: float = 0.0
    # The widest book whose mid counts as the market's price (``Instrument.mid``).
    narrow_book_bp: float = NARROW_BOOK_BP


class NoiseBot:
    """Decisions depend only on the seed and what the bot observes: same inputs, same intents."""

    def __init__(
        self, rng: np.random.Generator, config: NoiseConfig, view: View | None = None
    ) -> None:
        self._rng = rng
        self._config = config
        self._view = view or View(None, narrow_book_bp=config.narrow_book_bp)
        self._narrow = config.narrow_book_bp * BASIS_POINT
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
        anchor = instrument.anchor(self._narrow)
        centre = anchor + self._config.value_pull * (self._view.of(instrument) - anchor)
        reach = max(self._config.spread_bp * BASIS_POINT * centre, instrument.tick)
        limit = limit_price(centre + float(self._rng.uniform(-reach, reach)), side, instrument)
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
