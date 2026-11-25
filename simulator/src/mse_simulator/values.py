"""What the bots think each instrument is worth. Nothing here touches the exchange: a value only
shapes the orders a bot chooses to send, and prices are still made by orders meeting in the book.

Without it every strategy followed the current price, so nothing pulled a price back once it had
moved: a small bias in the order flow walked it to the bottom of the price band."""

import math
from uuid import UUID

import numpy as np

from mse_simulator.market import NARROW_BOOK_BP, Instrument, MarketView
from mse_simulator.prices import BASIS_POINT

# Keeps the values' draws apart from every bot's: a bot is seeded by [run seed, account].
_STREAM = 0x56414C5545


class FairValues:
    """One fair value per instrument for a run, shared by its bots: the logarithm of the price
    wanders at random and is pulled back toward the instrument's reference price, so a value
    drifts a few percent and returns rather than running away.

    It starts at a price the market supports (``starting_price``), moves one step per market
    poll, and only while the market is open and its data live, so it does not jump at the start
    of a run or across a halt. The steps are drawn from the run's seed and the instrument, so a
    run's values repeat for the same polls."""

    def __init__(
        self,
        seed: int,
        half_life_hours: float,
        volatility: float,
        poll_seconds: float,
        narrow_book_bp: float,
    ) -> None:
        self._seed = seed
        self._narrow = narrow_book_bp * BASIS_POINT
        pull = math.log(2) / (half_life_hours * 3600)
        # The exact step of a mean-reverting walk over one poll: how much of the gap to the
        # reference remains, and the size of the random part.
        self._keep = math.exp(-pull * poll_seconds)
        per_second = volatility / math.sqrt(3600)
        self._shake = per_second * math.sqrt((1 - self._keep**2) / (2 * pull))
        self._logs: dict[UUID, float] = {}
        self._draws: dict[UUID, np.random.Generator] = {}

    def observe(self, market: MarketView) -> None:
        if not (market.open and market.live):
            return
        for item in market.instruments:
            current = self._logs.get(item.instrument_id)
            if current is None:
                self._logs[item.instrument_id] = math.log(starting_price(item, self._narrow))
                self._draws[item.instrument_id] = np.random.default_rng(
                    [self._seed, item.instrument_id.int, _STREAM]
                )
                continue
            mean = math.log(item.reference)
            step = self._shake * float(self._draws[item.instrument_id].standard_normal())
            self._logs[item.instrument_id] = mean + (current - mean) * self._keep + step

    def of(self, instrument_id: UUID) -> float | None:
        """The value in cents, or None before the first poll of an open market."""
        current = self._logs.get(instrument_id)
        return None if current is None else math.exp(current)


def starting_price(item: Instrument, narrow: float) -> float:
    """Where a run's value for an instrument starts, in cents: the last trade; else the mid of a
    narrow book (``Instrument.mid``); else the reference. The last trade comes first because two
    accounts agreed on it, where one account alone can quote both sides of a book, and a value
    lasts for hours: it closes half of its gap to the reference only every half-life.

    A trade counts however far from the reference it is, so a market that collapsed climbs back
    rather than jumps. The bots cannot tell that from one share traded far away, which starts a
    run there just the same."""
    if item.last is not None:
        return float(item.last)
    mid = item.mid(narrow)
    return float(item.reference) if mid is None else mid


class View:
    """One bot's own idea of what each instrument is worth: the run's fair value, moved by a small
    fixed fraction of the bot's own, so bots disagree a little and do not all want the same side.
    With no fair value to go by, a bot takes the market's own price."""

    def __init__(
        self, values: FairValues | None, bias: float = 0.0, narrow_book_bp: float = NARROW_BOOK_BP
    ) -> None:
        self._values = values
        self._bias = bias
        self._narrow = narrow_book_bp * BASIS_POINT

    def of(self, instrument: Instrument) -> float:
        value = None if self._values is None else self._values.of(instrument.instrument_id)
        return instrument.anchor(self._narrow) if value is None else value * (1 + self._bias)
