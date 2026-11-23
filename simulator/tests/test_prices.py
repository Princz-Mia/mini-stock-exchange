import math

import numpy as np
from test_noise import instrument

from mse_simulator.prices import limit_price


def test_a_buy_rounds_down_and_a_sell_up_onto_the_grid_and_into_the_band() -> None:
    draws = np.random.default_rng(20261123)
    for _ in range(2_000):
        tick = int(draws.choice([1, 5, 25]))
        item = instrument()
        item = type(item)(**{**item.__dict__, "tick": tick, "max_price": 1_000_000 * tick})
        value = float(draws.uniform(-50, 1_100_000 * tick))
        buy, sell = limit_price(value, "BUY", item), limit_price(value, "SELL", item)
        for limit in (buy, sell):
            assert isinstance(limit, int)
            assert limit % tick == 0 and tick <= limit <= item.max_price
        if tick <= value <= item.max_price:
            assert buy <= value <= sell, "never more aggressive than asked"
            assert sell - buy == (0 if value % tick == 0 else tick)
    edge = instrument()
    assert limit_price(0.4, "BUY", edge) == 1 and limit_price(-3.0, "SELL", edge) == 1
    assert limit_price(1e12, "BUY", edge) == edge.max_price == limit_price(1e12, "SELL", edge)


def test_rounding_favours_neither_side() -> None:
    """A price and its mirror image round to mirror images: what rounding does to a buy above a
    level, it does to a sell below it. Rounding every limit down breaks this."""
    draws = np.random.default_rng(20261123)
    item = instrument()
    centre = 10_000
    for _ in range(2_000):
        away = float(draws.uniform(0, 500))
        buy = limit_price(centre + away, "BUY", item)
        sell = limit_price(centre - away, "SELL", item)
        assert buy - centre == centre - sell
        assert buy == centre + math.floor(away)
