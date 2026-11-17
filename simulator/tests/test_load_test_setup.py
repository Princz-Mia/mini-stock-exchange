import httpx
import pytest

from mse_simulator.loadtest import __main__ as loadtest
from mse_simulator.loadtest.__main__ import SYMBOLS, parse, set_up


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

    instruments = set_up(operator, 250, "a-trader-password")

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

    set_up(operator, 0, "a-trader-password", [("stress-0000", "p" * 24), ("stress-0001", "q" * 24)])

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
    monkeypatch.setattr(loadtest, "time", Clock())
    operator = RunOperator([503, 503])

    assert loadtest.stop_any_run(operator)
    assert operator.stop_requests == 3, "the stop is sent until it is taken"
    assert operator.status == "STOPPED"


def test_a_run_that_does_not_confirm_its_stop_keeps_the_checks_from_running(monkeypatch) -> None:
    monkeypatch.setattr(loadtest, "time", Clock())
    operator = RunOperator([], stops=False)

    # STOPPING is not stopped: the worker may still be trading.
    assert not loadtest.stop_any_run(operator)


def test_the_report_is_written_when_the_environment_cannot_be_read(monkeypatch) -> None:
    def broken(stack):
        raise RuntimeError("docker exec failed")

    monkeypatch.setattr(loadtest, "environment", broken)

    env = loadtest.read_environment(object())

    lines = "\n".join(loadtest.environment_lines(env))
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

    assert (
        "Generator: the simulator container on the same host." in loadtest.environment_lines(env)[2]
    )
    assert "Generator: this machine." in loadtest.environment_lines(env, "this machine")[2]
