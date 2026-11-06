import asyncio
import json
from datetime import UTC, datetime
from uuid import UUID, uuid4

import httpx
import pytest

from mse_simulator.control import Assignment
from mse_simulator.credentials import load_token
from mse_simulator.market import fetch
from mse_simulator.noise import Holdings
from mse_simulator.runner import Runner
from mse_simulator.settings import Settings

INSTRUMENT = str(uuid4())
OTHER = str(uuid4())
CONTROL = "test-control-token"


def token(seed: str) -> str:
    return "mse_bot_" + (seed * 43)[:43]


def write_tokens(directory, count: int) -> dict:
    directory.mkdir()
    accounts = {uuid4(): token(chr(ord("a") + i)) for i in range(count)}
    for account, value in accounts.items():
        (directory / f"{account}.token").write_text(value + "\n", encoding="ascii")
    return accounts


def test_a_token_is_read_only_by_its_own_file_name(tmp_path) -> None:
    directory = tmp_path / "tokens"
    accounts = write_tokens(directory, 1)
    account, value = next(iter(accounts.items()))
    assert load_token(directory, f"{account}.token") == value
    outside = uuid4()
    (tmp_path / f"{outside}.token").write_text(token("z"))
    assert load_token(directory, f"../{outside}.token") is None
    assert load_token(directory, f"tokens/{account}.token") is None
    assert load_token(directory, str(directory / f"{account}.token")) is None
    (directory / "not-a-uuid.token").write_text(token("y"))
    assert load_token(directory, "not-a-uuid.token") is None
    garbage = uuid4()
    (directory / f"{garbage}.token").write_text("garbage")
    assert load_token(directory, f"{garbage}.token") is None
    assert load_token(directory, f"{uuid4()}.token") is None


def run_body(accounts: dict, strategies: list[str], **overrides) -> dict:
    body = {
        "runId": str(uuid4()),
        "desiredState": "RUNNING",
        "seed": "7",
        "commandsPerSecond": "50.00",
        "maxOrderQuantity": 5,
        "maxPosition": 100,
        "endsAt": None,
        "leaseSeconds": 1,
        "instruments": [{"instrumentId": INSTRUMENT, "symbol": "NOVA"}],
        "bots": [
            {"accountId": str(account), "strategy": strategy, "credentialRef": f"{account}.token"}
            for account, strategy in zip(accounts, strategies, strict=True)
        ],
        "worker": {"workerId": None, "leaseExpiresAt": None},
        "totals": {"placed": 0, "canceled": 0, "rejected": 0, "errors": 0},
    }
    return body | overrides


def quote(instrument: str) -> dict:
    return {
        "instrumentId": instrument,
        "symbol": "NOVA" if instrument == INSTRUMENT else "ZETA",
        "tick": "0.01",
        "referencePrice": "100.00",
        "lastPrice": None,
        "bestBid": {"price": "99.50", "quantity": 3, "orderCount": 1},
        "bestAsk": None,
        "maxPrice": "1000000.00",
        "minQuantity": 1,
        "maxQuantity": 1000000,
    }


class Exchange:
    """A fake exchange: an open, live market with two instruments, every bot well funded, and the
    control plane. Heartbeats answer with ``desired`` until ``refuse`` names a refusal code."""

    def __init__(self) -> None:
        self.run: dict | None = None
        self.placed: list[httpx.Request] = []
        self.canceled: list[str] = []
        self.beats: list[dict] = []
        self.release = asyncio.Event()
        self.release.set()
        self.live = "LIVE"
        self.desired = "RUNNING"
        self.refuse: str | None = None
        self.active: dict[str, list[dict]] = {}
        self.uncancelable: set[str] = set()

    def open_order(self, bot_token: str, instrument: str = INSTRUMENT) -> str:
        order_id = str(uuid4())
        self.active.setdefault(bot_token, []).append(
            {
                "orderId": order_id,
                "instrumentId": instrument,
                "side": "BUY",
                "workingQuantity": 1,
                "createdAt": datetime.now(UTC).isoformat(),
            }
        )
        return order_id

    async def handle(self, request: httpx.Request) -> httpx.Response:
        path = request.url.path
        bearer = request.headers.get("Authorization", "").removeprefix("Bearer ")
        if path.startswith("/internal/"):
            assert bearer == CONTROL, "control routes take only the control token"
            if path == "/internal/simulator/assignment":
                return httpx.Response(200, json={"run": self.run})
            beat = json.loads(request.content)
            self.beats.append(beat)
            if self.refuse is not None:
                return httpx.Response(409, json={"code": self.refuse})
            return httpx.Response(
                200,
                json={
                    "runId": beat["runId"],
                    "desiredState": self.desired,
                    "leaseExpiresAt": "2026-11-06T09:00:00Z",
                    "stopped": beat["stopped"],
                },
            )
        assert bearer != CONTROL, "the control token never trades"
        if path == "/api/v1/market":
            return httpx.Response(200, json={"state": "OPEN"})
        if path == "/api/v1/instruments":
            return httpx.Response(
                200,
                json={
                    "feed": {"state": self.live, "lag": 0},
                    "instruments": [quote(INSTRUMENT), quote(OTHER)],
                },
            )
        if path == "/api/v1/portfolio":
            return httpx.Response(
                200,
                json={
                    "cash": {"available": "100000.00"},
                    "holdings": [
                        {"instrumentId": held, "available": 1000, "total": 1000}
                        for held in (INSTRUMENT, OTHER)
                    ],
                },
            )
        if path == "/api/v1/orders" and request.method == "GET":
            return httpx.Response(
                200, json={"orders": self.active.get(bearer, []), "nextCursor": None}
            )
        if path == "/api/v1/orders":
            self.placed.append(request)
            await self.release.wait()
            return httpx.Response(201, json={"orderId": str(uuid4()), "state": "OPEN"})
        if path.endswith("/cancel"):
            order_id = path.split("/")[-2]
            if order_id in self.uncancelable:
                return httpx.Response(409, json={"code": "ORDER_NOT_ACTIVE"})
            self.canceled.append(order_id)
            self.active[bearer] = [
                o for o in self.active.get(bearer, []) if o["orderId"] != order_id
            ]
            return httpx.Response(200, json={"orderId": order_id, "state": "CANCELED"})
        return httpx.Response(404)


def settings(directory, **overrides) -> Settings:
    values = {
        "execution_url": "http://execution:8080",
        "market_data_url": "http://market:8080",
        "token_directory": directory,
        "simulator_token": CONTROL,
        "worker_id": "worker-1",
        "control_poll_seconds": 0.01,
    }
    return Settings(**(values | overrides))


def placed_instruments(exchange: Exchange) -> set[str]:
    return {json.loads(request.content)["instrumentId"] for request in exchange.placed}


async def follow_until(runner: Runner, exchange: Exchange, condition, then=None) -> None:
    """Follows the exchange's run until the condition holds, then applies ``then``."""
    task = asyncio.create_task(runner.follow(Assignment.parse(exchange.run)))
    for _ in range(300):
        if condition():
            break
        await asyncio.sleep(0.01)
    assert condition(), "the condition never held"
    if then is not None:
        then()
    await asyncio.wait_for(task, 5)


def test_each_bot_trades_with_its_own_token_and_never_has_two_commands_in_flight(tmp_path) -> None:
    accounts = write_tokens(tmp_path / "tokens", 2)
    exchange = Exchange()
    exchange.release.clear()
    exchange.run = run_body(accounts, ["NOISE", "NOISE"])

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            runner = Runner(settings(tmp_path / "tokens"), http)
            runner.start_run(Assignment.parse(exchange.run))
            await runner.refresh_market()
            assert runner.tick() and runner.tick()
            assert not runner.tick(), "both bots still have a command in flight"
            while len(exchange.placed) < 2:
                await asyncio.sleep(0.01)
            exchange.release.set()
            await asyncio.wait_for(runner.drain(), 2)
            assert runner.activity.placed == 2
            assert runner.tick()
            await asyncio.wait_for(runner.drain(), 2)

    asyncio.run(scenario())
    tokens = {request.headers["Authorization"] for request in exchange.placed[:2]}
    assert tokens == {f"Bearer {value}" for value in accounts.values()}
    assert len(exchange.placed) == 3


def test_a_run_trades_its_instruments_until_stopped_then_cancels_quotes_and_confirms(
    tmp_path,
) -> None:
    accounts = write_tokens(tmp_path / "tokens", 3)
    exchange = Exchange()
    exchange.run = run_body(
        accounts,
        ["NOISE", "NOISE", "MOMENTUM"],
        totals={"placed": 5, "canceled": 1, "rejected": 0, "errors": 0},
    )
    tokens = list(accounts.values())
    old = [exchange.open_order(tokens[0]), exchange.open_order(tokens[2], OTHER)]
    stuck = exchange.open_order(tokens[1])
    exchange.uncancelable.add(stuck)

    def stop_with_commands_in_flight() -> None:
        # Orders hang until well after the stop is seen, so the stop must wait for their answers.
        exchange.release.clear()
        exchange.desired = "STOPPED"
        asyncio.get_running_loop().call_later(0.5, exchange.release.set)

    async def scenario() -> Runner:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            runner = Runner(settings(tmp_path / "tokens"), http)
            await follow_until(
                runner,
                exchange,
                lambda: len(exchange.placed) >= 6 and len(exchange.beats) >= 2,
                stop_with_commands_in_flight,
            )
            return runner

    runner = asyncio.run(scenario())
    assert placed_instruments(exchange) == {INSTRUMENT}, "only the run's instruments"
    traders = {request.headers["Authorization"] for request in exchange.placed}
    assert f"Bearer {tokens[2]}" not in traders, (
        "a strategy that isn't available yet trades nothing"
    )
    assert set(old) <= set(exchange.canceled), "every bot's quotes go, whatever its strategy"
    final = exchange.beats[-1]
    assert final["stopped"] is True
    assert final["inFlight"] == 0
    assert final["stopFailures"] == 1, "the order that couldn't be cancelled is reported"
    assert final["placed"] == 5 + len(exchange.placed), "totals continue from the run's"
    assert final["placed"] == runner.activity.placed
    assert all(beat["workerId"] == "worker-1" for beat in exchange.beats)
    assert not any(beat["stopped"] for beat in exchange.beats[:-1])
    counts = [beat["placed"] for beat in exchange.beats]
    assert counts == sorted(counts), "cumulative totals never go back"


def test_a_run_left_stopping_by_another_worker_is_settled_without_trading(tmp_path) -> None:
    accounts = write_tokens(tmp_path / "tokens", 1)
    exchange = Exchange()
    exchange.desired = "STOPPED"
    exchange.run = run_body(accounts, ["NOISE"], desiredState="STOPPED")
    quote_id = exchange.open_order(next(iter(accounts.values())))

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            runner = Runner(settings(tmp_path / "tokens"), http)
            await asyncio.wait_for(runner.follow(Assignment.parse(exchange.run)), 5)

    asyncio.run(scenario())
    assert exchange.placed == []
    assert exchange.canceled == [quote_id]
    assert [beat["stopped"] for beat in exchange.beats] == [False, True]


@pytest.mark.parametrize("code", ["LEASE_HELD", "RUN_FINISHED"])
def test_without_the_lease_nothing_trades(tmp_path, code) -> None:
    accounts = write_tokens(tmp_path / "tokens", 1)
    exchange = Exchange()
    exchange.refuse = code
    exchange.run = run_body(accounts, ["NOISE"])
    exchange.open_order(next(iter(accounts.values())))

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            runner = Runner(settings(tmp_path / "tokens"), http)
            await asyncio.wait_for(runner.follow(Assignment.parse(exchange.run)), 5)

    asyncio.run(scenario())
    assert exchange.placed == []
    assert exchange.canceled == [], "another worker's quotes are its to cancel"
    assert len(exchange.beats) == 1


def test_a_lost_lease_stops_trading_at_once_without_settling(tmp_path) -> None:
    accounts = write_tokens(tmp_path / "tokens", 1)
    exchange = Exchange()
    exchange.run = run_body(accounts, ["NOISE"])
    quote_id = exchange.open_order(next(iter(accounts.values())))

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            runner = Runner(settings(tmp_path / "tokens"), http)
            await follow_until(
                runner,
                exchange,
                lambda: len(exchange.placed) >= 2,
                lambda: setattr(exchange, "refuse", "LEASE_HELD"),
            )

    asyncio.run(scenario())
    assert quote_id not in exchange.canceled
    assert not any(beat["stopped"] for beat in exchange.beats)


def test_unanswered_heartbeats_stop_new_commands_before_the_lease_could_lapse(tmp_path) -> None:
    accounts = write_tokens(tmp_path / "tokens", 1)
    exchange = Exchange()
    exchange.run = run_body(accounts, ["NOISE"])
    exchange.release.set()
    now = [0.0]

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            runner = Runner(settings(tmp_path / "tokens"), http, clock=lambda: now[0])
            runner.start_run(Assignment.parse(exchange.run))
            assert (await runner.beat()).held
            stop = asyncio.Event()
            trading = asyncio.create_task(runner._trade(stop))
            while not exchange.placed:
                await asyncio.sleep(0.01)
            now[0] = 0.81  # 81% of a one-second lease without a renewal
            await asyncio.sleep(0.05)
            await runner.drain()
            before = len(exchange.placed)
            await asyncio.sleep(0.1)
            stop.set()
            await asyncio.wait_for(trading, 1)
            assert len(exchange.placed) == before, "no new command without a renewed lease"

    asyncio.run(scenario())


def test_stopping_the_process_lets_the_lease_lapse_without_settling(tmp_path) -> None:
    accounts = write_tokens(tmp_path / "tokens", 1)
    exchange = Exchange()
    exchange.run = run_body(accounts, ["NOISE"])
    quote_id = exchange.open_order(next(iter(accounts.values())))

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            stop = asyncio.Event()
            worker = asyncio.create_task(Runner(settings(tmp_path / "tokens"), http).run(stop))
            while len(exchange.placed) < 2:
                await asyncio.sleep(0.01)
            stop.set()
            await asyncio.wait_for(worker, 2)

    asyncio.run(scenario())
    assert quote_id not in exchange.canceled
    assert not any(beat["stopped"] for beat in exchange.beats)


def test_without_a_control_token_the_worker_stays_idle(tmp_path) -> None:
    accounts = write_tokens(tmp_path / "tokens", 1)
    exchange = Exchange()
    exchange.run = run_body(accounts, ["NOISE"])

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            stop = asyncio.Event()
            runner = Runner(settings(tmp_path / "tokens", simulator_token=None), http)
            task = asyncio.create_task(runner.run(stop))
            await asyncio.sleep(0.05)
            stop.set()
            await asyncio.wait_for(task, 1)

    asyncio.run(scenario())
    assert exchange.placed == [] and exchange.beats == []


def test_market_data_that_is_not_live_pauses_new_orders(tmp_path) -> None:
    accounts = write_tokens(tmp_path / "tokens", 1)
    exchange = Exchange()
    exchange.live = "CATCHING_UP"
    exchange.run = run_body(accounts, ["NOISE"])

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            runner = Runner(settings(tmp_path / "tokens"), http)
            runner.start_run(Assignment.parse(exchange.run))
            await runner.refresh_market()
            runner.tick()
            await asyncio.wait_for(runner.drain(), 2)

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


def test_each_bot_draws_from_its_own_generator_seeded_by_the_run(tmp_path) -> None:
    accounts = write_tokens(tmp_path / "tokens", 2)
    exchange = Exchange()

    async def decisions(seed: str):
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            runner = Runner(settings(tmp_path / "tokens"), http)
            runner.start_run(Assignment.parse(run_body(accounts, ["NOISE", "NOISE"], seed=seed)))
            await runner.refresh_market()
            holdings = Holdings(cash=10**9, shares={UUID(INSTRUMENT): 10**6})
            return [
                [bot.strategy.decide(runner._market, True, holdings, [], 0.0) for _ in range(20)]
                for bot in runner._bots.values()
            ]

    first, second = asyncio.run(decisions("7"))
    assert first != second, "two bots with one run seed must not trade in lockstep"
    assert asyncio.run(decisions("7")) == [first, second], "the same seed repeats them"
    assert asyncio.run(decisions("8")) != [first, second]
