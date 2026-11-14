import random

import pytest

from mse_simulator.population import STRATEGIES, split_text, strategy_split


def test_the_demo_population_splits_like_the_operator_screen() -> None:
    assert strategy_split(20) == {"MARKET_MAKER": 4, "NOISE": 12, "MOMENTUM": 4}
    assert strategy_split(1000) == {"MARKET_MAKER": 200, "NOISE": 600, "MOMENTUM": 200}


def test_small_populations_keep_a_market_maker_and_a_noise_bot() -> None:
    assert strategy_split(0) == {"MARKET_MAKER": 0, "NOISE": 0, "MOMENTUM": 0}
    assert strategy_split(1) == {"MARKET_MAKER": 1, "NOISE": 0, "MOMENTUM": 0}
    assert strategy_split(2) == {"MARKET_MAKER": 1, "NOISE": 1, "MOMENTUM": 0}
    assert strategy_split(4) == {"MARKET_MAKER": 1, "NOISE": 3, "MOMENTUM": 0}
    assert strategy_split(9) == {"MARKET_MAKER": 1, "NOISE": 7, "MOMENTUM": 1}


def test_every_population_is_split_whole_with_fifths_rounded_down() -> None:
    generator = random.Random(20261114)
    for bots in [*range(0, 30), *(generator.randrange(30, 5_000) for _ in range(300))]:
        split = strategy_split(bots)
        assert tuple(split) == STRATEGIES, "every strategy, in provisioning order"
        assert sum(split.values()) == bots
        assert split["MOMENTUM"] == bots // 5
        assert split["MARKET_MAKER"] == max(bots // 5, min(bots, 1))
        if bots >= 2:
            assert split["NOISE"] >= 1, bots


def test_a_negative_population_is_refused() -> None:
    with pytest.raises(ValueError):
        strategy_split(-1)


def test_the_split_reads_as_text() -> None:
    assert split_text(strategy_split(20)) == "4 market makers, 12 noise, 4 momentum"
    assert split_text(strategy_split(2)) == "1 market maker, 1 noise"
    assert split_text(strategy_split(0)) == "no bots"
