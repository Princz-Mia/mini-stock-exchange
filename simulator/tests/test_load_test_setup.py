import asyncio
import contextlib
import time

import httpx
import pytest

from mse_simulator.loadtest import __main__ as loadtest
from mse_simulator.loadtest import stack, stats
from mse_simulator.loadtest.__main__ import parse
from mse_simulator.loadtest.stack import SYMBOLS, set_up
from mse_simulator.population import strategy_split


class FakeOperator:
    """Records the setup commands the load test sends and answers each as the exchange would."""

    def __init__(self) -> None:
        self.sent: list[tuple[str, dict | None]] = []

    def expect(self, status: int, path: str, body: dict | None = None) -> dict:
        self.sent.append((path, body))
        if path == "/api/v1/admin/instruments":
            return {"instrumentId": f"id-{body['symbol']}"}
        return {}


def test_bots_are_tagged_in_the_split_their_run_uses() -> None:
    operator = FakeOperator()

    instruments = set_up(operator, strategy_split(250), "a-trader-password")

    assert len(instruments) == len(SYMBOLS)
    batches = [
        (body["strategy"], body["count"])
        for path, body in operator.sent
        if path == "/api/v1/admin/bots"
    ]
    assert batches == [("MARKET_MAKER", 50), ("NOISE", 100), ("NOISE", 50), ("MOMENTUM", 50)]
    assert operator.sent[-1] == ("/api/v1/admin/market/open", None)
    traders = [body for path, body in operator.sent if path == "/api/v1/admin/traders"]
    assert [(t["username"], t["password"], t["cash"]) for t in traders] == [
        ("trader", "a-trader-password", "1000000.00")
    ]
    assert not any(path == "/api/v1/admin/accounts" for path, _ in operator.sent)


def test_further_trader_logins_are_added_before_the_market_opens() -> None:
    # The exchange adds traders only while the market is in setup.
    operator = FakeOperator()

    set_up(
        operator,
        strategy_split(0),
        "a-trader-password",
        [("stress-0000", "p" * 24), ("stress-0001", "q" * 24)],
    )

    paths = [path for path, _ in operator.sent]
    assert paths[-1] == "/api/v1/admin/market/open"
    logins = [
        (body["username"], body["password"], body["cash"], "holdings" in body)
        for path, body in operator.sent
        if path == "/api/v1/admin/traders"
    ]
    assert logins[1:] == [
        ("stress-0000", "p" * 24, "1000000.00", False),
        ("stress-0001", "q" * 24, "1000000.00", False),
    ]
    assert "/api/v1/admin/bots" not in paths


@pytest.mark.parametrize(
    "argv",
    [
        ["--ramp", "100,nan"],
        ["--ramp", "200,100"],
        ["--ramp", "--stress"],
        ["--ramp", "--step-duration", "59"],
        ["--ramp", "--step-warmup", "-1"],
        ["--stress", "--stress-duration", "0"],
        ["--stress", "--stress-warmup", "-1"],
    ],
)
def test_options_that_cannot_measure_are_refused_before_the_stack_starts(
    argv, tmp_path, monkeypatch
) -> None:
    (tmp_path / "compose.yaml").write_text("services: {}")
    monkeypatch.chdir(tmp_path)

    with pytest.raises(SystemExit):
        parse(argv)


def test_the_ramp_and_stress_rates_are_read(tmp_path, monkeypatch) -> None:
    (tmp_path / "compose.yaml").write_text("services: {}")
    monkeypatch.chdir(tmp_path)

    assert parse(["--ramp"]).ramp[:4] == [100.0, 125.0, 150.0, 175.0]
    assert parse(["--stress"]).stress[-1] == 2000.0
    assert parse(["--ramp", "--step-duration", "60"]).step_duration == 60
    stress = parse(["--stress", "100,200"])
    assert stress.stress == [100.0, 200.0] and stress.ramp is None
    assert parse([]).ramp is None and parse([]).stress is None


class Clock:
    """Stands in for the time module: sleeping only moves the clock on."""

    def __init__(self) -> None:
        self.now = 0.0

    def monotonic(self) -> float:
        return self.now

    def sleep(self, seconds: float) -> None:
        self.now += seconds


class Answer:
    def __init__(self, status: int, body: dict | None = None) -> None:
        self.status_code = status
        self.body = body or {}

    def json(self) -> dict:
        return self.body


class RunOperator:
    """An operator whose latest run answers the stop as scripted and stops a poll later."""

    def __init__(self, stop_answers: list[int], stops: bool = True) -> None:
        self.stop_answers = stop_answers
        self.stops = stops
        self.status = "RUNNING"
        self.stop_requests = 0
        self.http = self

    def get(self, path: str) -> Answer:
        assert path.startswith("/api/v1/admin/bot-runs")
        run = {"runId": "run-1", "status": self.status}
        if self.status == "STOPPING" and self.stops:
            self.status = "STOPPED"
        return Answer(200, {"runs": [run]})

    def command(self, path: str) -> Answer:
        assert path == "/api/v1/admin/bot-runs/run-1/stop"
        self.stop_requests += 1
        status = self.stop_answers.pop(0) if self.stop_answers else 200
        if status == 200:
            self.status = "STOPPING"
        return Answer(status)


def test_a_run_left_going_by_a_broken_step_is_stopped_and_confirmed(monkeypatch) -> None:
    monkeypatch.setattr(stack, "time", Clock())
    operator = RunOperator([503, 503])

    assert stack.stop_any_run(operator)
    assert operator.stop_requests == 3, "the stop is sent until it is taken"
    assert operator.status == "STOPPED"


def test_a_run_that_does_not_confirm_its_stop_keeps_the_checks_from_running(monkeypatch) -> None:
    monkeypatch.setattr(stack, "time", Clock())
    operator = RunOperator([], stops=False)

    # STOPPING is not stopped: the worker may still be trading.
    assert not stack.stop_any_run(operator)


def test_the_report_is_written_when_the_environment_cannot_be_read(monkeypatch) -> None:
    def broken(stack):
        raise RuntimeError("docker exec failed")

    monkeypatch.setattr(stack, "environment", broken)

    env = stack.read_environment(object())

    lines = "\n".join(stack.environment_lines(env))
    assert "Commit: not read." in lines


def test_a_503_is_unavailable_unless_it_is_a_busy_refusal() -> None:
    def answer(status: int, body: dict | None = None) -> httpx.Response:
        return httpx.Response(status, json=body) if body is not None else httpx.Response(status)

    assert loadtest.unavailable(answer(503, {"code": "EXCHANGE_BUSY"})) == 0
    assert loadtest.unavailable(answer(503, {"code": "TRADING_UNAVAILABLE"})) == 1
    assert loadtest.unavailable(answer(503)) == 1, "no body to say it was busy"
    assert loadtest.unavailable(answer(201, {"orderId": "o"})) == 0


def test_the_report_says_when_execution_was_not_restarted() -> None:
    assert loadtest.restart_line(6.4) == "- Restarting Execution, its reconciliation included: 6 s."
    assert "not done" in loadtest.restart_line(float("nan"))


def test_the_admission_limits_are_read_as_execution_reports_them() -> None:
    scrape = stats.parse_prometheus(
        'mse_outbox_admission_limit{limit="events"} 500000.0\n'
        'mse_outbox_admission_limit{limit="bytes"} 1.073741824E9\n'
        "mse_outbox_pending 12.0\n"
    )

    limits = stack.admission_limits(scrape)

    assert limits == (500_000, 1_073_741_824)
    assert stack.admission_line(limits) == (
        "- Publication backlog limit, beyond which new orders are refused: 500,000 events or "
        "1,024 MiB."
    )
    events_only = stats.parse_prometheus('mse_outbox_admission_limit{limit="events"} 3\n')
    assert stack.admission_limits(events_only) is None
    assert stack.admission_line(None) == "- Publication backlog limit: not read."


def test_downstream_samples_are_timed_from_the_start_and_a_failed_one_is_skipped() -> None:
    sampled = loadtest.diagnostics.Sampled()
    calls = []

    def sample():
        calls.append(len(calls))
        if len(calls) == 2:
            raise RuntimeError("docker exec failed")
        return (
            stats.parse_prometheus(f'mse_feed_lag{{topic="mse.market.v1"}} {len(calls)}\n'),
            stats.parse_prometheus(f"mse_outbox_pending {10 * len(calls)}\n"),
        )

    async def run() -> None:
        task = asyncio.create_task(loadtest.sample_downstream(sample, sampled, every=0.05))
        await asyncio.sleep(0.18)
        task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await task

    asyncio.run(run())

    assert len(calls) >= 3
    assert [value for _, value in sampled.backlog][:2] == [10, 30], "the second sample failed"
    times = [at for at, _ in sampled.feed_lag["mse.market.v1"]]
    assert times[0] == pytest.approx(0.0, abs=0.05)
    assert times[1] == pytest.approx(0.10, abs=0.05)


def test_a_run_rate_is_written_as_the_api_takes_it_or_refused_before_starting(
    tmp_path, monkeypatch
) -> None:
    (tmp_path / "compose.yaml").write_text("services: {}")
    monkeypatch.chdir(tmp_path)
    assert loadtest.run_rate("225") == "225.00"
    assert loadtest.run_rate("12.5") == "12.50"
    assert loadtest.run_rate("100.00") == "100.00"
    assert parse(["--rate", "225"]).rate == "225.00"
    for text in ("abc", "0.001", "0", "-5", "2000.01", "nan", "inf", "1.234"):
        with pytest.raises(ValueError):
            loadtest.run_rate(text)
    with pytest.raises(SystemExit):
        parse(["--rate", "2500"])


def test_processor_readings_and_cpu_throttling_are_taken_while_a_rate_runs() -> None:
    sampled = loadtest.diagnostics.Sampled()
    readings = iter(["mse-load-postgres-1|40.00%\n", "mse-load-postgres-1|60.00%\n"])
    counters = iter([{"postgres": (100, 0, 0)}, {"postgres": (200, 20, 500_000)}])

    read_at: list[float] = []

    def count():
        read_at.append(time.monotonic())  # the clock asyncio's loop keeps
        return next(counters)

    async def run() -> tuple[dict, float]:
        began = asyncio.get_running_loop().time()
        task = asyncio.create_task(
            loadtest.sample_cpu(lambda: next(readings, ""), sampled, every=0.05)
        )
        held = await loadtest.throttled_between(count, 0.1, 0.2)
        task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await task
        return held, began

    held, began = asyncio.run(run())

    assert [value for _, value in sampled.cpu_at["postgres"]][:2] == [40.0, 60.0]
    times = [at for at, _ in sampled.cpu_at["postgres"]][:2]
    assert times[0] == pytest.approx(0.0, abs=0.05) and times[1] == pytest.approx(0.05, abs=0.05)
    assert read_at[0] - began >= 0.09, "the first counters wait for the window's start"
    assert read_at[1] - read_at[0] == pytest.approx(0.1, abs=0.05)
    assert held["postgres"][0] == pytest.approx(0.2), "20 of 100 periods"
    assert held["postgres"][1] == pytest.approx(5_000, rel=0.5), "500 ms in about 0.1 s"


def test_cpu_counters_skip_a_service_that_cannot_be_read() -> None:
    class FakeStack:
        def exec(self, service, *command):
            if service == "kafka":
                raise RuntimeError("no such container")
            if service == "web":
                return "usage_usec 5\n"
            return "nr_periods 10\nnr_throttled 1\nthrottled_usec 300\n"

    counters = loadtest.cpu_stats(FakeStack())

    assert set(counters) == {"execution-service", "postgres", "market-data-service"}
    assert counters["postgres"] == (10, 1, 300)


def test_each_report_names_its_own_generator() -> None:
    env = {
        key: "x"
        for key in (
            "host",
            "docker",
            "cpus",
            "memory_gib",
            "commit",
            "java",
            "jvm_args",
            "postgres",
        )
    } | {"limits": {}}

    assert "Generator: the simulator container on the same host." in stack.environment_lines(env)[2]
    assert "Generator: this machine." in stack.environment_lines(env, "this machine")[2]
