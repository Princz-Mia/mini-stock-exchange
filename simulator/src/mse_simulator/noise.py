"""The noise strategy: seeded random limit orders near the current price, within the bot's means."""

from dataclasses import dataclass, field
from uuid import UUID

import numpy as np

from mse_simulator.market import Instrument, MarketView


@dataclass(frozen=True)
class Holdings:
    """What the bot can still commit, net of open orders: cash in cents, shares by instrument; and
    the shares it owns, reserved or not."""

    cash: int
    shares: dict[UUID, int]
    positions: dict[UUID, int] = field(default_factory=dict)


@dataclass(frozen=True)
class OpenOrder:
    order_id: str
    created_at: float
    instrument_id: UUID | None = None
    side: str = "BUY"
    working: int = 0


@dataclass(frozen=True)
class Place:
    instrument_id: UUID
    side: str
    quantity: int
    limit: int


@dataclass(frozen=True)
class Cancel:
    order_id: str


Intent = Place | Cancel


@dataclass(frozen=True)
class NoiseConfig:
    max_quantity: int
    spread_ticks: int
    max_open_orders: int
    quote_lifetime: float
    max_position: int = 1_000_000


class Positions:
    """A run's position limit: a bot's shares of an instrument may move at most ``limit`` from where
    they stood when it first looked, counting what its open orders could still fill."""

    def __init__(self, limit: int) -> None:
        self._limit = limit
        self._start: dict[UUID, int] | None = None

    def room(
        self,
        instrument: UUID,
        side: str,
        holdings: Holdings,
        orders: list[OpenOrder],
        instruments: tuple[Instrument, ...],
    ) -> int:
        if self._start is None:
            self._start = {
                item.instrument_id: holdings.positions.get(item.instrument_id, 0)
                for item in instruments
            }
        start = self._start.setdefault(instrument, holdings.positions.get(instrument, 0))
        owned = holdings.positions.get(instrument, 0)
        pending = sum(
            order.working
            for order in orders
            if order.instrument_id == instrument and order.side == side
        )
        if side == "BUY":
            return max(0, start + self._limit - owned - pending)
        return max(0, owned - pending - (start - self._limit))


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
        stale = [order for order in orders if now - order.created_at > self._config.quote_lifetime]
        if stale:
            return Cancel(min(stale, key=lambda order: order.created_at).order_id)
        if not tradable:
            return None
        if len(orders) >= self._config.max_open_orders:
            return Cancel(min(orders, key=lambda order: order.created_at).order_id)
        instrument = market.instruments[int(self._rng.integers(len(market.instruments)))]
        side = "BUY" if self._rng.random() < 0.5 else "SELL"
        offset = int(self._rng.integers(-self._config.spread_ticks, self._config.spread_ticks + 1))
        limit = min(
            max(instrument.anchor() + offset * instrument.tick, instrument.tick),
            instrument.max_price,
        )
        wanted = int(self._rng.integers(1, self._config.max_quantity + 1))
        quantity = min(
            wanted,
            self._affordable(instrument, side, limit, holdings),
            self._positions.room(
                instrument.instrument_id, side, holdings, orders, market.instruments
            ),
            instrument.max_quantity,
        )
        if quantity < instrument.min_quantity:
            return None
        return Place(instrument.instrument_id, side, quantity, limit)

    @staticmethod
    def _affordable(instrument: Instrument, side: str, limit: int, holdings: Holdings) -> int:
        """Never more than the bot holds. The exchange still checks; nobody tops a bot up."""
        if side == "BUY":
            return holdings.cash // limit
        return holdings.shares.get(instrument.instrument_id, 0)
