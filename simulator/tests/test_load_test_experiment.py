import random
from datetime import UTC, datetime
from decimal import ROUND_HALF_UP, Decimal

import pytest

from mse_simulator.loadtest import experiment, stack
from mse_simulator.loadtest.experiment import (
    EVIDENCE,
    SHARED,
    Options,
    Outcome,
    figures,
    order,
    parse,
    population,
    render,
    split,
)

D = Decimal


class Clock:
    """Stands in for the time module: sleeping only moves the clock on."""

    def __init__(self) -> None:
        self.now = 0.0

    def monotonic(self) -> float:
        return self.now

    def sleep(self, seconds: float) -> None:
        self.now += seconds


def test_a_share_gives_its_market_makers_and_the_rest_three_noise_to_one_momentum() -> None:
    assert split(40, D("0.1")) == {"MARKET_MAKER": 4, "NOISE": 27, "MOMENTUM": 9}
    assert split(40, D("0.5")) == {"MARKET_MAKER": 20, "NOISE": 15, "MOMENTUM": 5}
    # Half a bot rounds up, whatever binary floating point would make of it.
    assert split(40, D("0.0625"))["MARKET_MAKER"] == 3
    assert split(10, D("0.35"))["MARKET_MAKER"] == 4
    assert split(40, D("0")) == {"MARKET_MAKER": 0, "NOISE": 30, "MOMENTUM": 10}
    assert split(40, D("1")) == {"MARKET_MAKER": 40, "NOISE": 0, "MOMENTUM": 0}


def test_every_split_has_all_its_bots_and_the_market_makers_asked_for() -> None:
    random_ = random.Random(20261121)
    for _ in range(500):
        bots = random_.randint(1, 10_000)
        share = D(random_.randint(0, 1000)) / 1000
        each = split(bots, share)
        makers = (D(bots) * share).quantize(D(1), rounding=ROUND_HALF_UP)
        assert sum(each.values()) == bots
        assert each["MARKET_MAKER"] == makers
        assert each["MOMENTUM"] == (bots - makers) // 4


def test_enough_bots_are_tagged_for_every_share() -> None:
    shares = (D("0.1"), D("0.5"), D("0.3"))
    tagged = population(40, shares)

    assert tagged == {"MARKET_MAKER": 20, "NOISE": 27, "MOMENTUM": 9}
    for share in shares:
        assert all(split(40, share)[strategy] <= tagged[strategy] for strategy in tagged)


def test_runs_go_seed_by_seed_with_the_shares_turned_for_each() -> None:
    a, b, c = D("0.1"), D("0.3"), D("0.5")
    assert order((a, b, c), (7, 8, 9, 10)) == [
        (7, a), (7, b), (7, c),
        (8, b), (8, c), (8, a),
        (9, c), (9, a), (9, b),
        (10, a), (10, b), (10, c),
    ]  # fmt: skip
    # Every share runs once a seed, and each takes its turn at going first.
    random_ = random.Random(7)
    for _ in range(200):
        shares = tuple(D(n) / 100 for n in random_.sample(range(101), random_.randint(1, 6)))
        seeds = tuple(random_.sample(range(1000), random_.randint(1, 6)))
        ran = order(shares, seeds)
        assert len(ran) == len(shares) * len(seeds)
        for index, seed in enumerate(seeds):
            turn = ran[index * len(shares) : (index + 1) * len(shares)]
            assert {each for _, each in turn} == set(shares) and {s for s, _ in turn} == {seed}
            assert turn[0][1] == shares[index % len(shares)]


def test_options_that_cannot_run_are_refused_before_a_stack_starts(
    tmp_path, monkeypatch, capsys
) -> None:
    (tmp_path / "compose.yaml").write_text("services: {}\n", encoding="utf-8")
    monkeypatch.chdir(tmp_path)

    options = parse([])
    assert (options.bots, options.rate, options.duration) == (40, "20.00", 300)
    assert options.shares == (D("0.1"), D("0.3"), D("0.5"))
    assert options.seeds == (1, 2)
    assert parse(["--rate", "12.5", "--shares", "0, 1", "--seeds", "3"]).rate == "12.50"

    for argv, said in [
        (["--rate", "81"], "more than 40 bots may send"),
        (["--duration", "179"], "at least 180 s"),
        (["--shares", "0.1,1.5"], "from 0 to 1"),
        (["--shares", "0.1,0.1"], "twice"),
        (["--shares", "0.1,0.11"], "both give 4 market makers"),
        (["--shares", ""], "lists nothing"),
        (["--seeds", "1,-2"], "must not be negative"),
        (["--seeds", "one"], "not a list of numbers"),
        (["--bots", "0"], "at least 1"),
    ]:
        with pytest.raises(SystemExit):
            parse(argv)
        assert said in capsys.readouterr().err, argv

    monkeypatch.chdir(tmp_path / "..")
    with pytest.raises(SystemExit):
        parse([])


def detail(run_id: str = "run-1", figures_: bool = True) -> dict:
    """A finished run as Execution serves it: 300 s from start to the end of its window."""
    return {
        "run": {
            "runId": run_id,
            "status": "STOPPED",
            "startedAt": "2026-11-21T10:00:00Z",
            "stoppedAt": "2026-11-21T10:05:00Z",
            "config": {
                "instruments": [
                    {"instrumentId": "id-ALPHA", "symbol": "ALPHA"},
                    {"instrumentId": "id-BRAVO", "symbol": "BRAVO"},
                ]
            },
            # As this API serves a window: its end is when the worker confirmed the stop.
            "window": {
                "startSequence": "40",
                "stopSequence": "880",
                "endSequence": "900",
                "endedBy": "WORKER",
            },
        },
        "figures": (
            {
                "instruments": [
                    {"symbol": "ALPHA", "trades": 30, "volume": 150},
                    {"symbol": "BRAVO", "trades": 10, "volume": 50},
                ],
                "strategies": [
                    {
                        "strategy": "MARKET_MAKER",
                        "bots": 4,
                        "placements": 2000,
                        "cancels": 1600,
                        "refusals": [{"code": "INSUFFICIENT_CASH", "commands": 3}],
                        "placedQuantity": 400,
                        "filledQuantity": 100,
                        "pnl": "12.50",
                    },
                    {
                        "strategy": "NOISE",
                        "bots": 27,
                        "placements": 1500,
                        "cancels": 900,
                        "refusals": [],
                        "placedQuantity": 0,
                        "filledQuantity": 0,
                        "pnl": "-7.25",
                    },
                ],
            }
            if figures_
            else None
        ),
    }


def quality(spread: str | None, volatility: str | None, state: str = "LIVE") -> dict:
    return {"averageSpread": spread, "volatility": volatility, "feed": {"state": state, "lag": 0}}


def outcome(index: int = 1, share: str = "0.1", seed: int = 1, **fields) -> Outcome:
    return Outcome(index, seed, D(share), split(40, D(share)), **fields)


def test_a_run_is_started_traded_for_the_duration_stopped_and_its_figures_kept(monkeypatch) -> None:
    clock = Clock()
    monkeypatch.setattr(experiment, "time", clock)
    monkeypatch.setattr(stack, "time", clock)
    states = iter(["STARTING", "RUNNING", "RUNNING"])
    sent: list[tuple] = []
    stopped_at: list[float] = []

    class Operator:
        def expect(self, status, path, body=None):
            sent.append((status, path))
            stopped_at.append(clock.now)
            return {}

    def latest(operator):
        if stopped_at:
            return {"status": "STOPPED", "activity": {"placed": 9}}
        return {
            "status": next(states, "RUNNING"),
            "activity": {"placed": 0 if clock.now < 10 else 5},
        }

    def start_run(operator, *asked):
        sent.append(asked)
        return {"runId": "run-1"}

    answers = iter([detail(figures_=False), detail()])
    monkeypatch.setattr(experiment, "start_run", start_run)
    monkeypatch.setattr(experiment, "activity", latest)
    monkeypatch.setattr(experiment, "run_detail", lambda operator, run_id: next(answers))
    combination = outcome(share="0.5", seed=3)

    experiment.run_one(Operator(), combination, Options(root=None, duration=200), 13)

    # Its own split and seed, the experiment's rate, and an end of its own as a safety net.
    assert sent[0] == ({"MARKET_MAKER": 20, "NOISE": 15, "MOMENTUM": 5}, 3, "20.00", 13)
    assert sent[1] == (200, "/api/v1/admin/bot-runs/run-1/stop")
    # Timed from when its bots first traded, not from when it was started.
    trading = 10.0
    assert stopped_at[0] >= trading + 200
    assert combination.run_id == "run-1"
    assert combination.detail["figures"] is not None
    assert combination.complete


def test_a_run_keeps_its_id_when_it_breaks_and_the_next_one_still_runs(monkeypatch) -> None:
    calls: list[int] = []

    def run_one(operator, each, options, minutes):
        calls.append(each.index)
        each.run_id = f"run-{each.index}"
        if each.index == 2:
            raise RuntimeError("timed out waiting for the bots to trade")
        each.detail = detail(each.run_id)

    stops: list[bool] = [True]
    monkeypatch.setattr(experiment, "run_one", run_one)
    monkeypatch.setattr(experiment, "stop_any_run", lambda operator: stops[0])
    outcomes = [outcome(index) for index in (1, 2, 3)]

    experiment.run_all(None, outcomes, Options(root=None), 7)

    assert calls == [1, 2, 3]
    assert outcomes[1].run_id == "run-2"
    assert outcomes[1].problem == "RuntimeError: timed out waiting for the bots to trade"
    assert [each.complete for each in outcomes] == [True, False, True]

    # A run that can't be confirmed stopped ends the experiment: the next start would be refused.
    stops[0] = False
    calls.clear()
    outcomes = [outcome(index) for index in (1, 2, 3)]
    experiment.run_all(None, outcomes, Options(root=None), 7)
    assert calls == [1, 2]
    assert outcomes[2].run_id is None and outcomes[2].problem is None


def test_market_quality_is_read_once_market_data_has_caught_up(monkeypatch) -> None:
    monkeypatch.setattr(stack, "time", Clock())
    asked: list[str] = []
    answers = {
        "id-ALPHA": iter([quality("0.0400", None, "CATCHING_UP"), quality("0.0500", "0.0100")]),
        "id-BRAVO": iter([]),
    }

    class Response:
        def __init__(self, body):
            self.body = body

        def raise_for_status(self):
            pass

        def json(self):
            return self.body

    class Http:
        def get(self, path):
            asked.append(path)
            instrument = path.split("/")[4]
            # BRAVO never catches up.
            return Response(next(answers[instrument], quality(None, None, "CATCHING_UP")))

    class Operator:
        http = Http()

    combination = outcome(detail=detail())
    experiment.read_quality(Operator(), combination)

    assert asked[0] == "/api/v1/instruments/id-ALPHA/market-quality?fromSequence=40&toSequence=900"
    assert combination.quality["ALPHA"] == quality("0.0500", "0.0100")
    assert (
        combination.quality["BRAVO"] == "timed out waiting for Market Data to catch up with run 1"
    )


def test_a_runs_figures_come_from_its_window_and_the_instruments_with_values() -> None:
    combination = outcome(detail=detail())
    combination.quality = {"ALPHA": quality("0.0400", "0.0100"), "BRAVO": quality("0.0600", None)}

    each = figures(combination)

    assert each.seconds == 300
    assert each.achieved_rate == pytest.approx((2000 + 1600 + 1500 + 900) / 300)
    assert (each.trades, each.volume) == (40, 200)
    assert (each.spread, each.spread_instruments) == (D("0.05"), 2)
    assert (each.volatility, each.volatility_instruments) == (D("0.01"), 1)
    assert each.fill == {"MARKET_MAKER": D("0.25"), "NOISE": None}
    assert each.pnl == {"MARKET_MAKER": D("12.50"), "NOISE": D("-7.25")}
    # Shared over the strategy's bots, whose number differs with the share.
    assert each.pnl_per_bot("MARKET_MAKER") == D("3.125")
    assert each.pnl_per_bot("MOMENTUM") is None
    assert each.refused == 3
    assert figures(outcome(detail=detail(figures_=False))) is None
    assert figures(outcome(detail=detail(), problem="RuntimeError: stuck")) is None
    # Measured to when its worker confirmed the stop.
    shorter = detail()
    shorter["run"]["stoppedAt"] = "2026-11-21T10:02:30Z"
    assert figures(outcome(detail=shorter)).seconds == 150
    assert figures(outcome(detail=shorter)).achieved_rate == pytest.approx(6000 / 150)
    # A run whose stop was never confirmed has no end to measure to.
    unconfirmed = detail()
    unconfirmed["run"]["stoppedAt"] = None
    assert figures(outcome(detail=unconfirmed)) is None


def test_the_report_is_labelled_and_lists_every_run_in_order() -> None:
    options = Options(root=None, shares=(D("0.1"), D("0.5")), seeds=(1, 2))
    runs = []
    for index, (seed, share, spread, pnl) in enumerate(
        [
            (1, "0.1", "0.0400", "10.00"),
            (1, "0.5", "0.0200", "4.00"),
            (2, "0.5", "0.0300", "-2.00"),
        ],
        start=1,
    ):
        found = detail(f"run-{index}")
        found["figures"]["strategies"][0]["pnl"] = pnl
        runs.append(outcome(index, share, seed, run_id=f"run-{index}", detail=found))
        runs[-1].quality = {"ALPHA": quality(spread, "0.0100"), "BRAVO": quality(spread, "0.0100")}
    runs.append(outcome(4, "0.1", 2, problem="HTTP 503 | busy"))

    text = render(
        options,
        runs,
        datetime(2026, 11, 21, 10, tzinfo=UTC),
        ["## Simulator", ""],
        ["## Environment", ""],
    )
    lines = text.splitlines()

    assert lines[0] == "# Market-maker share experiment: 2026-11-21 10:00 UTC"
    assert EVIDENCE in lines and SHARED in lines
    first = "| 1 | 1 | 10% (4 of 40) | 5 min 0 s | 20.00/s | 40 | 200 | 0.0400 (2 of 2) "
    assert f"{first}| 1.0000% (2 of 2) |" in lines
    # A run that didn't complete keeps its place and says why, on its own line of the table.
    assert "| 4 | 2 | 10% (4 of 40) | HTTP 503 \\| busy | | | | | |" in lines
    assert "| 2 | 25.0% | +4.00 (+1.00 a bot) | -7.25 (-0.27 a bot) | — | 3 |" in lines
    # By share: the mean, with the range only where the runs differ.
    assert "| 10% | 1 | 40 | 0.0400 | 1.0000% | +2.50 |" in lines
    assert (
        "| 50% | 2 | 40 | 0.0250 (0.0200 to 0.0300) | 1.0000% | +0.25 (-0.50 to +1.00) |" in lines
    )
    # The simulator and the environment close it, as given.
    assert text.endswith("\n## Simulator\n\n## Environment\n")
