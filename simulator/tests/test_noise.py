from uuid import UUID, uuid4

import numpy as np

from mse_simulator.market import Instrument, MarketView
from mse_simulator.noise import Cancel, Holdings, NoiseBot, NoiseConfig, OpenOrder, Place

CONFIG = NoiseConfig(max_quantity=10, spread_ticks=5, max_open_orders=3, quote_lifetime=10.0)


def instrument(reference: int = 10_000, bid=None, ask=None, last=None) -> Instrument:
    return Instrument(
        instrument_id=uuid4(),
        symbol="NOVA",
        tick=1,
        reference=reference,
        last=last,
        bid=bid,
        ask=ask,
        max_price=100_000_000,
        min_quantity=1,
        max_quantity=1_000_000,
    )


def view(*instruments: Instrument) -> MarketView:
    return MarketView(open=True, live=True, instruments=instruments, fetched_at=0.0)


def bot(seed: int) -> NoiseBot:
    return NoiseBot(np.random.default_rng([seed, 99]), CONFIG)


def test_the_same_seed_and_observations_give_the_same_intents() -> None:
    market = view(instrument(), instrument(reference=5_000, bid=4_990, ask=5_010))
    holdings = Holdings(cash=1_000_000, shares={i.instrument_id: 100 for i in market.instruments})

    def decisions(seed: int) -> list:
        strategy = bot(seed)
        return [strategy.decide(market, True, holdings, [], 1.0) for _ in range(50)]

    assert decisions(1) == decisions(1)
    assert decisions(1) != decisions(2)
    assert {intent.side for intent in decisions(1) if isinstance(intent, Place)} == {"BUY", "SELL"}


def test_orders_stay_near_the_anchor_and_within_what_the_bot_holds() -> None:
    draws = np.random.default_rng(2026)
    for seed in range(300):
        quoted = instrument(
            reference=int(draws.integers(100, 20_000)),
            bid=int(draws.integers(100, 10_000)) if draws.random() < 0.5 else None,
            ask=int(draws.integers(10_000, 20_000)) if draws.random() < 0.5 else None,
            last=int(draws.integers(100, 20_000)) if draws.random() < 0.5 else None,
        )
        holdings = Holdings(
            cash=int(draws.integers(0, 50_000)),
            shares={quoted.instrument_id: int(draws.integers(0, 4))},
        )
        intent = bot(seed).decide(view(quoted), True, holdings, [], 0.0)
        if intent is None:
            continue
        assert isinstance(intent, Place)
        assert abs(intent.limit - quoted.anchor()) <= CONFIG.spread_ticks * quoted.tick
        assert 1 <= intent.quantity <= CONFIG.max_quantity
        if intent.side == "BUY":
            assert intent.quantity * intent.limit <= holdings.cash
        else:
            assert intent.quantity <= holdings.shares[quoted.instrument_id]


def test_the_anchor_is_the_mid_then_the_last_trade_then_the_reference() -> None:
    assert instrument(bid=9_900, ask=10_100, last=5_000).anchor() == 10_000
    assert instrument(bid=9_900, last=5_000).anchor() == 5_000
    assert instrument(reference=7_000).anchor() == 7_000


def test_stale_quotes_are_cancelled_first_even_while_exposure_is_paused() -> None:
    orders = [OpenOrder("new", 95.0), OpenOrder("old", 80.0), OpenOrder("older", 85.0)]
    assert bot(1).decide(view(instrument()), False, Holdings(0, {}), orders, 100.0) == Cancel("old")
    fresh = [OpenOrder("new", 95.0)]
    assert bot(1).decide(view(instrument()), False, Holdings(10**9, {}), fresh, 100.0) is None


def test_at_the_open_order_cap_the_oldest_order_makes_room() -> None:
    orders = [OpenOrder("a", 99.0), OpenOrder("b", 97.0), OpenOrder("c", 98.0)]
    decided = bot(1).decide(view(instrument()), True, Holdings(10**9, {}), orders, 100.0)
    assert decided == Cancel("b")


def test_a_bot_with_nothing_to_trade_stays_quiet() -> None:
    quoted = instrument()
    empty = Holdings(cash=0, shares={UUID(int=0): 5})
    assert all(bot(seed).decide(view(quoted), True, empty, [], 0.0) is None for seed in range(50))
