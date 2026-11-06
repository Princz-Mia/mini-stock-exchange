from dataclasses import replace
from uuid import uuid4

import numpy as np
import pytest

from mse_simulator.market import Instrument, MarketView
from mse_simulator.market_maker import MarketMaker, MarketMakerConfig
from mse_simulator.momentum import MomentumBot, MomentumConfig, PriceSignal
from mse_simulator.strategy import Cancel, Holdings, OpenOrder, Place


def instrument(reference: int = 10_000, bid=None, ask=None, last=None, tick: int = 1) -> Instrument:
    return Instrument(
        instrument_id=uuid4(),
        symbol="NOVA",
        tick=tick,
        reference=reference,
        last=last,
        bid=bid,
        ask=ask,
        max_price=100_000_000,
        min_quantity=1,
        max_quantity=1_000_000,
    )


def view(*instruments: Instrument, open: bool = True, live: bool = True) -> MarketView:
    return MarketView(open=open, live=live, instruments=instruments, fetched_at=0.0)


def rich(*instruments: Instrument, position: int = 0) -> Holdings:
    return Holdings(
        cash=10**10,
        shares={item.instrument_id: 10**6 for item in instruments},
        positions={item.instrument_id: position for item in instruments},
    )


# Momentum


MOMENTUM = MomentumConfig(
    max_quantity=10, max_position=50, threshold=0.001, cooldown=5.0, order_lifetime=10.0
)


def trending(signal: PriceSignal, item: Instrument, prices: list[int]) -> Instrument:
    """Feeds the signal one poll per price; returns the instrument as of the last one."""
    for price in prices:
        item = replace(item, last=price)
        signal.observe(view(item))
    return item


def test_the_signal_needs_a_full_window_and_samples_only_open_live_data() -> None:
    signal = PriceSignal(2, 4)
    item = instrument(reference=10_000)
    signal.observe(view(item))  # no trade yet: the anchor, which is the reference
    item = trending(signal, item, [10_100, 10_200])
    assert signal.strength(item.instrument_id) is None
    signal.observe(view(replace(item, last=99_999), live=False))
    signal.observe(view(replace(item, last=99_999), open=False))
    assert signal.strength(item.instrument_id) is None, "a frozen or closed feed isn't sampled"
    trending(signal, item, [10_300])
    long, short = (10_000 + 10_100 + 10_200 + 10_300) / 4, (10_200 + 10_300) / 2
    assert signal.strength(item.instrument_id) == pytest.approx((short - long) / long)
    trending(signal, item, [10_300, 10_300, 10_300, 10_300])
    assert signal.strength(item.instrument_id) == 0.0, "old samples leave the long window"
    with pytest.raises(ValueError):
        PriceSignal(3, 3)


def momentum(seed: int, signal: PriceSignal, config: MomentumConfig = MOMENTUM) -> MomentumBot:
    return MomentumBot(np.random.default_rng([seed, 42]), config, signal)


def test_the_same_seed_and_observations_give_the_same_momentum_intents() -> None:
    rising, falling = instrument(ask=10_400), instrument(bid=9_600)

    def decisions(seed: int) -> list:
        signal = PriceSignal(2, 4)
        up = trending(signal, rising, [10_000, 10_100, 10_200, 10_400])
        down = trending(signal, falling, [10_000, 9_900, 9_800, 9_600])
        market = view(up, down)
        bot = momentum(seed, signal)
        return [bot.decide(market, True, rich(up, down), [], float(t)) for t in range(0, 100, 5)]

    first = decisions(1)
    assert first == decisions(1)
    assert all(isinstance(intent, Place) for intent in first)
    assert any(decisions(seed) != first for seed in range(2, 6))


@pytest.mark.parametrize("direction", [1, -1])
def test_momentum_leans_with_the_trend(direction: int) -> None:
    signal = PriceSignal(2, 4)
    # Short average 1.5 % from the long one: fifteen times the threshold.
    item = trending(signal, instrument(), [10_000 + direction * step * 100 for step in range(4)])
    with_trend = "BUY" if direction > 0 else "SELL"
    sides = []
    for seed in range(400):
        intent = momentum(seed, signal).decide(view(item), True, rich(item, position=25), [], 0.0)
        assert isinstance(intent, Place)
        sides.append(intent.side)
    assert sides.count(with_trend) >= 390


def test_momentum_bias_follows_the_signal_strength() -> None:
    """At the threshold, about three in four orders follow the trend; with no bias it'd be half."""
    signal = PriceSignal(1, 2)
    item = trending(signal, instrument(), [10_000, 10_020])  # strength 0.001 / 1.001
    config = replace(MOMENTUM, threshold=0.000999)
    buys = sum(
        momentum(seed, signal, config)
        .decide(view(item), True, rich(item, position=25), [], 0.0)
        .side
        == "BUY"
        for seed in range(2_000)
    )
    assert 1_350 <= buys <= 1_570


def test_momentum_stays_out_of_flat_markets_until_the_window_fills_and_while_paused() -> None:
    signal = PriceSignal(2, 4)
    item = trending(signal, instrument(), [10_000, 10_000, 10_100])
    bot = momentum(1, signal)
    assert bot.decide(view(item), True, rich(item), [], 0.0) is None, "window not full"
    flat = PriceSignal(2, 4)
    level = trending(flat, instrument(), [10_000, 10_005, 10_005, 10_005])
    assert abs(flat.strength(level.instrument_id)) < MOMENTUM.threshold
    assert momentum(1, flat).decide(view(level), True, rich(level), [], 0.0) is None
    item = trending(signal, item, [10_400])
    assert bot.decide(view(item), False, rich(item), [], 0.0) is None, "paused data"
    assert isinstance(bot.decide(view(item), True, rich(item), [], 0.0), Place)


def test_momentum_keeps_one_order_at_a_time_and_a_cooldown() -> None:
    signal = PriceSignal(2, 4)
    item = trending(signal, instrument(ask=10_500), [10_000, 10_100, 10_200, 10_400])
    bot = momentum(3, signal)
    holdings = rich(item, position=25)
    first = bot.decide(view(item), True, holdings, [], 100.0)
    assert isinstance(first, Place)
    resting = OpenOrder("o-1", 100.0, item.instrument_id, first.side, first.quantity, first.limit)
    assert bot.decide(view(item), True, holdings, [resting], 106.0) is None, "one at a time"
    assert bot.decide(view(item), True, holdings, [resting], 111.0) == Cancel("o-1"), "stale"
    assert bot.decide(view(item), True, holdings, [], 104.9) is None, "cooling down"
    assert isinstance(bot.decide(view(item), True, holdings, [], 105.0), Place)
    assert bot.decide(view(item), True, holdings, [], 109.9) is None, "after every order"
    assert isinstance(bot.decide(view(item), True, holdings, [], 110.0), Place)


def test_momentum_takes_the_opposite_quote_or_a_tick_through_the_anchor() -> None:
    signal = PriceSignal(1, 2)
    up = trending(signal, instrument(bid=10_090, ask=10_110, tick=5), [10_000, 10_100])
    for seed in range(50):
        intent = momentum(seed, signal).decide(view(up), True, rich(up, position=25), [], 0.0)
        assert intent.limit == (10_110 if intent.side == "BUY" else 10_090)
    bare = trending(signal, instrument(tick=5), [10_200, 10_300])
    for seed in range(50):
        intent = momentum(seed, signal).decide(view(bare), True, rich(bare, position=25), [], 0.0)
        assert intent.limit == (10_305 if intent.side == "BUY" else 10_295)


def test_momentum_never_moves_a_position_past_the_cap_or_spends_what_it_lacks() -> None:
    draws = np.random.default_rng(2026)
    for case in range(200):
        direction = 1 if draws.random() < 0.5 else -1
        signal = PriceSignal(2, 4)
        item = trending(
            signal, instrument(), [10_000 + direction * step * 200 for step in range(4)]
        )
        start = int(draws.integers(0, 120))
        cash = int(draws.integers(0, 2_000_000))
        config = replace(MOMENTUM, max_position=int(draws.integers(1, 60)), cooldown=0.0)
        bot = momentum(case, signal, config)
        position, available = start, start
        for step in range(60):
            holdings = Holdings(
                cash, {item.instrument_id: available}, {item.instrument_id: position}
            )
            intent = bot.decide(view(item), True, holdings, [], float(step))
            if intent is None:
                continue
            if intent.side == "BUY":
                assert intent.quantity * intent.limit <= cash
                cash -= intent.quantity * intent.limit
                position += intent.quantity
                available += intent.quantity
            else:
                assert intent.quantity <= available
                position -= intent.quantity
                available -= intent.quantity
            # Every order fills at once here: the strictest case for the cap.
            assert abs(position - start) <= config.max_position


# Market maker


MAKER = MarketMakerConfig(
    quote_size=10,
    max_position=100,
    half_spread_ticks=2,
    skew_ticks=1,
    requote_ticks=2,
    quote_lifetime=30.0,
)


def maker(seed: int, config: MarketMakerConfig = MAKER) -> MarketMaker:
    return MarketMaker(np.random.default_rng([seed, 7]), config)


def quote(item: Instrument, side: str, limit: int, at: float = 0.0, order_id=None) -> OpenOrder:
    return OpenOrder(order_id or str(uuid4()), at, item.instrument_id, side, 10, limit)


def test_a_maker_quotes_a_bid_then_an_ask_around_the_mid_then_rests() -> None:
    item = instrument(bid=9_990, ask=10_010, tick=5)
    bot = maker(1)
    holdings = rich(item)
    bid = bot.decide(view(item), True, holdings, [], 0.0)
    assert bid == Place(item.instrument_id, "BUY", 10, 9_990)
    resting = [quote(item, "BUY", 9_990)]
    ask = bot.decide(view(item), True, holdings, resting, 0.0)
    assert ask == Place(item.instrument_id, "SELL", 10, 10_010)
    resting.append(quote(item, "SELL", 10_010))
    assert bot.decide(view(item), True, holdings, resting, 1.0) is None


def test_the_same_seed_and_observations_give_the_same_maker_intents() -> None:
    items = tuple(instrument(reference=1_000 * (index + 5)) for index in range(6))

    def decisions(seed: int) -> list:
        bot, resting, intents = maker(seed), [], []
        for step in range(6):
            intent = bot.decide(view(*items), True, rich(*items), resting, float(step))
            intents.append(intent)
            if isinstance(intent, Place):
                resting.append(quote(items[0], intent.side, intent.limit, step))
        return intents

    assert decisions(1) == decisions(1)
    picked = {decisions(seed)[0].instrument_id for seed in range(30)}
    assert len(picked) > 1, "independent makers spread over the run's instruments"


def test_inventory_leans_both_quotes_against_the_position() -> None:
    item = instrument(reference=10_000)
    flat = maker(1).quotes(item, rich(item, position=500), (item,))
    assert flat == (9_998, 10_002)
    bot = maker(1)
    bot.quotes(item, rich(item, position=500), (item,))  # the start: 500 shares
    assert bot.quotes(item, rich(item, position=530), (item,)) == (9_995, 9_999), "long: lower"
    assert bot.quotes(item, rich(item, position=480), (item,)) == (10_000, 10_004), "short: up"
    draws = np.random.default_rng(2026)
    for _ in range(300):
        tick = int(draws.choice([1, 5, 25]))
        anchor = int(draws.integers(2_000, 50_000))
        config = replace(
            MAKER,
            half_spread_ticks=int(draws.integers(1, 6)),
            skew_ticks=int(draws.integers(0, 4)),
            quote_size=int(draws.integers(1, 20)),
        )
        item = instrument(reference=anchor, tick=tick)
        bot = maker(0, config)
        bot.quotes(item, rich(item, position=1_000), (item,))
        previous = None
        for position in sorted(int(p) for p in draws.integers(900, 1_100, 8)):
            bid, ask = bot.quotes(item, rich(item, position=position), (item,))
            assert ask - bid == 2 * config.half_spread_ticks * tick
            assert bid % tick == 0 and ask % tick == 0, "on the tick grid"
            if previous is not None:
                assert bid <= previous, "more inventory never raises the quotes"
            previous = bid


def test_old_quotes_are_cancelled_before_new_ones_are_placed() -> None:
    item = instrument(reference=10_000)
    bot = maker(1)
    holdings = rich(item)
    on_target = [quote(item, "BUY", 9_998, order_id="bid"), quote(item, "SELL", 10_002)]
    assert bot.decide(view(item), True, holdings, on_target, 0.0) is None
    near = [quote(item, "BUY", 9_997, order_id="bid"), quote(item, "SELL", 10_003)]
    assert bot.decide(view(item), True, holdings, near, 0.0) is None, "within the threshold"
    off = [quote(item, "BUY", 9_996, order_id="bid")]
    assert bot.decide(view(item), True, holdings, off, 0.0) == Cancel("bid")
    # A wide threshold keeps these near enough to stay, but each crosses the other side's target.
    wide = maker(1, replace(MAKER, requote_ticks=10))
    low_ask = [quote(item, "SELL", 9_998, order_id="ask")]
    assert wide.decide(view(item), True, holdings, low_ask, 0.0) == Cancel("ask")
    high_bid = [quote(item, "BUY", 10_002, order_id="bid")]
    assert wide.decide(view(item), True, holdings, high_bid, 0.0) == Cancel("bid")
    assert wide.decide(view(item), True, holdings, near, 0.0) is None
    doubled = [quote(item, "BUY", 9_998, 1.0, "older"), quote(item, "BUY", 9_998, 2.0, "newer")]
    assert bot.decide(view(item), True, holdings, doubled, 3.0) == Cancel("older")
    stale = [quote(item, "BUY", 9_998, 0.0, "stale")]
    assert bot.decide(view(item), True, holdings, stale, 30.1) == Cancel("stale")
    assert bot.decide(view(item), False, holdings, stale, 30.1) == Cancel("stale"), "paused"
    assert bot.decide(view(item), False, holdings, [], 30.1) is None


def test_a_maker_never_places_beside_a_live_quote_or_beyond_its_means() -> None:
    draws = np.random.default_rng(2026)
    for case in range(400):
        item = instrument(reference=int(draws.integers(1_000, 20_000)))
        bot = maker(case, replace(MAKER, max_position=int(draws.integers(1, 30))))
        start = int(draws.integers(0, 40))
        bot.quotes(item, rich(item, position=start), (item,))
        position = start + int(draws.integers(-35, 35))
        holdings = Holdings(
            cash=int(draws.integers(0, 300_000)),
            shares={item.instrument_id: max(0, position - int(draws.integers(0, 5)))},
            positions={item.instrument_id: position},
        )
        orders = [
            quote(item, str(draws.choice(["BUY", "SELL"])), item.reference + int(offset))
            for offset in draws.integers(-6, 7, int(draws.integers(0, 3)))
        ]
        intent = bot.decide(view(item), True, holdings, orders, 1.0)
        if not isinstance(intent, Place):
            continue
        assert not any(order.side == intent.side for order in orders)
        bid, ask = bot.quotes(item, holdings, (item,))
        assert intent.limit == (bid if intent.side == "BUY" else ask)
        assert all(not bot._off(order, bid, ask, item.tick) for order in orders), (
            "cancels go before places"
        )
        assert 1 <= intent.quantity <= MAKER.quote_size
        pending = sum(o.working for o in orders if o.side == intent.side)
        if intent.side == "BUY":
            assert intent.quantity * intent.limit <= holdings.cash
            assert position + pending + intent.quantity - start <= bot._config.max_position
        else:
            assert intent.quantity <= holdings.shares[item.instrument_id]
            assert start - (position - pending - intent.quantity) <= bot._config.max_position


def test_a_maker_whose_instrument_left_the_view_waits() -> None:
    item, other = instrument(), instrument()
    bot = maker(1)
    picked = bot.decide(view(item), True, rich(item), [], 0.0)
    assert picked.instrument_id == item.instrument_id
    assert bot.decide(view(other), True, rich(other), [], 0.0) is None
