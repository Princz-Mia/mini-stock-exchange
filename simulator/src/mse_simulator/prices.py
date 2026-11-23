"""Turns a price a strategy worked out into a limit the exchange accepts. Every limit a bot sends
goes through here, so prices are rounded once, in one way."""

import math

from mse_simulator.market import Instrument


def limit_price(value: float, side: str, instrument: Instrument) -> int:
    """A limit for ``side`` from a price in cents that need not be on the tick grid: a buy rounds
    down and a sell rounds up, then the result is kept inside the instrument's price band.

    Rounding away from the other side treats buys and sells alike, so it moves neither side's
    orders toward trading more often than the other's, and it never makes an order more
    aggressive than the strategy asked for. Rounding both sides the same way (always down, say)
    would make one side cross more often, and prices would drift."""
    tick = instrument.tick
    steps = math.floor(value / tick) if side == "BUY" else math.ceil(value / tick)
    return min(max(steps * tick, tick), instrument.max_price)
