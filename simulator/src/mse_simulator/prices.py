"""Turns a price a strategy worked out into a limit the exchange accepts. Every limit a bot sends
goes through here, so prices are rounded once, in one way."""

import math

from mse_simulator.market import Instrument

# Offsets and spreads are given in basis points of a price: a hundredth of a percent.
BASIS_POINT = 1e-4

# How close to a grid price, in ticks, counts as on it.
GRID_TOLERANCE = 1e-6


def limit_price(value: float, side: str, instrument: Instrument) -> int:
    """A limit for ``side`` from a price in cents that need not be on the tick grid: a buy rounds
    down and a sell rounds up, then the result is kept inside the instrument's price band.

    Rounding away from the other side treats buys and sells alike, so it moves neither side's
    orders toward trading more often than the other's, and it never makes an order more
    aggressive than the strategy asked for. Rounding both sides the same way (always down, say)
    would make one side cross more often, and prices would drift."""
    exact = value / instrument.tick
    # A price worked out in floating point can miss the grid by a hair (100.10 as
    # 100.10000000000001), which must not cost a whole tick. Either side, alike.
    nearest = round(exact)
    if abs(exact - nearest) < GRID_TOLERANCE:
        steps = nearest
    else:
        steps = math.floor(exact) if side == "BUY" else math.ceil(exact)
    return min(max(steps * instrument.tick, instrument.tick), instrument.max_price)
