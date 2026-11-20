import pytest

from mse_simulator.loadtest import diagnostics, ramp, stats

EDGES = ("0.05", "0.15", "0.5", "1.0", "+Inf")


def scrape(
    committed: dict[str, int], failed: dict[str, int] | None = None, refused: int = 0
) -> dict:
    """Cumulative latency buckets by outcome, as Execution's histogram publishes them, and the
    commands refused for load, split across its three counters."""
    lines = [
        f'mse_commands_refused_total{{command="place",reason="busy"}} {refused // 2}',
        f'mse_commands_refused_total{{command="place",reason="backlog"}} {refused // 4}',
        f'mse_commands_refused_total{{command="place",reason="invalid"}} {refused}',
        f'mse_commands_rate_limited_total{{account="bot"}} {refused - refused // 2 - refused // 4}',
    ]
    for outcome, counts in (("committed", committed), ("failed", failed or {})):
        total = 0
        for edge in EDGES:
            total += counts.get(edge, 0)
            lines.append(f'mse_commands_seconds_bucket{{outcome="{outcome}",le="{edge}"}} {total}')
    return stats.parse_prometheus("\n".join(lines))


ACTIVITY_BEFORE = {"placed": 1000, "canceled": 500, "rejected": 10, "errors": 0}
QUIET_QUEUE = [(float(at), 1.0) for at in range(0, 121, 5)]
QUIET_BACKLOG = [(float(at), 20.0) for at in range(0, 121, 5)]
# The trades feed peaks at 9 once; the market feed stays at 4.
QUIET_LAG = {
    "mse.trades.v1": [(float(at), 9.0 if at == 60 else 3.0) for at in range(0, 121, 5)],
    "mse.market.v1": [(float(at), 4.0) for at in range(0, 121, 5)],
}


def rising(start: float, end: float, seconds: int = 120) -> list[tuple[float, float]]:
    """A series that sits at start for the first third and at end for the last."""
    return [
        (float(at), start if at < seconds / 3 else end if at >= 2 * seconds / 3 else start)
        for at in range(0, seconds + 1, 5)
    ]


def measured(
    target: float = 500.0,
    after_committed: dict[str, int] | None = None,
    activity_after: dict[str, int] | None = None,
    probe: list | None = None,
    queue: list | None = None,
    failed: dict[str, int] | None = None,
    refused: int = 0,
    seconds: float = 120.0,
    unavailable: int = 0,
    backlog: list | None = None,
    lag: dict | None = None,
    live: dict | None = None,
) -> ramp.Step:
    sampled = diagnostics.Sampled()
    sampled.feed_lag = QUIET_LAG if lag is None else lag
    sampled.backlog = QUIET_BACKLOG if backlog is None else backlog
    sampled.feed_live = live or {}
    sampled.cpu = {"simulator": [30.0, 50.0], "execution-service": [80.0, 120.0]}
    return ramp.step(
        target,
        scrape({"0.05": 100}, refused=1_000),
        scrape(after_committed or {"0.05": 100 + 57_120, "0.15": 3_000}, failed, 1_000 + refused),
        ACTIVITY_BEFORE,
        activity_after or {"placed": 41_000, "canceled": 20_500, "rejected": 10, "errors": 0},
        seconds,
        probe if probe is not None else [("place", 0.01, 201), ("cancel", 0.01, 200)] * 60,
        queue if queue is not None else QUIET_QUEUE,
        sampled,
        unavailable,
    )


def test_a_step_is_measured_over_its_window() -> None:
    step = measured()

    # 40,000 placed and 20,000 canceled by the bots, plus 120 probe commands, in 120 s.
    assert step.offered == 60_120
    assert step.offered_rate == pytest.approx(501.0)
    assert step.admitted == 60_120 and step.probe == 120
    assert step.admitted_rate == pytest.approx(501.0)
    assert step.committed == 60_120
    assert step.within_p95 == pytest.approx(1.0)
    assert step.within_p99 == pytest.approx(1.0)
    assert step.unexpected == 0
    assert step.refused == 0
    assert step.lag_peak == 9.0
    assert step.backlog_peak == 20.0
    assert step.cpu["execution-service"] == (100.0, 120.0)
    # 40% of a CPU over the bots' 500 commands a second (the probe is not the simulator's).
    assert step.simulator_ms == pytest.approx(0.8)
    assert step.passed and step.missed() == []


@pytest.mark.parametrize(
    ("change", "missed"),
    [
        ({"after_committed": {"0.05": 100 + 50_000, "0.5": 8_000}}, "p95 within 150 ms"),
        ({"after_committed": {"0.05": 100 + 57_000, "1.0": 1_000}}, "p99 within 500 ms"),
        (
            {"queue": [(float(at), 1.0 if at < 85 else 40.0) for at in range(0, 121, 5)]},
            "no sustained queue growth",
        ),
        ({"probe": [("place", 0.01, 504)] * 120}, "refusals and failures below 0.1%"),
        ({"refused": 61}, "refusals and failures below 0.1%"),
        ({"after_committed": {"0.05": 100 + 50_000}}, "admitted at least 90% of the rate"),
        ({"backlog": rising(20.0, 5_000.0)}, "publication and the feeds kept up"),
        (
            {"lag": QUIET_LAG | {"mse.market.v1": rising(4.0, 5_000.0)}},
            "publication and the feeds kept up",
        ),
        ({"backlog": []}, "publication and the feeds kept up"),
        ({"lag": {}}, "publication and the feeds kept up"),
    ],
)
def test_each_budget_can_fail_a_step_alone(change, missed) -> None:
    step = measured(**change)

    assert not step.passed
    assert step.missed() == [missed]


def test_queue_growth_is_judged_over_thirds_of_a_short_step() -> None:
    # A one-minute step in which the queue grows throughout.
    step = measured(seconds=60.0, queue=[(float(at), 1.0 + at) for at in range(0, 61, 5)])

    assert step.missed() == ["no sustained queue growth"]


def test_commands_that_failed_after_admission_count_against_the_step() -> None:
    step = measured(failed={"0.05": 100})

    assert step.unexpected == 100
    assert "refusals and failures below 0.1%" in step.missed()


def test_the_bots_heartbeat_totals_do_not_decide_the_rate() -> None:
    # The bots report their totals every 5 s, so either end of a window may lag; Execution's own
    # count of admitted commands decides whether the rate was tested.
    step = measured(
        activity_after={"placed": 20_000, "canceled": 10_000, "rejected": 10, "errors": 0}
    )

    assert step.offered_rate < 0.9 * step.target
    assert step.passed


def test_each_probe_failure_is_counted_once() -> None:
    # Execution records the 500 under its failed outcome and the 503 among its refusals; the 504's
    # command went on and was recorded as committed, so only the probe saw it fail.
    step = measured(
        failed={"0.05": 1},
        refused=1,
        probe=[("place", 0.01, 503), ("place", 0.01, 504), ("place", 0.01, 500)],
    )

    assert step.unexpected == 2, "the failed command and the timeout"
    assert step.refused == 1


def test_probe_answers_from_a_stopped_writer_count_against_the_step() -> None:
    # Execution counts a stopped writer's 503 nowhere, and the bots retry it silently; only the
    # probe sees it.
    step = measured(unavailable=61, probe=[("place", 0.01, 503)] * 61)

    assert step.unexpected == 61
    assert step.missed() == ["refusals and failures below 0.1%"]


def test_commands_refused_for_load_count_against_the_step() -> None:
    # 60,120 admitted: 60 refusals are just under 0.1% of the attempts, 61 just over.
    assert measured(refused=60).passed
    step = measured(refused=61)

    assert step.refused == 61, "busy, backlog and rate-limited, not invalid"
    assert step.missed() == ["refusals and failures below 0.1%"]


def test_the_highest_passing_rate_is_the_last_before_the_first_failure() -> None:
    good = [measured(target) for target in (150.0, 200.0, 300.0)]
    bad = measured(500.0, queue=[(float(at), 1.0 if at < 60 else 40.0) for at in range(0, 121, 5)])
    # Admitted at 750 a second, so it meets every budget at its rate.
    lucky = measured(750.0, after_committed={"0.05": 100 + 90_000})
    assert lucky.passed

    assert ramp.highest_passing(good + [bad, lucky]).target == 300.0, (
        "a pass above a failure is not trusted"
    )
    assert ramp.highest_passing([bad, lucky]) is None
    assert ramp.highest_passing([]) is None


def test_the_report_names_the_highest_rate_and_each_step() -> None:
    steps = [measured(150.0), measured(200.0, probe=[("place", 0.01, 504)] * 120)]

    text = "\n".join(ramp.report(steps, [300.0, 500.0]))

    assert "**Highest rate that met every budget: 150 commands a second.**" in text
    assert "| 150/s |" in text and "| yes |" in text
    assert "no: refusals and failures below 0.1%" in text
    assert "Not run, after a step missed a budget: 300/s, 500/s." in text
    assert "Could not be measured" not in text
    assert "| 150/s | 100 / 120 | n/a | 40 / 50 | n/a | n/a | 0.8 ms |" in text


def test_downstream_growth_is_allowed_two_seconds_of_the_rate_and_at_least_200() -> None:
    # At 500 a second the allowance is 1,000 records between the first and last thirds.
    assert measured(backlog=rising(20.0, 1_020.0)).passed
    assert not measured(backlog=rising(20.0, 1_021.0)).passed
    lag = QUIET_LAG | {"mse.trades.v1": rising(3.0, 1_004.0)}
    assert not measured(lag=lag).passed, "any topic's lag counts"
    # At 50 a second, two seconds would be 100 records; the floor of 200 applies.
    low = {"0.05": 100 + 5_712, "0.15": 300}
    activity = {"placed": 5_000, "canceled": 2_500, "rejected": 10, "errors": 0}
    assert measured(50.0, low, activity, backlog=rising(20.0, 220.0)).passed
    assert not measured(50.0, low, activity, backlog=rising(20.0, 221.0)).passed


def test_a_momentary_downstream_peak_is_not_growth() -> None:
    spike = [(float(at), 50_000.0 if at == 60 else 20.0) for at in range(0, 121, 5)]

    step = measured(backlog=spike)

    assert step.passed
    assert step.backlog_peak == 50_000.0


def test_the_report_says_what_fell_behind() -> None:
    behind = measured(200.0, lag=QUIET_LAG | {"mse.market.v1": rising(4.0, 900.0)})

    text = "\n".join(ramp.report([measured(150.0), behind], []))

    assert "| 20 | 9 | yes |" in text, "backlog peak and feed lag peak"
    assert "Publication or a feed fell behind: 200/s: grew: mse.market.v1 4 to 900." in text
    assert "not sampled" not in text


def test_a_feed_that_stopped_fails_the_step_though_its_lag_stayed_flat() -> None:
    stopped = {"mse.trades.v1": [(float(at), 1.0 if at < 70 else 0.0) for at in range(0, 121, 5)]}

    step = measured(live=stopped)

    assert step.missed() == ["publication and the feeds kept up"]
    text = "\n".join(ramp.report([step], []))
    assert "Publication or a feed fell behind: 500/s: not live: mse.trades.v1." in text
    unsampled = "\n".join(ramp.report([measured(backlog=[])], []))
    assert "fell behind" not in unsampled
    assert "Publication and the feeds were not sampled enough to judge: 500/s" in unsampled


def test_the_report_names_a_step_that_could_not_be_measured() -> None:
    text = "\n".join(ramp.report([measured(150.0)], [500.0], "300/s (RuntimeError: timed out)"))

    assert "**Highest rate that met every budget: 150 commands a second.**" in text
    assert "Could not be measured, which ended the ramp: 300/s (RuntimeError: timed out)." in text


def test_rates_are_read_rising_and_within_what_a_run_may_ask() -> None:
    assert ramp.rates("150, 200,300") == [150.0, 200.0, 300.0]
    for text in (
        "",
        "a,b",
        "0,100",
        "100,2000.01",
        "200,150",
        "100,100",
        "nan",
        "100,nan",
        "inf",
        "0.001,1",
    ):
        with pytest.raises(ValueError):
            ramp.rates(text)
