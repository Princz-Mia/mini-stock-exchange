"""The momentum strategy: a buy or sell bias from a short against a long moving price."""

import math
from collections import deque
from dataclasses import dataclass
from uuid import UUID

import numpy as np

from mse_simulator.market import Instrument, MarketView
from mse_simulator.strategy import (
    Holdings,
    Intent,
    OpenOrder,
    Place,
    Positions,
    affordable,
    clamp,
    oldest_stale,
)


class PriceSignal:
    """Each instrument's price, one sample per market poll, shared by every momentum bot of a run:
    the last trade, or the anchor before the first one."""

    def __init__(self, short: int, long: int) -> None:
        if not 1 <= short < long:
            raise ValueError("the short window must be shorter than the long one")
        self._short = short
        self._long = long
        self._prices: dict[UUID, deque[int]] = {}

    def observe(self, market: MarketView) -> None:
        """Samples only open, live data: a frozen feed would read as a flat market."""
        if not (market.open and market.live):
            return
        for item in market.instruments:
            price = item.last if item.last is not None else item.anchor()
            self._prices.setdefault(item.instrument_id, deque(maxlen=self._long)).append(price)

    def strength(self, instrument_id: UUID) -> float | None:
        """(short average − long average) / long average; None until the long window is full."""
        prices = self._prices.get(instrument_id)
        if prices is None or len(prices) < self._long:
            return None
        long = sum(prices) / self._long
        short = sum(list(prices)[-self._short :]) / self._short
        return (short - long) / long


@dataclass(frozen=True)
class MomentumConfig:
    max_quantity: int
    max_position: int
    # A signal weaker than this, as a fraction of the price, isn't a trend.
    threshold: float
    cooldown: float
    order_lifetime: float


class MomentumBot:
    """Follows a trend with one order at a time. The side is drawn, biased by the signal: at the
    threshold about three in four orders follow the trend; at three times the threshold, nineteen
    in twenty.
    Orders take the best opposite quote, and the position stays within the run's limit."""

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
            if (strength := self._signal.strength(item.instrument_id)) is not None
            and abs(strength) >= self._config.threshold
        ]
        if not trends:
            return None
        instrument, strength = trends[int(self._rng.integers(len(trends)))]
        buy = 1.0 / (1.0 + math.exp(-strength / self._config.threshold))
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

    @staticmethod
    def _limit(instrument: Instrument, side: str) -> int:
        """The best opposite quote, or a tick through the anchor when that side is empty."""
        if side == "BUY":
            quote, step = instrument.ask, instrument.tick
        else:
            quote, step = instrument.bid, -instrument.tick
        return clamp(quote if quote is not None else instrument.anchor() + step, instrument)
