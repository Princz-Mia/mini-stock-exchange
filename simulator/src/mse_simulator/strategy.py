"""What every strategy sees and returns: the bot's means and orders in, one intent out."""

from dataclasses import dataclass, field
from typing import Protocol
from uuid import UUID

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
    limit: int | None = None


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


class Strategy(Protocol):
    """Decisions depend only on the bot's seed and what it observes: same inputs, same intents."""

    def decide(
        self,
        market: MarketView,
        tradable: bool,
        holdings: Holdings,
        orders: list[OpenOrder],
        now: float,
    ) -> Intent | None: ...


class Positions:
    """A run's position limit: a bot's shares of an instrument may move at most ``limit`` from where
    they stood when it first looked, counting what its open orders could still fill."""

    def __init__(self, limit: int) -> None:
        self._limit = limit
        self._start: dict[UUID, int] | None = None

    def _start_of(
        self, instrument: UUID, holdings: Holdings, instruments: tuple[Instrument, ...]
    ) -> int:
        if self._start is None:
            self._start = {
                item.instrument_id: holdings.positions.get(item.instrument_id, 0)
                for item in instruments
            }
        return self._start.setdefault(instrument, holdings.positions.get(instrument, 0))

    def moved(
        self, instrument: UUID, holdings: Holdings, instruments: tuple[Instrument, ...]
    ) -> int:
        """Shares owned now less those owned at the start: positive when long."""
        start = self._start_of(instrument, holdings, instruments)
        return holdings.positions.get(instrument, 0) - start

    def room(
        self,
        instrument: UUID,
        side: str,
        holdings: Holdings,
        orders: list[OpenOrder],
        instruments: tuple[Instrument, ...],
    ) -> int:
        start = self._start_of(instrument, holdings, instruments)
        owned = holdings.positions.get(instrument, 0)
        pending = sum(
            order.working
            for order in orders
            if order.instrument_id == instrument and order.side == side
        )
        if side == "BUY":
            return max(0, start + self._limit - owned - pending)
        return max(0, owned - pending - (start - self._limit))


def affordable(instrument: Instrument, side: str, limit: int, holdings: Holdings) -> int:
    """Never more than the bot holds. The exchange still checks; nobody tops a bot up."""
    if side == "BUY":
        return holdings.cash // limit
    return holdings.shares.get(instrument.instrument_id, 0)


def oldest_stale(orders: list[OpenOrder], now: float, lifetime: float) -> Cancel | None:
    """The oldest order past its lifetime. Cancelling is allowed even while new exposure pauses."""
    stale = [order for order in orders if now - order.created_at > lifetime]
    return Cancel(min(stale, key=lambda order: order.created_at).order_id) if stale else None


def clamp(limit: int, instrument: Instrument) -> int:
    """A limit inside the instrument's price band: at least one tick, at most its maximum."""
    return min(max(limit, instrument.tick), instrument.max_price)
