import copy
import random
import subprocess

import httpx
import pytest

from mse_simulator.loadtest import __main__ as loadtest
from mse_simulator.loadtest.manifests import differences, report, revision_label

COMMIT = "27a70d6c1e2f9b8a7c6d5e4f3a2b1c0d9e8f7a6b"


def manifest(**changes) -> dict:
    """A manifest as Execution serves it back, with the simulator's own setting names."""
    base = {
        "version": "0.1.0+27a70d6c1e2f",
        "workerId": "simulator-1",
        "reportedAt": "2026-11-21T10:00:00Z",
        "runtime": {"python": "3.14.0", "numpy": "2.3.1"},
        "strategies": {
            "MARKET_MAKER": {"halfSpreadTicks": 2, "skewTicks": 1, "quoteLifetimeSeconds": 30.0},
            "NOISE": {"spreadTicks": 5, "maxOpenOrders": 5},
            "MOMENTUM": {"threshold": 0.001, "shortPolls": 5},
        },
        "defaults": {"maxInFlight": 32, "marketPollSeconds": 1.0},
        "reports": 1,
    }
    return base | changes


def test_the_revision_is_the_commit_marked_dirty_only_for_tracked_changes() -> None:
    assert revision_label("27a70d6c1e2f", "") == "27a70d6c1e2f"
    assert revision_label("27a70d6c1e2f", " M simulator/pyproject.toml\n") == "27a70d6c1e2f.dirty"
    assert revision_label("", "") == ""


def test_the_revision_is_read_from_the_checkout(tmp_path) -> None:
    assert loadtest.revision(tmp_path) == "", "outside a checkout the version stays unknown"

    def git(*args: str) -> str:
        return subprocess.run(
            # Signing and hooks from the machine's own git setup have no say here.
            [
                "git",
                *("-c", "user.name=t", "-c", "user.email=t@example.invalid"),
                *("-c", "commit.gpgsign=false", "-c", "core.hooksPath="),
                *args,
            ],
            cwd=tmp_path,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()

    git("init", "-q")
    (tmp_path / "tracked.txt").write_text("one\n", encoding="utf-8")
    git("add", "tracked.txt")
    git("commit", "-q", "-m", "first")
    commit = git("rev-parse", "--short=12", "HEAD")

    assert loadtest.revision(tmp_path) == commit
    # An untracked file is not built into anything, as the compatibility gate has it.
    (tmp_path / "scratch.txt").write_text("x\n", encoding="utf-8")
    assert loadtest.revision(tmp_path) == commit
    (tmp_path / "tracked.txt").write_text("two\n", encoding="utf-8")
    assert loadtest.revision(tmp_path) == f"{commit}.dirty"


def test_who_reported_a_manifest_and_when_is_no_difference() -> None:
    other = manifest(workerId="simulator-2", reportedAt="2026-11-21T11:00:00Z", reports=3)

    assert differences(manifest(), other) == []


def test_settings_differ_as_values_not_as_text() -> None:
    first = manifest()
    other = copy.deepcopy(first)
    # Python wrote 30.0 and it may come back as 30: the same number.
    other["strategies"]["MARKET_MAKER"]["quoteLifetimeSeconds"] = 30
    assert differences(first, other) == []
    # true is not 1, though Python compares them equal.
    flag = copy.deepcopy(first)
    flag["defaults"]["paused"] = 1
    flagged = copy.deepcopy(first)
    flagged["defaults"]["paused"] = True
    assert differences(flag, flagged) == ["defaults.paused true (was 1)"]

    other["defaults"]["maxInFlight"] = 64
    del other["strategies"]["NOISE"]["spreadTicks"]
    other["runtime"]["numpy"] = "2.4.0"
    other["defaults"]["maxReadsInFlight"] = 8
    other["version"] = "0.1.0+0123456789ab"
    assert differences(first, other) == [
        "NOISE.spreadTicks absent (was 5)",
        "defaults.maxInFlight 64 (was 32)",
        "defaults.maxReadsInFlight 8 (new)",
        "runtime.numpy 2.4.0 (was 2.3.1)",
        "version 0.1.0+0123456789ab (was 0.1.0+27a70d6c1e2f)",
    ]


def test_every_changed_setting_is_named_once_and_nothing_else() -> None:
    random_ = random.Random(20261121)
    for _ in range(500):
        first = manifest()
        other = copy.deepcopy(first)
        # Every setting of the copy by its dotted name, and where it lives.
        places = {"version": (other, "version")}
        places |= {f"runtime.{name}": (other["runtime"], name) for name in other["runtime"]}
        for strategy, settings in other["strategies"].items():
            places |= {f"{strategy}.{name}": (settings, name) for name in settings}
        places |= {f"defaults.{name}": (other["defaults"], name) for name in other["defaults"]}
        expected = set()
        if random_.random() < 0.2:
            # A strategy gone altogether: each of its settings is absent.
            gone = random_.choice(list(other["strategies"]))
            del other["strategies"][gone]
            expected |= {key for key in places if key.startswith(f"{gone}.")}
            places = {key: place for key, place in places.items() if key not in expected}
        for key in random_.sample(sorted(places), random_.randint(0, 6)):
            holder, name = places[key]
            value = holder[name]
            number = isinstance(value, int | float) and not isinstance(value, bool)
            change = random_.choice(
                ["other", "remove", *(["retyped", "boolean"] if number else [])]
            )
            if change == "other":
                holder[name] = f"{value}-other" if isinstance(value, str) else value + 1
            elif change == "remove":
                del holder[name]
            elif change == "retyped":
                # The same number written the other way: 30 as 30.0, 30.0 as 30.
                holder[name] = (
                    float(value)
                    if isinstance(value, int)
                    else (int(value) if value.is_integer() else value)
                )
            else:
                # A boolean is never the number it equals in Python.
                holder[name] = value != 0
            if change != "retyped":
                expected.add(key)
        for _ in range(random_.randint(0, 2)):
            group = random_.choice(["runtime", "defaults", *other["strategies"]])
            holder = other["strategies"][group] if group in other["strategies"] else other[group]
            name = f"extra{random_.randint(0, 9)}"
            holder[name] = random_.choice([7, 0.25, True, "fast"])
            expected.add(f"{group}.{name}")

        named = [item.split(" ")[0] for item in differences(first, other)]

        assert sorted(named) == sorted(expected)
        assert named == sorted(named), "in name order"


def test_one_run_names_its_simulator_in_full() -> None:
    lines = report([("the run", "run-1", manifest())], COMMIT)

    assert lines[:3] == ["## Simulator", "", "The run's worker reported:"]
    assert "- Version: 0.1.0+27a70d6c1e2f." in lines
    assert "- Runtime: numpy 2.3.1, python 3.14.0." in lines
    strategies = [line for line in lines if line.startswith(("- Market", "- Noise", "- Momentum"))]
    assert strategies == [
        "- Market makers: halfSpreadTicks 2, quoteLifetimeSeconds 30.0, skewTicks 1.",
        "- Noise: maxOpenOrders 5, spreadTicks 5.",
        "- Momentum: shortPolls 5, threshold 0.001.",
    ]
    assert "- Defaults: marketPollSeconds 1.0, maxInFlight 32." in lines
    assert not any("does not name" in line for line in lines), "the version names the commit"
    assert "| the run | run-1 | as above |" in lines


def test_runs_on_different_settings_are_flagged_and_their_differences_named() -> None:
    other = manifest(defaults={"maxInFlight": 64, "marketPollSeconds": 1.0})
    lines = report(
        [("100/s", "run-1", manifest()), ("200/s", "run-2", manifest()), ("300/s", "run-3", other)],
        COMMIT,
    )

    assert lines[2].startswith("**The runs did not all run the same simulator code and settings")
    assert "| 200/s | run-2 | as above |" in lines
    assert "| 300/s | run-3 | differs: defaults.maxInFlight 64 (was 32) |" in lines


def test_a_run_whose_simulator_changed_midway_is_flagged() -> None:
    lines = report(
        [("100/s", "run-1", manifest()), ("200/s", "run-2", manifest(reports=2))], COMMIT
    )

    assert lines[2].startswith("**The runs did not all run the same")
    assert (
        "| 200/s | run-2 | changed during the run: 2 different reports, the first shown |" in lines
    )


def test_runs_with_nothing_reported_or_read_still_appear() -> None:
    lines = report(
        [
            ("100/s", "run-1", None),
            ("200/s", "run-2", "HTTPStatusError: 503"),
            ("300/s", "run-3", manifest()),
        ],
        COMMIT,
    )

    # The run that reported is the one shown; what the others ran is not claimed to be the same.
    assert lines[2] == (
        "1 of 3 runs reported, all the same version and settings; what the others ran is not known:"
    )
    assert "| 100/s | run-1 | not reported |" in lines
    assert "| 200/s | run-2 | not read (HTTPStatusError: 503) |" in lines
    assert "| 300/s | run-3 | as above |" in lines

    nothing = report([("the run", "run-1", None)], COMMIT)
    assert nothing[2].startswith("No run's worker reported its manifest")
    assert "| the run | run-1 | not reported |" in nothing


def test_a_version_that_does_not_name_the_commit_is_said() -> None:
    def warned(version: str, commit: str) -> bool:
        lines = report([("the run", "run-1", manifest(version=version))], commit)
        return any("does not name this report's commit" in line for line in lines)

    assert not warned("0.1.0+27a70d6c1e2f", COMMIT)
    # The report counts untracked files as changes and the image doesn't: only the commit counts.
    assert not warned("0.1.0+27a70d6c1e2f", f"{COMMIT} (uncommitted changes)")
    assert not warned("0.1.0+27a70d6c1e2f.dirty", f"{COMMIT} (uncommitted changes)")
    assert warned("0.1.0+unknown", COMMIT)
    assert warned("0.1.0+0123456789ab", COMMIT)
    assert warned("0.1.0", COMMIT)
    # A report that could not read its own commit has nothing to compare with.
    assert not warned("0.1.0+27a70d6c1e2f", "not read")
    assert not warned("0.1.0+unknown", "")


def test_every_cell_stays_on_its_line_of_the_table() -> None:
    lines = report(
        [
            ("a | b", "run-1", "ConnectError: refused\nretry later | maybe"),
            ("200/s", "run-2", None),
        ],
        COMMIT,
    )

    rows = [line for line in lines if line.startswith("| ")]
    assert rows[2:] == [
        r"| a \| b | run-1 | not read (ConnectError: refused retry later \| maybe) |",
        "| 200/s | run-2 | not reported |",
    ]


def operator(handler) -> object:
    class Operator:
        http = httpx.Client(base_url="http://exchange", transport=httpx.MockTransport(handler))

    return Operator()


def test_a_manifest_is_read_by_its_run_or_why_it_could_not_be() -> None:
    def answers(request: httpx.Request) -> httpx.Response:
        run = request.url.path.rsplit("/", 1)[1]
        if run == "run-1":
            return httpx.Response(200, json={"run": {"manifest": manifest()}, "figures": None})
        if run == "run-2":
            return httpx.Response(200, json={"run": {"manifest": None}, "figures": None})
        if run == "run-3":
            return httpx.Response(200, json={"runs": []})
        return httpx.Response(404, json={"code": "NOT_FOUND"})

    reader = operator(answers)

    assert loadtest.read_manifest(reader, "run-1") == manifest()
    assert loadtest.read_manifest(reader, "run-2") is None
    assert loadtest.read_manifest(reader, "run-3").startswith("KeyError")
    # A status error says its status, on one line, not httpx's two-line message.
    assert loadtest.read_manifest(reader, "run-4") == "HTTP 404"


class Clock:
    """Stands in for the time module: sleeping only moves the clock on."""

    def __init__(self) -> None:
        self.now = 0.0

    def monotonic(self) -> float:
        return self.now

    def sleep(self, seconds: float) -> None:
        self.now += seconds


def test_a_step_records_its_run_before_anything_can_break_it(monkeypatch) -> None:
    def broken(operator):
        raise RuntimeError("the exchange is not answering")

    monkeypatch.setattr(loadtest, "begin_run", lambda *args: {"runId": "run-9"})
    monkeypatch.setattr(loadtest, "activity", broken)
    monkeypatch.setattr(loadtest, "time", Clock())
    runs: list[tuple[float, str]] = []

    with pytest.raises(RuntimeError, match="timed out waiting for the run to be RUNNING"):
        loadtest.ramp_step(None, None, None, "id-ALPHA", None, 100.0, 5, 0, runs)

    assert runs == [(100.0, "run-9")]
