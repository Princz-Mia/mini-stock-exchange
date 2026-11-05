import asyncio
import json
import random
from uuid import uuid4

import httpx

from mse_simulator.client import TraderClient

TOKEN = "mse_bot_" + "A" * 43
ORDER = {"instrumentId": str(uuid4()), "side": "BUY", "quantity": 1}


def client(handler, sleeps: list[float]) -> tuple[TraderClient, httpx.AsyncClient]:
    async def sleep(seconds: float) -> None:
        sleeps.append(seconds)

    http = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    trader = TraderClient(
        http, "http://execution", uuid4(), TOKEN, asyncio.Semaphore(2), random.Random(7), sleep
    )
    return trader, http


def test_an_unresolved_command_is_retried_with_the_same_key_and_body_until_it_resolves() -> None:
    answers = [
        httpx.Response(503, headers={"Retry-After": "1"}),
        httpx.Response(504, json={"code": "COMMAND_OUTCOME_UNKNOWN"}),
        None,
        httpx.Response(429),
        httpx.Response(500, json={"code": "COMMAND_FAILED"}),
        httpx.Response(201, json={"orderId": "o-1", "state": "OPEN"}),
    ]
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        answer = answers.pop(0)
        if answer is None:
            raise httpx.ConnectError("refused", request=request)
        return answer

    sleeps: list[float] = []

    async def scenario():
        trader, http = client(handler, sleeps)
        async with http:
            return await trader.place(ORDER)

    answer = asyncio.run(scenario())
    assert answer.status == 201
    assert answer.body["orderId"] == "o-1"
    assert len(seen) == 6
    assert len({request.headers["Idempotency-Key"] for request in seen}) == 1
    assert len({request.content for request in seen}) == 1
    assert json.loads(seen[0].content) == ORDER
    assert all(request.headers["Authorization"] == f"Bearer {TOKEN}" for request in seen)
    assert len(sleeps) == 5
    assert sleeps[0] >= 1, "Retry-After is honoured"
    assert all(0 <= wait <= 5 for wait in sleeps)


def test_a_final_answer_is_not_retried_and_each_intent_gets_its_own_key() -> None:
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(422, json={"code": "INSUFFICIENT_CASH"})

    sleeps: list[float] = []

    async def scenario():
        trader, http = client(handler, sleeps)
        async with http:
            return [await trader.place(ORDER), await trader.place(ORDER)]

    first, second = asyncio.run(scenario())
    assert (first.status, first.code) == (422, "INSUFFICIENT_CASH")
    assert second.code == "INSUFFICIENT_CASH"
    assert sleeps == []
    assert seen[0].headers["Idempotency-Key"] != seen[1].headers["Idempotency-Key"]


def test_a_cancel_names_the_order_and_sends_no_body() -> None:
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200, json={"status": "CANCELED"})

    async def scenario():
        trader, http = client(handler, [])
        async with http:
            return await trader.cancel("order-7")

    assert asyncio.run(scenario()).status == 200
    assert seen[0].url.path == "/api/v1/orders/order-7/cancel"
    assert seen[0].content == b""
    assert "Idempotency-Key" in seen[0].headers
