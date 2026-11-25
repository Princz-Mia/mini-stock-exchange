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


def values(seed: int = 7, half_life: float = 4.0, volatility: float = 0.015) -> FairValues:
    return FairValues(seed, half_life, volatility, poll_seconds=1.0)


def walked(fair: FairValues, item, polls: int) -> float:
    for _ in range(polls):
        fair.observe(view(item))
    return fair.of(item.instrument_id)


def test_a_value_starts_where_the_market_stands_and_moves_only_with_open_live_polls() -> None:
    fair = values()
    item = instrument(reference=10_000, bid=9_400, ask=9_600, last=9_000)
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
    fair.observe(view(replace(item, bid=None, ask=None)))
    assert View(fair, 0.002).of(item) == pytest.approx(10_020)
    assert View(fair, -0.002).of(item) == pytest.approx(9_980)
    assert View(fair).of(item) == pytest.approx(10_000)
