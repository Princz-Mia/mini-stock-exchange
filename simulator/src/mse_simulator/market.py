"""One shared view of the public market, polled once per interval for every bot."""

from dataclasses import dataclass
from decimal import Decimal
from uuid import UUID

import httpx


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

    def anchor(self) -> int:
        """Where noise centres: the mid if both sides quote, else the last trade, else the
        reference."""
        if self.bid is not None and self.ask is not None:
            return (self.bid + self.ask) // 2
        if self.last is not None:
            return self.last
        return self.reference


@dataclass(frozen=True)
class MarketView:
    open: bool
    live: bool
    instruments: tuple[Instrument, ...]
    fetched_at: float

    def tradable(self, now: float, max_age: float) -> bool:
        """New exposure only while the market is open and the data is live and recent."""
        return (
            self.open and self.live and bool(self.instruments) and now - self.fetched_at <= max_age
        )


CLOSED = MarketView(open=False, live=False, instruments=(), fetched_at=float("-inf"))


async def fetch(
    http: httpx.AsyncClient, execution_url: str, market_data_url: str, now: float
) -> MarketView:
    """The authoritative phase from Execution, quotes from Market Data. Public, no credentials."""
    phase = await http.get(f"{execution_url}/api/v1/market")
    phase.raise_for_status()
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
        open=phase.json()["state"] == "OPEN",
        live=body["feed"]["state"] == "LIVE",
        instruments=instruments,
        fetched_at=now,
    )
