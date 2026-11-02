import asyncio
import time

import httpx
import numpy as np
import pytest
from pydantic import ValidationError

from mse_simulator.__main__ import run
from mse_simulator.healthcheck import is_healthy
from mse_simulator.settings import Settings


def settings(**overrides: object) -> Settings:
    values = {"execution_url": "http://execution:8080", "market_data_url": "http://market:8080"}
    return Settings(**(values | overrides))


def test_settings_reject_unknown_fields_and_unbounded_concurrency() -> None:
    with pytest.raises(ValidationError):
        settings(account_balance=1)
    with pytest.raises(ValidationError):
        settings(max_in_flight=0)


def test_seeded_generators_repeat_decisions() -> None:
    first = np.random.default_rng(42).integers(1, 10_000, size=5)
    second = np.random.default_rng(42).integers(1, 10_000, size=5)
    assert first.tolist() == second.tolist()


def test_httpx_async_client_speaks_through_a_transport() -> None:
    async def call() -> int:
        transport = httpx.MockTransport(lambda _request: httpx.Response(200, json={"status": "UP"}))
        async with httpx.AsyncClient(transport=transport) as client:
            return (await client.get("http://market/actuator/health")).status_code

    assert asyncio.run(call()) == 200


def test_worker_writes_a_heartbeat_and_stops_on_request(tmp_path, monkeypatch) -> None:
    heartbeat = tmp_path / "heartbeat"
    monkeypatch.setattr("mse_simulator.__main__.HEARTBEAT_FILE", heartbeat)

    async def scenario() -> None:
        stop = asyncio.Event()
        worker = asyncio.create_task(run(settings(heartbeat_seconds=0.01), stop))
        await asyncio.sleep(0.05)
        stop.set()
        await asyncio.wait_for(worker, timeout=1)

    asyncio.run(scenario())
    assert is_healthy(heartbeat)
    assert not is_healthy(heartbeat, now=time.time() + 60)
    assert not is_healthy(tmp_path / "missing")
