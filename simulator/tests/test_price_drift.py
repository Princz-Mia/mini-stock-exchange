"""Where prices go when the real strategies trade with each other for a long time, in the toy
market: no network, seeded, tens of thousands of orders in a few seconds."""

from uuid import UUID

import numpy as np
from toy_market import ToyMarket

from mse_simulator.noise import NoiseBot, NoiseConfig
from mse_simulator.strategy import Holdings, Place

NOVA = UUID(int=1)


class Scripted:
    """Places what it is told, once."""

    def __init__(self, *intents: Place) -> None:
        self._intents = list(intents)

    def decide(self, market, tradable, holdings: Holdings, orders, now):
        return self._intents.pop(0) if self._intents else None


def test_the_toy_market_matches_by_price_then_time_at_the_resting_price() -> None:
    """Three sellers rest 3 and 4 at 100.00 and 5 at 101.00; a buy of 10 at 101.00 takes 3, 4 and
    3, pays 1,003.00 and keeps the 7.00 it was ready to pay beyond that."""
    market = ToyMarket([10_000])
    sellers = [
        market.join(Scripted(Place(NOVA, "SELL", quantity, limit)), cash=0, shares=10)
        for quantity, limit in [(3, 10_000), (4, 10_000), (5, 10_100)]
    ]
    buyer = market.join(Scripted(Place(NOVA, "BUY", 10, 10_100)), cash=2_000_000, shares=0)
    market.run(4, rate=4.0)
    assert [seller.shares[NOVA] for seller in sellers] == [7, 6, 7]
    assert [seller.cash for seller in sellers] == [30_000, 40_000, 30_300]
    assert buyer.shares[NOVA] == 10 and buyer.cash == 2_000_000 - 100_300
    assert buyer.cash_held == 0 and not buyer.orders
    assert sellers[2].shares_held[NOVA] == 2
    assert market.trades == 3 and market.books[NOVA].last == 10_100


def test_the_toy_market_fills_the_older_order_first_at_a_price() -> None:
    market = ToyMarket([10_000])
    first, second = (
        market.join(Scripted(Place(NOVA, "SELL", 4, 10_000)), cash=0, shares=4) for _ in range(2)
    )
    market.join(Scripted(Place(NOVA, "BUY", 5, 10_000)), cash=10**6, shares=0)
    market.run(3, rate=4.0)
    assert (first.shares[NOVA], second.shares[NOVA]) == (0, 3)


def test_the_toy_market_keeps_what_an_order_cannot_pay_for_and_a_bot_from_trading_with_itself() -> (
    None
):
    market = ToyMarket([10_000])
    poor = market.join(Scripted(Place(NOVA, "BUY", 5, 10_000)), cash=49_999, shares=0)
    both = market.join(
        Scripted(Place(NOVA, "SELL", 5, 10_000), Place(NOVA, "BUY", 5, 10_000)),
        cash=10**6,
        shares=5,
    )
    market.run(4, rate=4.0)
    assert poor.refused == 1 and not poor.orders
    assert market.self_trades == 1 and market.trades == 0
    assert both.shares[NOVA] == 5 and both.shares_held[NOVA] == 5 and both.cash_held == 0


def noise_market(seed: int, commands: int) -> ToyMarket:
    """Twelve noise bots with ample means, trading one instrument with each other."""
    market = ToyMarket([10_000])
    # Five basis points of 100.00: five ticks either side. No value to go by: the market alone.
    config = NoiseConfig(max_quantity=10, spread_bp=5.0, max_open_orders=5, quote_lifetime=10.0)
    for bot in range(12):
        market.join(NoiseBot(np.random.default_rng([seed, bot]), config), cash=10**12, shares=10**6)
    market.run(commands, rate=4.0)
    return market


def test_the_toy_market_conserves_cash_and_shares_and_never_crosses() -> None:
    market = noise_market(seed=7, commands=5_000)
    assert market.trades > 1_000
    assert sum(account.cash for account in market.accounts) == 12 * 10**12
    assert sum(account.shares[NOVA] for account in market.accounts) == 12 * 10**6
    book = market.books[NOVA]
    assert book.bids[0].limit < book.asks[0].limit
    for account in market.accounts:
        orders = account.orders.values()
        assert account.cash_held == sum(o.limit * o.working for o in orders if o.side == "BUY")
        assert account.shares_held.get(NOVA, 0) == sum(
            o.working for o in orders if o.side == "SELL"
        )


def test_noise_alone_does_not_push_prices_one_way() -> None:
    """Over 60,000 commands and about 30,000 trades per seed, random orders around the mid leave
    the price near where it started: some seeds end higher, some lower. With the mid rounded down
    to the tick, every seed fell, by 6.4% on average."""
    changes = [noise_market(seed, commands=60_000).price(NOVA) / 10_000 - 1 for seed in range(1, 6)]
    assert abs(sum(changes) / len(changes)) < 0.02, changes
    assert min(changes) < 0 < max(changes), changes
