"""The momentum strategy: a buy or sell bias from a short against a long moving price."""

import math
from collections import deque
from dataclasses import dataclass
from uuid import UUID

import numpy as np

from mse_simulator.market import Instrument, MarketView
from mse_simulator.prices import BASIS_POINT, limit_price
from mse_simulator.strategy import (
    Holdings,
    Intent,
    OpenOrder,
    Place,
    Positions,
    affordable,
    oldest_stale,
)


class PriceSignal:
    """Each instrument's price, one sample per market poll, shared by every momentum bot of a run:
    the mid, or the last trade or the reference while a side is empty. The mid, because the last
    trade alternates between the bid and the ask while the price stands still, which would read
    as movement."""

    def __init__(self, short: int, long: int) -> None:
        if not 1 <= short < long:
            raise ValueError("the short window must be shorter than the long one")
        self._short = short
        self._long = long
        self._prices: dict[UUID, deque[float]] = {}

    def observe(self, market: MarketView) -> None:
        """Samples only open, live data: a frozen feed would read as a flat market. A halt or a gap
        in the data empties the windows, so prices from before it are not compared with prices
        after it."""
        if not (market.open and market.live):
            self._prices.clear()
            return
        for item in market.instruments:
            self._prices.setdefault(item.instrument_id, deque(maxlen=self._long)).append(
                item.anchor()
            )

    def averages(self, instrument_id: UUID) -> tuple[float, float] | None:
        """The short and the long average; None until the long window is full."""
        prices = self._prices.get(instrument_id)
        if prices is None or len(prices) < self._long:
            return None
        return sum(list(prices)[-self._short :]) / self._short, sum(prices) / self._long

    def strength(self, instrument_id: UUID) -> float | None:
        """(short average − long average) / long average; None until the long window is full."""
        averages = self.averages(instrument_id)
        if averages is None:
            return None
        short, long = averages
        return (short - long) / long


@dataclass(frozen=True)
class MomentumConfig:
    max_quantity: int
    max_position: int
    # A signal weaker than this, as a fraction of the price, isn't a trend.
    threshold: float
    cooldown: float
    order_lifetime: float
    # Nor is one where the averages are fewer than this many ticks apart: on a cheap instrument a
    # single tick is already a large fraction of the price.
    min_ticks: float = 2.0
    # How far the odds of following the trend rise above even, at most: 0.25 means three orders
    # in four follow a strong trend, and one in four still goes against it.
    follow: float = 0.25
    # An order reaches at most this far past the current price, in basis points. Within that it
    # takes the best opposite quote; beyond it, it rests, so it can't chase a thin book upward.
    reach_bp: float = 20.0


class MomentumBot:
    """Follows a trend with one order at a time. The side is drawn, leaning toward the trend by up
    to ``follow``: the lean is about three quarters of that at the threshold and nearly all of it
    at twice the threshold. It never becomes certain, so momentum bots do not all join one side.
    Orders take the best opposite quote if it is near, and the position stays within the run's
    limit."""

    def __init__(
        self, rng: np.random.Generator, config: MomentumConfig, signal: PriceSignal
    ) -> None:
        self._rng = rng
        self._config = config
        self._signal = signal
        self._positions = Positions(config.max_position)
        self._ready_at = float("-inf")

    def decide(
        self,
        market: MarketView,
        tradable: bool,
        holdings: Holdings,
        orders: list[OpenOrder],
        now: float,
    ) -> Intent | None:
        stale = oldest_stale(orders, now, self._config.order_lifetime)
        if stale is not None:
            return stale
        if not tradable or orders or now < self._ready_at:
            return None
        trends = [
            (item, strength)
            for item in market.instruments
            if (strength := self._trend(item)) is not None
        ]
        if not trends:
            return None
        instrument, strength = trends[int(self._rng.integers(len(trends)))]
        buy = 0.5 + self._config.follow * math.tanh(strength / self._config.threshold)
        side = "BUY" if self._rng.random() < buy else "SELL"
        limit = self._limit(instrument, side)
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
        self._ready_at = now + self._config.cooldown
        return Place(instrument.instrument_id, side, quantity, limit)

    def _trend(self, instrument: Instrument) -> float | None:
        """The signal's strength if it counts as a trend, else None."""
        averages = self._signal.averages(instrument.instrument_id)
        if averages is None:
            return None
        short, long = averages
        apart = abs(short - long)
        if (
            apart < self._config.threshold * long
            or apart < self._config.min_ticks * instrument.tick
        ):
            return None
        return (short - long) / long

    def _limit(self, instrument: Instrument, side: str) -> int:
        """The best opposite quote if it is within reach of the current price, else the edge of
        that reach, where the order rests."""
        reach = max(self._config.reach_bp * BASIS_POINT * instrument.anchor(), instrument.tick)
        if side == "BUY":
            edge = limit_price(instrument.anchor() + reach, side, instrument)
            return instrument.ask if instrument.ask is not None and instrument.ask <= edge else edge
        edge = limit_price(instrument.anchor() - reach, side, instrument)
        return instrument.bid if instrument.bid is not None and instrument.bid >= edge else edge
