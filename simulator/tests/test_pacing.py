import random

import pytest

from mse_simulator.pacing import STEPS_PER_COMMAND, Pacer


def steps(pacer: Pacer) -> int:
    taken = 0
    while pacer.take():
        taken += 1
    return taken


def test_a_run_starts_with_one_command_at_once() -> None:
    pacer = Pacer(100.0, 0.0)

    assert steps(pacer) == 1


def test_credit_accrues_at_the_rate_up_to_one_second_of_it() -> None:
    pacer = Pacer(10.0, 0.0)
    steps(pacer)

    pacer.accrue(0.5)
    assert steps(pacer) == 5
    pacer.accrue(100.0)
    assert steps(pacer) == 11, "idle time saves up one second's worth, plus the one in hand"


def test_a_step_that_sends_nothing_gives_its_command_back() -> None:
    pacer = Pacer(10.0, 0.0)
    steps(pacer)
    pacer.accrue(0.1)

    assert pacer.take()
    assert not pacer.take()
    pacer.refund()
    assert pacer.take(), "the refunded command is spent again"


def test_refunds_cannot_repeat_steps_beyond_their_own_budget() -> None:
    pacer = Pacer(10.0, 0.0)
    while pacer.take():
        pacer.refund()
    pacer.accrue(1.0)
    taken = 0
    while pacer.take():
        taken += 1
        pacer.refund()

    assert taken == 10 * STEPS_PER_COMMAND, "a second of steps, not more"


def test_a_clock_that_goes_back_neither_adds_nor_takes_away() -> None:
    pacer = Pacer(10.0, 5.0)
    steps(pacer)
    pacer.accrue(4.0)

    assert not pacer.take()
    pacer.accrue(4.25)
    assert pacer.take(), "a quarter of a second after the step back earns its commands"


def test_a_rate_must_be_positive() -> None:
    with pytest.raises(ValueError):
        Pacer(0.0, 0.0)


def test_commands_sent_match_the_rate_whatever_share_of_steps_waits() -> None:
    """Seeded runs: strategies that wait on up to half their steps, polled every 50 ms for ten
    simulated minutes. The commands sent keep the rate, short only by the credit still in hand."""
    rng = random.Random(20261108)
    for _ in range(60):
        rate = rng.choice([1.0, 4.0, 25.0, 100.0, 250.0])
        waits = rng.uniform(0.0, 0.5)
        pacer = Pacer(rate, 0.0)
        sent = 0
        for tick in range(1, 12_001):
            pacer.accrue(tick * 0.05)
            while pacer.take():
                if rng.random() < waits:
                    pacer.refund()
                else:
                    sent += 1
        expected = rate * 600 + 1
        assert expected * 0.99 - (rate + 1) <= sent <= expected, (rate, waits, sent)
