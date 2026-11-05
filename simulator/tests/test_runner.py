import asyncio
from uuid import UUID, uuid4

import httpx
import pytest

from mse_simulator.credentials import load_tokens
from mse_simulator.market import fetch
from mse_simulator.noise import Holdings
from mse_simulator.runner import Runner
from mse_simulator.settings import Settings

INSTRUMENT = str(uuid4())


def token(seed: str) -> str:
    return "mse_bot_" + (seed * 43)[:43]


def write_tokens(directory, count: int) -> dict:
    directory.mkdir()
    accounts = {uuid4(): token(chr(ord("a") + i)) for i in range(count)}
    for account, value in accounts.items():
        (directory / f"{account}.token").write_text(value + "\n", encoding="ascii")
    return accounts


def test_tokens_load_by_account_and_anything_else_is_skipped(tmp_path) -> None:
    directory = tmp_path / "tokens"
    accounts = write_tokens(directory, 2)
    (directory / "notes.txt").write_text("x")
    (directory / "not-a-uuid.token").write_text(token("z"))
    (directory / f"{uuid4()}.token").write_text("garbage")
    (directory / ".abc.partial").write_text(token("q"))
    assert load_tokens(directory) == accounts
    assert load_tokens(tmp_path / "missing") == {}


class Exchange:
    """A fake exchange: an open, live market with one instrument, and every bot well funded."""

    def __init__(self) -> None:
        self.placed: list[httpx.Request] = []
        self.release = asyncio.Event()
        self.live = "LIVE"

    async def handle(self, request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path == "/api/v1/market":
            return httpx.Response(200, json={"state": "OPEN"})
        if path == "/api/v1/instruments":
            return httpx.Response(
                200,
                json={
                    "feed": {"state": self.live, "lag": 0},
                    "instruments": [
                        {
                            "instrumentId": INSTRUMENT,
                            "symbol": "NOVA",
                            "tick": "0.01",
                            "referencePrice": "100.00",
                            "lastPrice": None,
                            "bestBid": {"price": "99.50", "quantity": 3, "orderCount": 1},
                            "bestAsk": None,
                            "maxPrice": "1000000.00",
                            "minQuantity": 1,
                            "maxQuantity": 1000000,
                        }
                    ],
                },
            )
        if path == "/api/v1/portfolio":
            return httpx.Response(
                200,
                json={
                    "cash": {"available": "100000.00"},
                    "holdings": [{"instrumentId": INSTRUMENT, "available": 1000}],
                },
            )
        if path == "/api/v1/orders" and request.method == "GET":
            return httpx.Response(200, json={"orders": [], "nextCursor": None})
        if path == "/api/v1/orders":
            self.placed.append(request)
            await self.release.wait()
            return httpx.Response(201, json={"orderId": str(uuid4()), "state": "OPEN"})
        return httpx.Response(404)


def settings(directory, **overrides) -> Settings:
    values = {
        "execution_url": "http://execution:8080",
        "market_data_url": "http://market:8080",
        "token_directory": directory,
        "rate": 10,
    }
    return Settings(**(values | overrides))


def test_each_bot_trades_with_its_own_token_and_never_has_two_commands_in_flight(tmp_path) -> None:
    accounts = write_tokens(tmp_path / "tokens", 2)
    exchange = Exchange()

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            runner = Runner(settings(tmp_path / "tokens"), http)
            runner.load_bots()
            await runner.refresh_market()
            assert runner.tick() and runner.tick()
            assert not runner.tick(), "both bots still have a command in flight"
            while len(exchange.placed) < 2:
                await asyncio.sleep(0.01)
            exchange.release.set()
            await asyncio.wait_for(runner.drain(), 2)
            assert runner.telemetry.counts["placed"] == 2
            assert runner.tick()
            await asyncio.wait_for(runner.drain(), 2)

    asyncio.run(scenario())
    tokens = {request.headers["Authorization"] for request in exchange.placed[:2]}
    assert tokens == {f"Bearer {value}" for value in accounts.values()}
    assert len(exchange.placed) == 3


def test_market_data_that_is_not_live_pauses_new_orders(tmp_path) -> None:
    write_tokens(tmp_path / "tokens", 1)
    exchange = Exchange()
    exchange.live = "CATCHING_UP"
    exchange.release.set()

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            runner = Runner(settings(tmp_path / "tokens"), http)
            runner.load_bots()
            await runner.refresh_market()
            runner.tick()
            await asyncio.wait_for(runner.drain(), 2)
            assert runner.telemetry.counts["idle"] == 1

    asyncio.run(scenario())
    assert exchange.placed == []


@pytest.mark.parametrize(("now", "expected"), [(1.0, True), (2.0, True), (2.5, False)])
def test_data_older_than_the_budget_pauses_new_orders(now, expected) -> None:
    exchange = Exchange()

    async def scenario():
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            return await fetch(http, "http://execution", "http://market", 0.0)

    market = asyncio.run(scenario())
    assert market.instruments[0].bid == 9_950
    assert market.tradable(now, 2.0) is expected


def test_a_zero_rate_keeps_the_worker_idle_until_stopped(tmp_path) -> None:
    write_tokens(tmp_path / "tokens", 1)
    exchange = Exchange()

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            stop = asyncio.Event()
            runner = Runner(settings(tmp_path / "tokens", rate=0), http)
            task = asyncio.create_task(runner.run(stop))
            await asyncio.sleep(0.05)
            stop.set()
            await asyncio.wait_for(task, 1)

    asyncio.run(scenario())
    assert exchange.placed == []


def test_each_bot_draws_from_its_own_generator(tmp_path) -> None:
    write_tokens(tmp_path / "tokens", 2)

    async def scenario():
        async with httpx.AsyncClient(transport=httpx.MockTransport(Exchange().handle)) as http:
            runner = Runner(settings(tmp_path / "tokens"), http)
            runner.load_bots()
            await runner.refresh_market()
            holdings = Holdings(cash=10**9, shares={UUID(INSTRUMENT): 10**6})
            return [
                [bot.strategy.decide(runner._market, True, holdings, [], 0.0) for _ in range(20)]
                for bot in runner._bots.values()
            ]

    first, second = asyncio.run(scenario())
    assert first != second, "two bots with one run seed must not trade in lockstep"
