"""The market-maker strategy: one bid and one ask around a reservation price that leans against
the bot's inventory."""

from dataclasses import dataclass
from uuid import UUID

import numpy as np

from mse_simulator.market import Instrument, MarketView
from mse_simulator.prices import BASIS_POINT, limit_price
from mse_simulator.strategy import (
    Cancel,
    Holdings,
    Intent,
    OpenOrder,
    Place,
    Positions,
    Replace,
    affordable,
    oldest_stale,
)
from mse_simulator.values import View

SIDES = ("BUY", "SELL")


@dataclass(frozen=True)
class MarketMakerConfig:
    quote_size: int
    max_position: int
    # Each quote sits this far from the reservation price, in basis points of it; at least a tick.
    half_spread_bp: float
    # The reservation price moves this far against each quote size of inventory.
    skew_bp: float
    # A quote this far off its target, or crossing the other side, is replaced. At least a tick.
    requote_bp: float
    quote_lifetime: float


class MarketMaker:
    """Quotes one instrument: the one its place among the run's makers gives it, so the makers
    spread evenly over the run's instruments, or one drawn from its seed. The reservation price is
    the bot's own idea of the value, moved ``skew_bp`` down for each quote size the bot is long and
    up for each it is short: a long maker quotes lower to sell down, a short one higher. Because
    it quotes around a value and not around the current price, it buys below that value and sells
    above it wherever the market goes, which is what pulls the market back. A quote past its
    lifetime is replaced in the same step that cancels it, by the side that has no quote, the
    cancelled side last: a maker whose turns come further apart than the lifetime then alternates
    its bid and its ask instead of only ever bidding, and one with closer turns keeps both. Other
    old quotes are cancelled before new ones are placed, so at most one quote per side rests;
    quotes still near their target stay put. It never quotes more than it holds or than the run's
    position limit allows."""

    def __init__(
        self,
        rng: np.random.Generator,
        config: MarketMakerConfig,
        view: View | None = None,
        place: int | None = None,
    ) -> None:
        self._rng = rng
        self._config = config
        self._view = view or View(None)
        self._place = place
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
        if not tradable:
            # Cancelling is allowed while new exposure pauses; a replacement is not.
            return stale
        if self._instrument is None:
            self._instrument = self._pick(market.instruments)
        instrument = next(
            (item for item in market.instruments if item.instrument_id == self._instrument), None
        )
        if instrument is None:
            return stale
        bid, ask = self.quotes(instrument, holdings, market.instruments)
        if bid >= ask:
            return stale
        if stale is not None:
            # With turns further apart than the lifetime every turn finds a stale quote: a cancel
            # alone would use up each turn, and the side placed after it would always be the bid.
            left = [order for order in orders if order.order_id != stale.order_id]
            gone = next(
                (
                    order.side
                    for order in orders
                    if order.order_id == stale.order_id and order.instrument_id == self._instrument
                ),
                None,
            )
            if any(
                self._off(order, bid, ask, instrument.tick)
                for order in left
                if order.instrument_id == self._instrument
            ):
                # A quote left off its target could cross the new one: it goes first, next turn.
                return stale
            fresh = self._missing(instrument, bid, ask, holdings, left, market, last=gone)
            return stale if fresh is None else Replace(stale, fresh)
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
        return self._missing(instrument, bid, ask, holdings, orders, market)

    def _missing(
        self,
        instrument: Instrument,
        bid: int,
        ask: int,
        holdings: Holdings,
        orders: list[OpenOrder],
        market: MarketView,
        last: str | None = None,
    ) -> Place | None:
        """The first side with no quote that the bot can afford, in the usual order but ``last``
        tried last."""
        mine = [order for order in orders if order.instrument_id == instrument.instrument_id]
        for side in sorted(SIDES, key=lambda side: side == last):
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

    def _pick(self, instruments: tuple[Instrument, ...]) -> UUID:
        if self._place is None:
            return instruments[int(self._rng.integers(len(instruments)))].instrument_id
        ordered = sorted(item.instrument_id for item in instruments)
        return ordered[self._place % len(ordered)]

    def quotes(
        self, instrument: Instrument, holdings: Holdings, instruments: tuple[Instrument, ...]
    ) -> tuple[int, int]:
        """The bid and ask the maker wants now, on the tick grid and inside the price band. The
        reservation price itself is not rounded: only the two quotes are, each away from the other,
        so the pair stays centred on it."""
        moved = self._positions.moved(instrument.instrument_id, holdings, instruments)
        lean = self._config.skew_bp * BASIS_POINT * moved / self._config.quote_size
        reservation = self._view.of(instrument) * (1 - lean)
        half = max(self._config.half_spread_bp * BASIS_POINT * reservation, instrument.tick)
        return (
            limit_price(reservation - half, "BUY", instrument),
            limit_price(reservation + half, "SELL", instrument),
        )

    def _off(self, order: OpenOrder, bid: int, ask: int, tick: int) -> bool:
        if order.limit is None:
            return True
        if order.side == "BUY":
            target, crosses = bid, order.limit >= ask
        else:
            target, crosses = ask, order.limit <= bid
        drift = max(self._config.requote_bp * BASIS_POINT * target, tick)
        return crosses or abs(order.limit - target) >= drift
