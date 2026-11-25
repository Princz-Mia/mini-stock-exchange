import json
from importlib import metadata

import numpy as np
import pytest

from mse_simulator import manifest as manifest_module
from mse_simulator.manifest import REVISION_VARIABLE, manifest, version
from mse_simulator.settings import Settings

PACKAGE = metadata.version("mse-simulator")


def settings(**overrides) -> Settings:
    values = {
        "execution_url": "http://execution:8080",
        "market_data_url": "http://market:8080",
        "simulator_token": "a-control-token-that-must-not-leak",
        "worker_id": "worker-1",
    }
    return Settings(**(values | overrides))


@pytest.mark.parametrize(
    ("revision", "expected"),
    [
        ("27a70d6c1e2f", "27a70d6c1e2f"),
        ("27a70d6c1e2f.dirty", "27a70d6c1e2f.dirty"),
        ("27a70d6c1e2f0123456789abcdef0123456789ab", "27a70d6c1e2f0123456789abcdef0123456789ab"),
        (None, "unknown"),
        ("", "unknown"),
        ("main", "unknown"),
        ("27a70d6c1e2f dirty", "unknown"),
        ("27A70D6", "unknown"),
    ],
)
def test_the_version_names_the_commit_or_says_it_is_unknown(
    monkeypatch, revision, expected
) -> None:
    if revision is None:
        monkeypatch.delenv(REVISION_VARIABLE, raising=False)
    else:
        monkeypatch.setenv(REVISION_VARIABLE, revision)
    assert version() == f"{PACKAGE}+{expected}"


def test_the_manifest_holds_the_strategy_defaults_and_nothing_else(monkeypatch) -> None:
    monkeypatch.setenv(REVISION_VARIABLE, "27a70d6c1e2f")
    reported = manifest(settings())

    assert reported["version"] == f"{PACKAGE}+27a70d6c1e2f"
    assert reported["runtime"]["numpy"] == np.__version__
    assert set(reported["runtime"]) == {"python", "numpy"}
    assert reported["strategies"] == {
        "MARKET_MAKER": {
            "halfSpreadBp": 10.0,
            "skewBp": 10.0,
            "requoteBp": 5.0,
            "quoteLifetimeSeconds": 30.0,
        },
        "NOISE": {
            "spreadBp": 30.0,
            "valuePull": 0.25,
            "maxOpenOrders": 5,
            "quoteLifetimeSeconds": 10.0,
        },
        "MOMENTUM": {
            "shortPolls": 10,
            "longPolls": 60,
            "threshold": 0.002,
            "minTicks": 2.0,
            "follow": 0.25,
            "reachBp": 20.0,
            "cooldownSeconds": 15.0,
            "orderLifetimeSeconds": 10.0,
        },
    }
    assert reported["defaults"] == {
        "fairValueHalfLifeHours": 4.0,
        "fairValueVolatility": 0.015,
        "viewSpreadBp": 20.0,
        "marketPollSeconds": 1.0,
        "maxDataAgeSeconds": 2.0,
        "maxInFlight": 32,
        "maxReadsInFlight": 64,
    }
    assert set(reported) == {"version", "runtime", "strategies", "defaults"}


def test_a_changed_setting_shows_in_the_manifest() -> None:
    changed = manifest(
        settings(maker_half_spread_bp=4, momentum_threshold=0.004, fair_value_volatility=0.03)
    )
    assert changed["strategies"]["MARKET_MAKER"]["halfSpreadBp"] == 4
    assert changed["strategies"]["MOMENTUM"]["threshold"] == 0.004
    assert changed["defaults"]["fairValueVolatility"] == 0.03
    assert changed != manifest(settings())


def test_no_secret_address_or_name_leaves_with_it() -> None:
    text = json.dumps(manifest(settings()))
    for private in (
        "a-control-token-that-must-not-leak",
        "execution:8080",
        "market:8080",
        "worker-1",
    ):
        assert private not in text
    # Execution refuses a manifest over 8 KiB; this one stays far below.
    assert len(text.encode()) < 2_048


def test_a_revision_that_is_not_a_commit_is_warned_about(monkeypatch, caplog) -> None:
    monkeypatch.setenv(REVISION_VARIABLE, "feature/secret-branch")
    with caplog.at_level("WARNING", logger=manifest_module.__name__):
        assert version().endswith("+unknown")
    assert REVISION_VARIABLE in caplog.text
    assert "secret-branch" not in caplog.text, "the value itself is not logged"
