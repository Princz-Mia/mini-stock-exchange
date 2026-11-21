import subprocess
from pathlib import Path

from mse_simulator.loadtest import __main__ as loadtest
from mse_simulator.loadtest import stack
from mse_simulator.loadtest.stack import Ports, Stack


def commands(monkeypatch) -> list[list[str]]:
    """Every command the stack runs, answered as a success."""
    ran: list[list[str]] = []

    def run(command, **kwargs):
        ran.append(command)
        return subprocess.CompletedProcess(command, 0, stdout="", stderr="")

    monkeypatch.setattr(stack.subprocess, "run", run)
    return ran


def settings(stack_: Stack) -> dict[str, str]:
    lines = stack_.env_file.read_text(encoding="utf-8").splitlines()
    return dict(line.split("=", 1) for line in lines)


def test_a_stack_is_its_own_project_on_its_own_ports(tmp_path, monkeypatch) -> None:
    ran = commands(monkeypatch)
    project = Stack(tmp_path, "mse-other", "other", Ports(1001, 1002, 1003, 1004), ("a.yaml",))

    assert project.env_file == tmp_path / "runtime" / "other" / "stack.env"
    values = settings(project)
    assert {
        key: values[key] for key in ("WEB_HOST_PORT", "POSTGRES_HOST_PORT", "KAFKA_HOST_PORT")
    } == {
        "WEB_HOST_PORT": "1001",
        "POSTGRES_HOST_PORT": "1002",
        "KAFKA_HOST_PORT": "1003",
    }
    assert values["FLINK_UI_HOST_PORT"] == "1004"
    # Fresh passwords for every role, none of them shared.
    passwords = [value for key, value in values.items() if "PASSWORD" in key or "TOKEN" in key]
    assert len(passwords) == len(set(passwords)) >= 9
    assert project.web_url == "http://127.0.0.1:1001"

    project.compose("ps")
    assert ran[-1] == [
        "docker",
        "compose",
        "-p",
        "mse-other",
        "--env-file",
        str(project.env_file),
        "-f",
        "compose.yaml",
        "-f",
        "a.yaml",
        "--profile",
        "full",
        "ps",
    ]


def test_the_load_test_keeps_its_project_ports_and_overlay(tmp_path, monkeypatch) -> None:
    ran = commands(monkeypatch)
    load = loadtest.load_stack(tmp_path)

    assert load.env_file == tmp_path / "runtime" / "load-test" / "load.env"
    ports = ("WEB_HOST_PORT", "POSTGRES_HOST_PORT", "KAFKA_HOST_PORT", "FLINK_UI_HOST_PORT")
    assert [settings(load)[key] for key in ports] == ["38080", "35432", "39092", "38081"]
    load.compose("ps")
    assert ran[-1][2:4] == ["-p", "mse-load"]
    overlay = str(Path("infra", "load-test", "compose.diagnostics.yaml"))
    assert ran[-1][6:10] == ["-f", "compose.yaml", "-f", overlay]


def test_a_run_is_started_with_its_split_and_the_load_tests_limits() -> None:
    sent: list[tuple[int, str, dict]] = []

    class Operator:
        def expect(self, status: int, path: str, body: dict | None = None) -> dict:
            sent.append((status, path, body))
            return {"runId": "run-1"}

    split = {"MARKET_MAKER": 4, "NOISE": 27, "MOMENTUM": 9}

    assert stack.start_run(Operator(), split, 7, "20.00", 9) == {"runId": "run-1"}
    assert sent == [
        (
            201,
            "/api/v1/admin/bot-runs",
            {
                "marketMakers": 4,
                "momentum": 9,
                "noise": 27,
                "seed": 7,
                "commandsPerSecond": "20.00",
                "maxOrderQuantity": 10,
                "maxPosition": 200,
                "durationMinutes": 9,
            },
        )
    ]
