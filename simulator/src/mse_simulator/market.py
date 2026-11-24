"""One shared view of the public market, polled once per interval for every bot."""

from collections.abc import Callable
from dataclasses import dataclass, replace
from decimal import Decimal
from uuid import UUID

import httpx

# The widest book whose mid the bots take for a price, in basis points of that mid: the default
# of the setting narrow_book_bp.
NARROW_BOOK_BP = 100.0


def cents(price: str) -> int:
    """Exact cents from a two-decimal string such as "100.25"."""
    return int(Decimal(price) * 100)


def price(amount: int) -> str:
    return f"{amount // 100}.{amount % 100:02d}"


@dataclass(frozen=True)
class Instrument:
    instrument_id: UUID
    symbol: str
    tick: int
    reference: int
    last: int | None
    bid: int | None
    ask: int | None
    max_price: int
    min_quantity: int
    max_quantity: int

    def mid(self, narrow: float) -> float | None:
        """The mid of a narrow book, in cents: both sides quoted, at most ``narrow`` of the mid or
        one tick apart. None otherwise. The mid of a wide book is no price anyone offered to trade
        at: a single order far from the rest, facing nothing near it, would make it whatever that
        order says. The mid is exact, so it may lie half a tick off the grid: rounding it here,
        one way for every order, would push prices that way. ``prices.limit_price`` rounds each
        order's limit instead."""
        if self.bid is None or self.ask is None:
            return None
        mid = (self.bid + self.ask) / 2
        return mid if self.ask - self.bid <= max(narrow * mid, self.tick) else None

    def anchor(self, narrow: float) -> float:
        """Where the market stands, in cents: the mid of a narrow book, else the last trade, else
        the reference."""
        mid = self.mid(narrow)
        if mid is not None:
            return mid
        if self.last is not None:
            return float(self.last)
        return float(self.reference)


OPEN = "OPEN"
CLOSED = "CLOSED"


@dataclass(frozen=True)
class MarketView:
    # The phase as Execution names it. Only OPEN trades; every other phase pauses new orders, and
    # only CLOSED is final.
    state: str
    live: bool
    instruments: tuple[Instrument, ...]
    # When the answers arrived, on the worker's clock: the data is as old as that, however long
    # the poll took.
    fetched_at: float

    @property
    def open(self) -> bool:
        return self.state == OPEN

    @property
    def closed(self) -> bool:
        """Closed for good: unlike a halt, no resume follows."""
        return self.state == CLOSED

    def tradable(self, now: float, max_age: float) -> bool:
        """New exposure only while the market is open and the data is live and recent."""
        return (
            self.open and self.live and bool(self.instruments) and now - self.fetched_at <= max_age
        )

    def only(self, instrument_ids: frozenset[UUID]) -> MarketView:
        """The same view, limited to a run's instruments."""
        return replace(
            self,
            instruments=tuple(
                item for item in self.instruments if item.instrument_id in instrument_ids
            ),
        )


# Before the first poll: no phase yet, and too old to trade on.
UNSEEN = MarketView(state="UNKNOWN", live=False, instruments=(), fetched_at=float("-inf"))


async def phase(http: httpx.AsyncClient, execution_url: str) -> str:
    """The market's phase, which only Execution decides."""
    answer = await http.get(f"{execution_url}/api/v1/market")
    answer.raise_for_status()
    return answer.json()["state"]


async def fetch(
    http: httpx.AsyncClient, execution_url: str, market_data_url: str, clock: Callable[[], float]
) -> MarketView:
    """The authoritative phase from Execution, quotes from Market Data. Public, no credentials.
    Stamped by ``clock`` once both have answered: stamped when the poll began, a slow poll would
    make fresh data look old and pause the bots."""
    state = await phase(http, execution_url)
    if state == CLOSED:
        # Nothing is quoted any more, and the end of the market must not wait for Market Data.
        return MarketView(state=state, live=False, instruments=(), fetched_at=clock())
    listing = await http.get(f"{market_data_url}/api/v1/instruments")
    listing.raise_for_status()
    body = listing.json()

    def quote(value: dict | None) -> int | None:
        return None if value is None else cents(value["price"])

    instruments = tuple(
        Instrument(
            instrument_id=UUID(item["instrumentId"]),
            symbol=item["symbol"],
            tick=cents(item["tick"]),
            reference=cents(item["referencePrice"]),
            last=None if item["lastPrice"] is None else cents(item["lastPrice"]),
            bid=quote(item["bestBid"]),
            ask=quote(item["bestAsk"]),
            max_price=cents(item["maxPrice"]),
            min_quantity=item["minQuantity"],
            max_quantity=item["maxQuantity"],
        )
        for item in body["instruments"]
    )
    return MarketView(
        state=state,
        live=body["feed"]["state"] == "LIVE",
        instruments=instruments,
        fetched_at=clock(),
    )
