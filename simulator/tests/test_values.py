import math
from dataclasses import replace
from uuid import UUID

import pytest
from test_noise import instrument as any_instrument
from test_strategies import view

from mse_simulator.market import Instrument
from mse_simulator.values import FairValues, View

HOUR = 3600


def instrument(number: int = 1, **quotes) -> Instrument:
    """With a fixed id: a value's path is drawn from the run's seed and the instrument."""
    return replace(any_instrument(**quotes), instrument_id=UUID(int=number))


def values(
    seed: int = 7, half_life: float = 4.0, volatility: float = 0.015, narrow: float = 100.0
) -> FairValues:
    return FairValues(seed, half_life, volatility, poll_seconds=1.0, narrow_book_bp=narrow)


def walked(fair: FairValues, item, polls: int) -> float:
    for _ in range(polls):
        fair.observe(view(item))
    return fair.of(item.instrument_id)


def test_a_value_starts_where_the_market_stands_and_moves_only_with_open_live_polls() -> None:
    fair = values()
    # A book 42 basis points wide and no trade yet.
    item = instrument(reference=10_000, bid=9_480, ask=9_520)
    assert fair.of(item.instrument_id) is None
    fair.observe(view(item, open=False))
    fair.observe(view(item, live=False))
    assert fair.of(item.instrument_id) is None, "a halted or frozen market starts nothing"
    fair.observe(view(item))
    assert fair.of(item.instrument_id) == pytest.approx(9_500), "the mid, not the reference"
    fair.observe(view(item, open=False))
    fair.observe(view(item, live=False))
    assert fair.of(item.instrument_id) == pytest.approx(9_500), "no step across a halt"
    fair.observe(view(item))
    moved = fair.of(item.instrument_id)
    assert moved != pytest.approx(9_500, abs=1e-9)
    assert abs(moved / 9_500 - 1) < 0.002, "one poll is a small step, not a jump"


def start(item: Instrument, narrow: float = 100.0) -> float:
    """Where a new run's value for the instrument starts."""
    fair = values(narrow=narrow)
    fair.observe(view(item))
    return fair.of(item.instrument_id)


def test_one_far_order_on_each_side_does_not_set_the_value() -> None:
    """A 1.00 bid and a 500.00 offer from a 100.00 reference have a mid of 250.50, which nobody
    offered: the value starts at the reference, or at the last trade if there is one."""
    far = instrument(reference=10_000, bid=100, ask=50_000)
    assert start(far) == pytest.approx(10_000)
    assert start(replace(far, last=10_200)) == pytest.approx(10_200)
    assert start(replace(far, bid=None)) == pytest.approx(10_000), "the offer alone"
    assert start(replace(far, ask=None)) == pytest.approx(10_000), "the bid alone"


def test_a_value_starts_at_the_last_trade_then_a_narrow_mid_then_the_reference() -> None:
    """The last trade is a price two accounts agreed on; a quote needs only one. A second run on
    a market that has traded, or a worker restarted mid-run, starts again from its last trade,
    however stale: the pull toward the reference does the rest."""
    narrow = instrument(reference=10_000, bid=9_480, ask=9_520)
    assert start(narrow) == pytest.approx(9_500)
    assert start(replace(narrow, last=9_000)) == pytest.approx(9_000), "the trade before the mid"
    assert start(replace(narrow, last=2_500)) == pytest.approx(2_500), "a trade far away too"
    assert start(instrument(reference=10_000)) == pytest.approx(10_000)
    # 98 and 102 basis points of 100.00 apart, against a limit of 100.
    assert start(instrument(reference=8_000, bid=9_951, ask=10_049)) == pytest.approx(10_000)
    assert start(instrument(reference=8_000, bid=9_949, ask=10_051)) == pytest.approx(8_000)


def test_the_setting_says_how_narrow_a_book_must_be_and_a_tick_always_is() -> None:
    wide = instrument(reference=8_000, bid=9_760, ask=10_240)
    assert start(wide, narrow=500) == pytest.approx(10_000), "480 basis points within 500"
    assert start(wide, narrow=100) == pytest.approx(8_000)
    # Three cents and four with a tick of a cent: a third of the price, and still the best a
    # book can be.
    penny = instrument(reference=10, bid=3, ask=4)
    assert start(penny) == pytest.approx(3.5)
    assert start(penny, narrow=0) == pytest.approx(3.5)
    assert start(replace(penny, ask=5)) == pytest.approx(10), "two ticks: half the price"


def test_the_same_run_and_polls_give_the_same_values_and_others_differ() -> None:
    nova, orbt = instrument(1), instrument(2)

    def path(seed: int, item) -> list[float]:
        fair = values(seed)
        return [walked(fair, item, 1) for _ in range(50)]

    assert path(7, nova) == path(7, nova)
    assert path(7, nova) != path(8, nova), "another run's seed"
    assert path(7, nova) != path(7, replace(orbt, reference=nova.reference)), "another instrument"


def test_without_chance_a_gap_to_the_reference_halves_every_half_life() -> None:
    fair = values(half_life=1.0, volatility=0.0)
    item = instrument(reference=10_000, last=2_500)
    start = math.log(2_500 / 10_000)
    assert math.log(walked(fair, item, 1) / 10_000) == pytest.approx(start)
    assert math.log(walked(fair, item, HOUR) / 10_000) == pytest.approx(start / 2)
    assert math.log(walked(fair, item, HOUR) / 10_000) == pytest.approx(start / 4)
    # The market's own price plays no part after the start.
    assert math.log(walked(fair, replace(item, last=99), HOUR) / 10_000) == pytest.approx(start / 8)


def test_values_wander_by_the_set_volatility_and_stay_near_the_reference() -> None:
    """Over an hour a value moves by about 1.5% of the price; after sixteen hours it is still
    within a few percent of its reference, where a walk with nothing pulling it back would be 6%
    off."""
    item = instrument(reference=10_000)
    hourly, daily = [], []
    for seed in range(30):
        fair = values(seed)
        walked(fair, item, 1)
        hourly.append(math.log(walked(fair, item, HOUR) / 10_000))
        daily.append(math.log(walked(fair, item, 15 * HOUR) / 10_000))
    spread = math.sqrt(sum(change**2 for change in hourly) / len(hourly))
    assert 0.011 < spread < 0.019
    rest = math.sqrt(sum(gap**2 for gap in daily) / len(daily))
    assert 0.018 < rest < 0.034, "2.5% at rest with these settings"
    assert max(abs(gap) for gap in daily) < 0.09
    assert min(daily) < 0 < max(daily)


def test_a_bot_sees_the_value_through_its_own_bias_and_the_market_before_there_is_one() -> None:
    fair = values(volatility=0.0)
    item = instrument(reference=10_000, bid=9_990, ask=10_030)
    assert View(fair, 0.002).of(item) == 10_010, "no value yet: the market's mid"
    assert View(None, 0.002).of(item) == 10_010, "no values at all: the market's mid"
    far = replace(item, bid=100, ask=50_000)
    assert View(None).of(far) == 10_000, "not the mid of a wide book: the reference"
    assert View(None, narrow_book_bp=20_000).of(far) == 25_050, "unless the setting allows it"
    fair.observe(view(replace(item, bid=None, ask=None)))
    assert View(fair, 0.002).of(item) == pytest.approx(10_020)
    assert View(fair, -0.002).of(item) == pytest.approx(9_980)
    assert View(fair).of(item) == pytest.approx(10_000)
