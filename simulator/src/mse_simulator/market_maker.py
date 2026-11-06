"""The market-maker strategy: one bid and one ask around a reservation price that leans against
the bot's inventory."""

from dataclasses import dataclass
from uuid import UUID

import numpy as np

from mse_simulator.market import Instrument, MarketView
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

SIDES = ("BUY", "SELL")


@dataclass(frozen=True)
class MarketMakerConfig:
    quote_size: int
    max_position: int
    # Each quote sits this many ticks from the reservation price.
    half_spread_ticks: int
    # The reservation price moves this many ticks against each quote size of inventory.
    skew_ticks: int
    # A quote this many ticks off its target, or crossing the other side, is replaced.
    requote_ticks: int
    quote_lifetime: float


class MarketMaker:
    """Quotes one instrument, drawn once from the run's. The reservation price is the anchor moved
    ``skew_ticks`` down for each quote size the bot is long, and up for each it is short: a long
    maker quotes lower to sell down, a short one higher. Old quotes are cancelled before new ones
    are placed, so at most one quote per side rests; quotes still near their target stay put. It
    never quotes more than it holds or than the run's position limit allows."""

    def __init__(self, rng: np.random.Generator, config: MarketMakerConfig) -> None:
        self._rng = rng
        self._config = config
        self._positions = Positions(config.max_position)
        self._instrument: UUID | None = None

    def decide(
        self,
        market: MarketView,
        tradable: bool,
        holdings: Holdings,
        orders: list[OpenOrder],
        now: float,
    ) -> Intent | None:
        stale = oldest_stale(orders, now, self._config.quote_lifetime)
        if stale is not None:
            return stale
        if not tradable:
            return None
        if self._instrument is None:
            pick = market.instruments[int(self._rng.integers(len(market.instruments)))]
            self._instrument = pick.instrument_id
        instrument = next(
            (item for item in market.instruments if item.instrument_id == self._instrument), None
        )
        if instrument is None:
            return None
        bid, ask = self.quotes(instrument, holdings, market.instruments)
        if bid >= ask:
            return None
        mine = [order for order in orders if order.instrument_id == self._instrument]
        for side in SIDES:
            # Newest first: the newest quote on a side is the one kept.
            quotes = sorted(
                (order for order in mine if order.side == side),
                key=lambda order: order.created_at,
                reverse=True,
            )
            if len(quotes) > 1:
                return Cancel(quotes[-1].order_id)
            if quotes and self._off(quotes[0], bid, ask, instrument.tick):
                return Cancel(quotes[0].order_id)
        for side in SIDES:
            if any(order.side == side for order in mine):
                continue
            limit = bid if side == "BUY" else ask
            quantity = min(
                self._config.quote_size,
                affordable(instrument, side, limit, holdings),
                self._positions.room(
                    instrument.instrument_id, side, holdings, orders, market.instruments
                ),
                instrument.max_quantity,
            )
            if quantity >= instrument.min_quantity:
                return Place(instrument.instrument_id, side, quantity, limit)
        return None

    def quotes(
        self, instrument: Instrument, holdings: Holdings, instruments: tuple[Instrument, ...]
    ) -> tuple[int, int]:
        """The bid and ask the maker wants now, on the tick grid and inside the price band."""
        tick = instrument.tick
        moved = self._positions.moved(instrument.instrument_id, holdings, instruments)
        lean = round(self._config.skew_ticks * moved / self._config.quote_size)
        reservation = round(instrument.anchor() / tick) * tick - lean * tick
        half = self._config.half_spread_ticks * tick
        return clamp(reservation - half, instrument), clamp(reservation + half, instrument)

    def _off(self, order: OpenOrder, bid: int, ask: int, tick: int) -> bool:
        if order.limit is None:
            return True
        if order.side == "BUY":
            target, crosses = bid, order.limit >= ask
        else:
            target, crosses = ask, order.limit <= bid
        return crosses or abs(order.limit - target) >= self._config.requote_ticks * tick
