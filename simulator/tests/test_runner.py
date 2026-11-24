import asyncio
import json
import logging
import threading
from datetime import UTC, datetime
from uuid import UUID, uuid4

import httpx
import numpy as np
import pytest

from mse_simulator import runner as runner_module
from mse_simulator.control import Assignment
from mse_simulator.credentials import load_token
from mse_simulator.manifest import manifest
from mse_simulator.market import UNSEEN, Instrument, MarketView, fetch
from mse_simulator.market_maker import MarketMaker
from mse_simulator.pacing import Pacer
from mse_simulator.runner import Activity, Runner
from mse_simulator.settings import Settings
from mse_simulator.strategy import Holdings

INSTRUMENT = str(uuid4())
OTHER = str(uuid4())
# The simulation the market is of, and an instrument of the one before it.
SIMULATION = str(uuid4())
EARLIER = str(uuid4())
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
    """A run as the assignment gives it, with its bots as their pages would give them, together."""
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


def assigned(body: dict) -> Assignment:
    """The assignment the control client builds from the run and its pages."""
    return Assignment.parse(served_run(body), body["bots"])


def served_run(body: dict) -> dict:
    """The run without its bots, as the assignment serves it: only their count."""
    run = {key: value for key, value in body.items() if key != "bots"}
    return run | {"botCount": len(body["bots"])}


def quote(instrument: str, last: str | None = None) -> dict:
    return {
        "instrumentId": instrument,
        "symbol": "NOVA" if instrument == INSTRUMENT else "ZETA",
        "tick": "0.01",
        "referencePrice": "100.00",
        "lastPrice": last,
        "bestBid": {"price": "99.50", "quantity": 3, "orderCount": 1},
        "bestAsk": None,
        "maxPrice": "1000000.00",
        "minQuantity": 1,
        "maxQuantity": 1000000,
    }


class Exchange:
    """A fake exchange: an open, live market with two instruments, every bot well funded, and the
    control plane. Heartbeats answer with ``desired`` until ``refuse`` names a refusal code. With
    ``rest``, placed orders stay active; with ``trend``, the last price moves that many cents per
    poll. ``state`` is the market's phase: while it is halted an order is turned away, as the
    exchange does. With ``revoked``, every bot token is refused; with ``commands_refused``, only
    when it sends an order or a cancel."""

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
        self.rest = False
        self.trend: int | None = None
        self.polls = 0
        self.pages = 0
        # When set, every market poll after the first waits until released.
        self.market_stall: asyncio.Event | None = None
        self.state = "OPEN"
        self.revoked = False
        self.commands_refused = False
        # The token of each order turned away because the market was halted.
        self.turned_away: list[str] = []
        # Refuses only a heartbeat that confirms a stop.
        self.refuse_stop: str | None = None
        # Seconds the phase takes to answer, and whether orders and Market Data are reachable.
        self.phase_delay = 0.0
        self.orders_down = False
        self.listing_down = False
        self.phase_down = False
        # Leaves every heartbeat unanswered: "503" answers that, "dropped" fails at the transport.
        self.beats_fail: str | None = None
        self.assignments = 0
        self.phases = 0
        # Every request made with a bot's token, and those refused for it: (token, path).
        self.bot_calls = 0
        self.refused: list[tuple[str, str]] = []
        self.order_attempts = 0
        # The simulation Execution names, and each listing's simulationId, None when not given.
        # With ``earlier_current``, Market Data still takes the previous simulation for current.
        self.simulation: str | None = SIMULATION
        self.listed_for: list[str | None] = []
        self.earlier_current = False

    def close(self) -> None:
        """Closes the market as Execution does: for good, every bot token revoked, the run ended
        and no longer assigned."""
        self.state = "CLOSED"
        self.revoked = True
        self.refuse = "RUN_FINISHED"
        self.run = None

    def open_order(
        self, bot_token: str, instrument: str = INSTRUMENT, side: str = "BUY", limit="99.00"
    ) -> str:
        order_id = str(uuid4())
        self.active.setdefault(bot_token, []).append(
            {
                "orderId": order_id,
                "instrumentId": instrument,
                "side": side,
                "workingQuantity": 1,
                "limitPrice": limit,
                "createdAt": datetime.now(UTC).isoformat(),
            }
        )
        return order_id

    def bot_page(self, request: httpx.Request) -> httpx.Response:
        """A page of the run's bots; the cursor is where the page starts."""
        assert self.run is not None and request.url.path.split("/")[-2] == self.run["runId"]
        self.pages += 1
        start = int(request.url.params.get("cursor", "0"))
        limit = int(request.url.params["limit"])
        bots = self.run["bots"][start : start + limit]
        more = start + limit < len(self.run["bots"])
        return httpx.Response(
            200, json={"bots": bots, "nextCursor": str(start + limit) if more else None}
        )

    async def handle(self, request: httpx.Request) -> httpx.Response:
        path = request.url.path
        bearer = request.headers.get("Authorization", "").removeprefix("Bearer ")
        if path.startswith("/internal/"):
            assert bearer == CONTROL, "control routes take only the control token"
            if path == "/internal/simulator/assignment":
                self.assignments += 1
                return httpx.Response(
                    200, json={"run": None if self.run is None else served_run(self.run)}
                )
            if path.endswith("/bots"):
                return self.bot_page(request)
            beat = json.loads(request.content)
            self.beats.append(beat)
            if self.beats_fail == "dropped":
                raise httpx.ConnectError("refused", request=request)
            if self.beats_fail is not None:
                return httpx.Response(int(self.beats_fail))
            refusal = self.refuse or (self.refuse_stop if beat["stopped"] else None)
            if refusal is not None:
                return httpx.Response(409, json={"code": refusal})
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
            self.phases += 1
            await asyncio.sleep(self.phase_delay)
            if self.phase_down:
                return httpx.Response(503)
            return httpx.Response(200, json={"state": self.state, "simulationId": self.simulation})
        if path == "/api/v1/instruments":
            if self.listing_down:
                return httpx.Response(503)
            self.polls += 1
            asked = request.url.params.get("simulationId")
            self.listed_for.append(asked)
            if self.market_stall is not None and self.polls > 1:
                await self.market_stall.wait()
            if asked is None and self.earlier_current:
                return httpx.Response(
                    200,
                    json={"feed": {"state": self.live, "lag": 0}, "instruments": [quote(EARLIER)]},
                )
            if asked not in (None, self.simulation):
                return httpx.Response(
                    200, json={"feed": {"state": self.live, "lag": 0}, "instruments": []}
                )
            last = None
            if self.trend is not None:
                cents = 10_000 + self.trend * self.polls
                last = f"{cents // 100}.{cents % 100:02d}"
            return httpx.Response(
                200,
                json={
                    "feed": {"state": self.live, "lag": 0},
                    "instruments": [quote(INSTRUMENT, last), quote(OTHER, last)],
                },
            )
        self.bot_calls += 1
        if self.revoked or (self.commands_refused and request.method == "POST"):
            self.refused.append((bearer, path))
            return httpx.Response(401)
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
            self.order_attempts += 1
            if self.orders_down:
                raise httpx.ConnectError("refused", request=request)
            if self.state == "HALTED":
                self.turned_away.append(bearer)
                return httpx.Response(422, json={"code": "MARKET_NOT_OPEN"})
            self.placed.append(request)
            await self.release.wait()
            if self.rest:
                order = json.loads(request.content)
                self.open_order(bearer, order["instrumentId"], order["side"], order["limitPrice"])
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


async def until(condition) -> None:
    for _ in range(300):
        if condition():
            break
        await asyncio.sleep(0.01)
    assert condition(), "the condition never held"


def closed_lines(caplog) -> list[str]:
    """The lines that say a run ended because the market closed."""
    return [r.getMessage() for r in caplog.records if "the market closed" in r.getMessage()]


def warnings(caplog) -> list[str]:
    return [r.getMessage() for r in caplog.records if r.levelno >= logging.WARNING]


def traders(requests: list[httpx.Request]) -> set[str]:
    """The bot tokens the requests were made with."""
    return {request.headers["Authorization"].removeprefix("Bearer ") for request in requests}


async def soon(seconds: float) -> None:
    """A sleep for a worker whose waits (a retry's, a stop asked again) should come round at
    once."""
    await asyncio.sleep(min(seconds, 0.01))


async def follow_until(runner: Runner, exchange: Exchange, condition, then=None) -> None:
    """Follows the exchange's run until the condition holds, then applies ``then``."""
    task = asyncio.create_task(runner.follow(assigned(exchange.run)))
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
            runner.start_run(assigned(exchange.run))
            await runner.load_bots()
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
        ["NOISE", "NOISE", "ARBITRAGE"],
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
        "a strategy this worker doesn't know trades nothing"
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
    assert exchange.beats[0]["manifest"] == manifest(settings(tmp_path / "tokens"))
    assert not any("manifest" in beat for beat in exchange.beats[1:]), "heard once, sent once"


def test_a_run_left_stopping_by_another_worker_is_settled_without_trading(tmp_path) -> None:
    accounts = write_tokens(tmp_path / "tokens", 1)
    exchange = Exchange()
    exchange.desired = "STOPPED"
    exchange.run = run_body(accounts, ["NOISE"], desiredState="STOPPED")
    quote_id = exchange.open_order(next(iter(accounts.values())))

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            runner = Runner(settings(tmp_path / "tokens"), http)
            await asyncio.wait_for(runner.follow(assigned(exchange.run)), 5)

    asyncio.run(scenario())
    assert exchange.placed == []
    assert exchange.canceled == [quote_id]
    assert [beat["stopped"] for beat in exchange.beats] == [False, True]


@pytest.mark.parametrize("code", ["LEASE_HELD", "RUN_FINISHED"])
def test_without_the_lease_nothing_trades(tmp_path, code, monkeypatch) -> None:
    read: list[str] = []
    monkeypatch.setattr(runner_module, "load_token", lambda directory, ref: read.append(ref))
    accounts = write_tokens(tmp_path / "tokens", 1)
    exchange = Exchange()
    exchange.refuse = code
    exchange.run = run_body(accounts, ["NOISE"])
    exchange.open_order(next(iter(accounts.values())))

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            runner = Runner(settings(tmp_path / "tokens"), http)
            await asyncio.wait_for(runner.follow(assigned(exchange.run)), 5)

    asyncio.run(scenario())
    assert exchange.placed == []
    assert exchange.canceled == [], "another worker's quotes are its to cancel"
    assert len(exchange.beats) == 1
    assert read == [], "a worker without the lease reads no token"


def test_the_manifest_goes_again_until_a_beat_for_the_run_is_held(tmp_path) -> None:
    accounts = write_tokens(tmp_path / "tokens", 1)
    exchange = Exchange()
    exchange.refuse = "LEASE_HELD"
    exchange.run = run_body(accounts, ["NOISE"])

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            runner = Runner(settings(tmp_path / "tokens"), http)
            await asyncio.wait_for(runner.follow(assigned(exchange.run)), 5)
            # Another worker held it; once free, the next try sends the manifest again.
            exchange.refuse = None
            exchange.desired = "STOPPED"
            await asyncio.wait_for(runner.follow(assigned(exchange.run)), 5)

    asyncio.run(scenario())
    with_manifest = ["manifest" in beat for beat in exchange.beats]
    assert with_manifest[:2] == [True, True], "refused, then held: both carry it"
    assert not any(with_manifest[2:]), "nothing after the first held beat"
    assert exchange.beats[-1]["stopped"] is True


def test_the_manifest_goes_until_an_accepted_beat_and_again_for_the_next_run(tmp_path) -> None:
    accounts = write_tokens(tmp_path / "tokens", 1)
    exchange = Exchange()
    first = run_body(accounts, ["NOISE"])
    second = run_body(accounts, ["NOISE"])

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            runner = Runner(settings(tmp_path / "tokens"), http)
            runner.start_run(assigned(first))
            # Kept as held, but refused before anything was stored: still unheard.
            exchange.refuse = "RUN_NOT_STOPPING"
            await runner.beat()
            exchange.refuse = None
            await runner.beat()
            await runner.beat()
            # A new run: its first beat carries it again.
            runner.start_run(assigned(second))
            await runner.beat()
            await runner.beat()

    asyncio.run(scenario())
    assert [(beat["runId"], "manifest" in beat) for beat in exchange.beats] == [
        (first["runId"], True),
        (first["runId"], True),
        (first["runId"], False),
        (second["runId"], True),
        (second["runId"], False),
    ]


def test_heartbeats_go_on_while_a_run_loads_and_nothing_trades_until_it_has(
    tmp_path, monkeypatch
) -> None:
    accounts = write_tokens(tmp_path / "tokens", 3)
    exchange = Exchange()
    exchange.run = run_body(accounts, ["NOISE", "NOISE", "NOISE"])
    loading = threading.Event()
    real = runner_module.load_token

    def slow(directory, ref):
        # A token file on a slow disk: the read waits, off the event loop, until released.
        assert loading.wait(5), "never released"
        return real(directory, ref)

    monkeypatch.setattr(runner_module, "load_token", slow)

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            runner = Runner(settings(tmp_path / "tokens"), http)
            task = asyncio.create_task(runner.follow(assigned(exchange.run)))
            for _ in range(300):
                if len(exchange.beats) >= 3:
                    break
                await asyncio.sleep(0.01)
            assert len(exchange.beats) >= 3, "the lease keeper beats while tokens are read"
            # No bot exists yet, so nothing could be placed anyway: what shows trading has not
            # started is that it has no pacer and has not polled the market.
            assert runner._pacer is None and exchange.polls == 0, "trading waits for the bots"
            loading.set()
            for _ in range(300):
                if exchange.placed:
                    break
                await asyncio.sleep(0.01)
            assert exchange.placed, "trading starts once they are"
            exchange.desired = "STOPPED"
            await asyncio.wait_for(task, 5)

    asyncio.run(scenario())


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
            runner.start_run(assigned(exchange.run))
            await runner.load_bots()
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


def test_building_a_large_run_yields_to_the_event_loop(tmp_path, monkeypatch) -> None:
    accounts = write_tokens(tmp_path / "tokens", 6)
    monkeypatch.setattr(runner_module, "BUILD_BATCH", 2)
    events: list[str] = []

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(Exchange().handle)) as http:
            runner = Runner(settings(tmp_path / "tokens"), http)
            build = runner._strategy

            def recorded(*args):
                events.append("build")
                return build(*args)

            monkeypatch.setattr(runner, "_strategy", recorded)

            async def other_work() -> None:
                while True:
                    events.append("other")
                    await asyncio.sleep(0)

            other = asyncio.create_task(other_work())
            runner.start_run(assigned(run_body(accounts, ["NOISE"] * 6)))
            await runner.load_bots()
            other.cancel()

    asyncio.run(scenario())
    built = [index for index, event in enumerate(events) if event == "build"]
    assert len(built) == 6
    between = events[built[0] : built[-1]]
    assert between.count("other") >= 2, "other work runs between batches of bots"


def test_a_stalled_market_poll_does_not_stop_the_bots(tmp_path) -> None:
    accounts = write_tokens(tmp_path / "tokens", 2)
    exchange = Exchange()
    exchange.run = run_body(accounts, ["NOISE", "NOISE"])
    exchange.market_stall = asyncio.Event()

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            runner = Runner(settings(tmp_path / "tokens", market_poll_seconds=0.01), http)
            task = asyncio.create_task(runner.follow(assigned(exchange.run)))
            for _ in range(300):
                if exchange.polls >= 2 and len(exchange.placed) >= 6:
                    break
                await asyncio.sleep(0.01)
            assert exchange.polls >= 2, "the second poll is waiting"
            assert len(exchange.placed) >= 6, "steps go on while the market poll is stuck"
            assert runner.activity.steps >= 6 and runner.activity.reads >= 12
            exchange.market_stall.set()
            exchange.desired = "STOPPED"
            await asyncio.wait_for(task, 5)

    asyncio.run(scenario())


def test_the_trading_loop_never_ticks_faster_than_its_floor(tmp_path) -> None:
    accounts = write_tokens(tmp_path / "tokens", 1)
    exchange = Exchange()
    exchange.run = run_body(accounts, ["NOISE"], commandsPerSecond="2000.00")
    pauses: list[float] = []

    async def scenario() -> None:
        async def sleep(seconds: float) -> None:
            pauses.append(seconds)
            if len(pauses) > 20:
                exchange.desired = "STOPPED"
            await asyncio.sleep(0)

        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            runner = Runner(settings(tmp_path / "tokens"), http, sleep=sleep)
            runner.start_run(assigned(exchange.run))
            await runner.load_bots()
            runner._lease_until = float("inf")
            stop = asyncio.Event()
            trading = asyncio.create_task(runner._trade(stop))
            for _ in range(200):
                if len(pauses) > 10:
                    break
                await asyncio.sleep(0)
            stop.set()
            await asyncio.wait_for(trading, 5)

    asyncio.run(scenario())
    assert pauses and min(pauses) >= runner_module.MIN_TICK, "1/2000 s would spin the loop"


def test_market_data_that_is_not_live_pauses_new_orders(tmp_path) -> None:
    accounts = write_tokens(tmp_path / "tokens", 1)
    exchange = Exchange()
    exchange.live = "CATCHING_UP"
    exchange.run = run_body(accounts, ["NOISE"])

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            runner = Runner(settings(tmp_path / "tokens"), http)
            runner.start_run(assigned(exchange.run))
            await runner.load_bots()
            await runner.refresh_market()
            runner.tick()
            await asyncio.wait_for(runner.drain(), 2)

    asyncio.run(scenario())
    assert exchange.placed == []


def test_a_command_with_no_idle_bot_waits_in_the_pacer(tmp_path) -> None:
    accounts = write_tokens(tmp_path / "tokens", 1)
    exchange = Exchange()
    exchange.run = run_body(accounts, ["NOISE"])

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            runner = Runner(settings(tmp_path / "tokens"), http)
            runner.start_run(assigned(exchange.run))
            await runner.load_bots()
            for bot in runner._bots.values():
                bot.busy = True
            pacer = Pacer(1.0, 0.0)

            assert runner.spend(pacer) == 0
            assert pacer.take(), "the command was kept for the next round"

    asyncio.run(scenario())


class Waits:
    """A strategy that always decides to wait."""

    def decide(self, *_args) -> None:
        return None


@pytest.mark.parametrize(("live", "refunded"), [("LIVE", True), ("CATCHING_UP", False)])
def test_a_bot_that_waits_by_choice_gives_its_command_to_another(tmp_path, live, refunded) -> None:
    """The run's rate counts commands: a step that decides to wait hands its credit back, but not
    while the data is stale, when the bots pause at the paced rate of steps."""
    accounts = write_tokens(tmp_path / "tokens", 1)
    exchange = Exchange()
    exchange.live = live
    exchange.run = run_body(accounts, ["NOISE"])

    async def scenario() -> bool:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            runner = Runner(settings(tmp_path / "tokens"), http)
            runner.start_run(assigned(exchange.run))
            await runner.load_bots()
            await runner.refresh_market()
            for bot in runner._bots.values():
                bot.strategy = Waits()
            runner._pacer = pacer = Pacer(1.0, 0.0)
            assert pacer.take() and not pacer.take()
            runner.tick()
            await asyncio.wait_for(runner.drain(), 2)
            return pacer.take()

    assert asyncio.run(scenario()) is refunded
    assert exchange.placed == []


@pytest.mark.parametrize(("now", "expected"), [(1.0, True), (2.0, True), (2.5, False)])
def test_data_older_than_the_budget_pauses_new_orders(now, expected) -> None:
    exchange = Exchange()

    async def scenario():
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            return await fetch(http, "http://execution", "http://market", lambda: 0.0)

    market = asyncio.run(scenario())
    assert market.instruments[0].bid == 9_950
    assert market.tradable(now, 2.0) is expected


def test_the_view_is_as_old_as_its_answers_not_as_old_as_the_poll(tmp_path) -> None:
    """A poll that took three seconds brought data one second old a second later: still fresh
    enough to trade on. Stamped when the poll began, it read four seconds old, and polls slower
    than the two-second budget would have paused the bots for good."""
    exchange = Exchange()
    now = [0.0]
    tmp_path.joinpath("tokens").mkdir()

    async def slow(request: httpx.Request) -> httpx.Response:
        answer = await exchange.handle(request)
        if request.url.path == "/api/v1/instruments":
            now[0] += 3.0
        return answer

    async def scenario():
        async with httpx.AsyncClient(transport=httpx.MockTransport(slow)) as http:
            market = await fetch(http, "http://execution", "http://market", lambda: now[0])
            runner = Runner(settings(tmp_path / "tokens"), http, clock=lambda: now[0])
            await runner.refresh_market()
            return market, runner._market

    market, polled = asyncio.run(scenario())
    assert market.fetched_at == 3.0
    assert market.tradable(4.0, 2.0)
    assert polled.fetched_at == 6.0, "the worker's poll too"


def test_the_instruments_are_those_of_the_simulation_execution_names() -> None:
    """Market Data takes for current the simulation whose instrument changed last, which early in
    a new simulation can still be the one before. The view asks for the one Execution names."""
    exchange = Exchange()
    exchange.earlier_current = True

    async def scenario():
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            named = await fetch(http, "http://execution", "http://market", lambda: 0.0)
            exchange.simulation = None
            unset = await fetch(http, "http://execution", "http://market", lambda: 0.0)
            return named, unset

    named, unset = asyncio.run(scenario())
    assert {str(item.instrument_id) for item in named.instruments} == {INSTRUMENT, OTHER}
    assert exchange.listed_for == [SIMULATION, None], "no simulation yet: nothing to name"
    assert [str(item.instrument_id) for item in unset.instruments] == [EARLIER]


def test_a_run_in_a_new_simulation_trades_before_market_data_takes_it_for_current(
    tmp_path,
) -> None:
    accounts = write_tokens(tmp_path / "tokens", 2)
    exchange = Exchange()
    exchange.earlier_current = True
    exchange.run = run_body(accounts, ["NOISE", "NOISE"])

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            await follow_until(
                Runner(settings(tmp_path / "tokens"), http),
                exchange,
                lambda: len(exchange.placed) >= 4,
                lambda: setattr(exchange, "desired", "STOPPED"),
            )

    asyncio.run(scenario())
    assert placed_instruments(exchange) == {INSTRUMENT}
    assert set(exchange.listed_for) == {SIMULATION}


def test_each_bot_draws_from_its_own_generator_seeded_by_the_run(tmp_path) -> None:
    accounts = write_tokens(tmp_path / "tokens", 2)
    exchange = Exchange()

    async def decisions(seed: str):
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            runner = Runner(settings(tmp_path / "tokens"), http)
            runner.start_run(assigned(run_body(accounts, ["NOISE", "NOISE"], seed=seed)))
            await runner.load_bots()
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


def test_market_makers_take_the_first_turns_and_quote_both_sides(tmp_path) -> None:
    accounts = write_tokens(tmp_path / "tokens", 3)
    exchange = Exchange()
    exchange.rest = True
    # The maker is assigned last here; it still trades first.
    exchange.run = run_body(accounts, ["NOISE", "NOISE", "MARKET_MAKER"])
    maker = f"Bearer {list(accounts.values())[2]}"
    # A value that stands still and a maker that sees it as it is, so the quotes are exact.
    still = settings(tmp_path / "tokens", fair_value_volatility=0.0, view_spread_bp=0.0)

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            runner = Runner(still, http)
            await follow_until(
                runner,
                exchange,
                # Both quotes, then long enough for the maker to look at them many times over.
                lambda: (
                    sum(r.headers["Authorization"] == maker for r in exchange.placed) >= 2
                    and len(exchange.beats) >= 4
                ),
                lambda: setattr(exchange, "desired", "STOPPED"),
            )

    asyncio.run(scenario())
    assert exchange.placed[0].headers["Authorization"] == maker, "liquidity goes first"
    quotes = [json.loads(r.content) for r in exchange.placed if r.headers["Authorization"] == maker]
    sides = {quote["side"]: quote["limitPrice"] for quote in quotes[:2]}
    # Reference 100.00 with no trade or ask yet: the value starts there, ten basis points each side.
    assert sides == {"BUY": "99.90", "SELL": "100.10"}
    assert len(quotes) == 2, "resting quotes on target aren't replaced"


@pytest.mark.parametrize("cancelable", [True, False])
def test_a_maker_replaces_a_stale_quote_in_one_step_and_pays_for_both_commands(
    tmp_path, cancelable
) -> None:
    """Its bid has rested past the quote lifetime: one step cancels it and asks, the side with no
    quote. The place is a second command, so the pacer is charged for it. A cancel the exchange
    refuses leaves the bid where it was, and nothing goes beside it."""
    accounts = write_tokens(tmp_path / "tokens", 1)
    exchange = Exchange()
    exchange.run = run_body(accounts, ["MARKET_MAKER"])
    maker = next(iter(accounts.values()))
    stale = exchange.open_order(maker, side="BUY", limit="99.90")
    exchange.active[maker][0]["createdAt"] = "2026-01-01T00:00:00+00:00"
    if not cancelable:
        exchange.uncancelable.add(stale)
    still = settings(tmp_path / "tokens", fair_value_volatility=0.0, view_spread_bp=0.0)

    async def scenario() -> tuple[Runner, Pacer]:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            runner = Runner(still, http)
            runner.start_run(assigned(exchange.run))
            await runner.load_bots()
            await runner.refresh_market()
            runner._pacer = pacer = Pacer(1.0, 0.0)
            assert pacer.take()
            runner.tick()
            await asyncio.wait_for(runner.drain(), 2)
            return runner, pacer

    runner, pacer = asyncio.run(scenario())
    if cancelable:
        assert exchange.canceled == [stale]
        assert [json.loads(r.content)["side"] for r in exchange.placed] == ["SELL"]
        assert (runner.activity.canceled, runner.activity.placed) == (1, 1)
        pacer.accrue(1.5)
        assert not pacer.take(), "a second and a half pays for two commands, not a third"
    else:
        assert exchange.canceled == [] and exchange.placed == []
        assert (runner.activity.rejected, runner.activity.placed) == (1, 0)
        pacer.accrue(1.0)
        assert pacer.take()


def test_momentum_bots_trade_on_the_signal_the_market_polls_feed(tmp_path) -> None:
    accounts = write_tokens(tmp_path / "tokens", 1)
    exchange = Exchange()
    exchange.rest = True
    exchange.trend = 10
    exchange.run = run_body(accounts, ["MOMENTUM"])
    quick = settings(
        tmp_path / "tokens",
        market_poll_seconds=0.01,
        momentum_short_polls=1,
        momentum_long_polls=3,
        momentum_threshold=0.0001,
        momentum_cooldown_seconds=0.0,
        # Always with the trend, so the first order's side is certain.
        momentum_follow=0.5,
    )

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            await follow_until(
                Runner(quick, http),
                exchange,
                lambda: len(exchange.placed) >= 1,
                lambda: setattr(exchange, "desired", "STOPPED"),
            )

    asyncio.run(scenario())
    order = json.loads(exchange.placed[0].content)
    assert exchange.polls >= 3, "no order before the long window fills"
    assert order["side"] == "BUY", "a rising market draws buys"
    assert order["instrumentId"] == INSTRUMENT


def test_each_run_has_its_own_fair_values_from_its_seed_and_numbers_its_makers_afresh(
    tmp_path,
) -> None:
    accounts = write_tokens(tmp_path / "tokens", 3)
    exchange = Exchange()

    async def values_and_places(runner: Runner, seed: str) -> tuple[list[float], list[int]]:
        runner.start_run(assigned(run_body(accounts, ["MARKET_MAKER"] * 2 + ["NOISE"], seed=seed)))
        assert runner._values.of(UUID(INSTRUMENT)) is None, "a new run starts its values over"
        await runner.load_bots()
        path = []
        for _ in range(5):
            await runner.refresh_market()
            path.append(runner._values.of(UUID(INSTRUMENT)))
        places = [
            bot.strategy._place
            for bot in runner._bots.values()
            if isinstance(bot.strategy, MarketMaker)
        ]
        return path, places

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            runner = Runner(settings(tmp_path / "tokens"), http)
            first, places = await values_and_places(runner, "7")
            assert places == [0, 1]
            assert first[0] == pytest.approx(10_000) and len(set(first)) == 5, "it moves per poll"
            other, places = await values_and_places(runner, "8")
            assert places == [0, 1], "numbered from the start again"
            assert other[0] == pytest.approx(10_000) and other != first, "another seed's path"
            again, _ = await values_and_places(runner, "7")
            assert again == first, "the same seed repeats it"

    asyncio.run(scenario())


def test_the_narrow_book_setting_reaches_the_values_and_every_strategy(tmp_path) -> None:
    """A book 200 basis points wide, from a reference of 80.00: narrow enough at 250, so every
    part of the run takes its mid of 100.00 for the price; at the default of 100 they would take
    the reference."""
    runner = Runner(settings(tmp_path, narrow_book_bp=250.0), httpx.AsyncClient())
    run = assigned(run_body({}, []))
    runner.start_run(run)

    def book(number: int) -> Instrument:
        return Instrument(UUID(int=number), "NOVA", 1, 8_000, None, 9_900, 10_100, 10**8, 1, 10**6)

    item, unseen = book(1), book(2)
    market = MarketView(state="OPEN", live=True, instruments=(item,), fetched_at=0.0)
    runner._signal.observe(market)
    runner._values.observe(market)
    assert runner._values.of(item.instrument_id) == pytest.approx(10_000)
    assert runner._signal._prices[item.instrument_id][-1] == 10_000

    def built(name: str):
        return runner._strategy(name, np.random.default_rng([7, 1]), run)

    # With no value for an instrument, a view goes by the market's price.
    assert built("MARKET_MAKER")._view.of(unseen) == 10_000
    assert built("NOISE")._view.of(unseen) == 10_000
    # Momentum reaches 20 basis points past the price: 100.20, short of the ask at 101.00.
    assert built("MOMENTUM")._limit(item, "BUY") == 10_020
    holdings = Holdings(cash=10**9, shares={item.instrument_id: 10**6})
    noise = built("NOISE").decide(market, True, holdings, [], 0.0)
    # Within 30 basis points of the bot's view, itself within 20 of the value at 100.00.
    assert abs(noise.limit / 10_000 - 1) <= 0.0051


@pytest.mark.parametrize("strategy", ["MARKET_MAKER", "MOMENTUM", "NOISE"])
def test_every_strategy_is_seeded_by_the_run(tmp_path, strategy) -> None:
    accounts = write_tokens(tmp_path / "tokens", 2)
    exchange = Exchange()
    quick = settings(
        tmp_path / "tokens",
        momentum_short_polls=1,
        momentum_long_polls=2,
        momentum_threshold=0.001,
    )

    async def decisions(seed: str):
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            runner = Runner(quick, http)
            body = run_body(accounts, [strategy, strategy], seed=seed)
            body["instruments"].append({"instrumentId": OTHER, "symbol": "ZETA"})
            runner.start_run(assigned(body))
            await runner.load_bots()
            # The same observations every time: the price climbs from the same start.
            exchange.trend, exchange.polls = 30, 0
            for _ in range(2):
                await runner.refresh_market()
            holdings = Holdings(cash=10**9, shares={UUID(INSTRUMENT): 10**6, UUID(OTHER): 10**6})
            return [
                [
                    bot.strategy.decide(runner._market, True, holdings, [], float(step * 10))
                    for step in range(20)
                ]
                for bot in runner._bots.values()
            ]

    first = asyncio.run(decisions("7"))
    assert all(any(intent is not None for intent in bot) for bot in first), "every bot trades"
    assert asyncio.run(decisions("7")) == first, "the same seed repeats every decision"
    others = [asyncio.run(decisions(str(seed))) for seed in range(8, 14)]
    assert any(other != first for other in others), "another seed changes them"


def test_a_close_ends_the_run_with_no_cancel_and_the_worker_takes_the_next_run(
    tmp_path, caplog
) -> None:
    caplog.set_level(logging.INFO, logger="mse_simulator")
    accounts = write_tokens(tmp_path / "tokens", 3)
    exchange = Exchange()
    exchange.run = run_body(accounts, ["NOISE"] * 3)
    for value in accounts.values():
        exchange.open_order(value)

    async def scenario() -> Activity:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            stop = asyncio.Event()
            runner = Runner(settings(tmp_path / "tokens"), http)
            worker = asyncio.create_task(runner.run(stop))
            await until(lambda: len(exchange.placed) >= 3 and len(exchange.beats) >= 2)
            beats, asked = len(exchange.beats), exchange.assignments
            exchange.close()
            await until(lambda: exchange.assignments >= asked + 3)
            assert len(exchange.beats) <= beats + 1, "one last beat at most, refused or not"
            # Idle from here on: nothing but the question for the next run.
            quiet = (len(exchange.beats), exchange.bot_calls, exchange.phases)
            await until(lambda: exchange.assignments >= asked + 6)
            assert (len(exchange.beats), exchange.bot_calls, exchange.phases) == quiet
            ended = runner.activity
            # A new market and a new run: the closed one is not remembered against it.
            exchange.state, exchange.revoked, exchange.refuse = "OPEN", False, None
            placed = len(exchange.placed)
            exchange.run = run_body(accounts, ["NOISE"] * 3)
            await until(lambda: len(exchange.placed) >= placed + 3)
            stop.set()
            await asyncio.wait_for(worker, 5)
            return ended

    ended = asyncio.run(scenario())
    assert exchange.canceled == []
    assert not any(path.endswith("/cancel") for _, path in exchange.refused), "nothing to settle"
    bots = [bearer for bearer, _ in exchange.refused]
    assert len(bots) == len(set(bots)) <= 3, "a refused token is not tried again"
    assert ended.errors + ended.rejected <= 3 and ended.stop_failures == 0
    assert len(closed_lines(caplog)) == 1
    assert warnings(caplog) == [], "a market that closed is not a fault"


def test_a_closed_view_left_by_the_last_market_does_not_end_a_run_in_the_next(
    tmp_path, caplog
) -> None:
    """The next market's first quotes cannot be fetched, so the worker starts the new run with the
    closed view of the old market. Only a look made for the run itself may end it."""
    caplog.set_level(logging.INFO, logger="mse_simulator")
    accounts = write_tokens(tmp_path / "tokens", 2)
    exchange = Exchange()
    exchange.run = run_body(accounts, ["NOISE", "NOISE"])

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            stop = asyncio.Event()
            runner = Runner(settings(tmp_path / "tokens", market_poll_seconds=0.02), http)
            worker = asyncio.create_task(runner.run(stop))
            await until(lambda: len(exchange.placed) >= 2)
            # Tokens and heartbeats still answer: only the poll can see this close, so the view
            # the run leaves behind is the closed one.
            exchange.state, exchange.run = "CLOSED", None
            await until(lambda: len(closed_lines(caplog)) == 1)
            assert runner._market.closed
            placed, attempts = len(exchange.placed), exchange.order_attempts
            exchange.listing_down, exchange.state = True, "OPEN"
            exchange.run = new = run_body(accounts, ["NOISE", "NOISE"])

            def beats() -> list[dict]:
                return [beat for beat in exchange.beats if beat["runId"] == new["runId"]]

            await until(lambda: len(beats()) >= 1)
            asked = exchange.assignments
            await until(lambda: len(beats()) >= 3)
            assert exchange.assignments == asked, "the run is followed, not taken up over and over"
            assert not any(beat["stopped"] for beat in beats()), "nothing ended it"
            assert len(closed_lines(caplog)) == 1, "the old market's close is not the new one's"
            # The bots take their turns, and wait: the only view is of a market that is gone.
            await until(lambda: runner.activity.steps >= 4)
            assert exchange.order_attempts == attempts, "no order on a view of the last market"
            exchange.listing_down = False
            await until(lambda: traders(exchange.placed[placed:]) == set(accounts.values()))
            stop.set()
            await asyncio.wait_for(worker, 5)

    asyncio.run(scenario())
    assert warnings(caplog) == []


def test_a_token_refused_in_the_last_run_asks_nothing_about_the_next_runs_market(tmp_path) -> None:
    accounts = write_tokens(tmp_path / "tokens", 1)
    exchange = Exchange()
    exchange.run = run_body(accounts, ["NOISE"])

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            # No poll comes round, so a run asks for the phase once unless a token is refused.
            runner = Runner(settings(tmp_path / "tokens", market_poll_seconds=60.0), http)
            runner.start_run(assigned(exchange.run))
            await runner.load_bots()
            await runner.refresh_market()
            exchange.revoked = True
            # Refused where no trading loop is left to ask about it, as a step answered while a
            # run drains is.
            assert runner.tick()
            await runner.drain()
            assert runner.activity.unauthorized == 1 and not runner.tick()
            exchange.revoked = False
            asked = exchange.phases
            exchange.run = run_body(accounts, ["NOISE"])
            await follow_until(
                runner,
                exchange,
                lambda: len(exchange.placed) >= 1,
                lambda: setattr(exchange, "desired", "STOPPED"),
            )
            assert exchange.phases == asked + 1, "only the new run's first look at the market"
            assert runner.activity.unauthorized == 0

    asyncio.run(scenario())


def test_a_closed_market_seen_on_the_poll_ends_the_run_and_leaves_its_orders_alone(
    tmp_path, caplog
) -> None:
    """The worst case for a worker that would settle: the tokens still work and orders rest."""
    caplog.set_level(logging.INFO, logger="mse_simulator")
    accounts = write_tokens(tmp_path / "tokens", 2)
    exchange = Exchange()
    exchange.run = run_body(accounts, ["NOISE", "NOISE"])
    for value in accounts.values():
        exchange.open_order(value)
    seen: list[int] = []

    def close() -> None:
        exchange.state = "CLOSED"
        seen.extend([exchange.bot_calls, len(exchange.beats), exchange.polls])

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            runner = Runner(settings(tmp_path / "tokens", market_poll_seconds=0.02), http)
            await follow_until(runner, exchange, lambda: len(exchange.placed) >= 2, close)
            done = exchange.bot_calls
            await asyncio.sleep(0.1)
            assert exchange.bot_calls == done, "nothing goes on after the run ended"

    asyncio.run(scenario())
    calls, beats, polls = seen
    assert exchange.canceled == [], "the exchange cancels at a close; the worker sends no cancel"
    assert exchange.bot_calls - calls <= 3 * len(accounts), "only the steps already started"
    assert exchange.polls == polls, "a closed market is not asked for quotes"
    assert [beat["stopped"] for beat in exchange.beats[beats:]].count(True) == 1
    assert exchange.beats[-1]["stopped"] is True and exchange.beats[-1]["inFlight"] == 0
    assert len(closed_lines(caplog)) == 1 and warnings(caplog) == []


@pytest.mark.parametrize("fault", ["503", "dropped"])
def test_the_last_beat_of_a_closed_run_is_sent_once_though_nothing_answers_it(
    tmp_path, caplog, fault
) -> None:
    """Execution is busy with the close, or gone: the beat that reports the totals gets no answer.
    Unlike a stop, which is asked again until it is confirmed, there is nothing here to confirm."""
    caplog.set_level(logging.INFO, logger="mse_simulator")
    accounts = write_tokens(tmp_path / "tokens", 2)
    exchange = Exchange()
    exchange.run = run_body(accounts, ["NOISE", "NOISE"])
    seen: list[int] = []

    def close_with_heartbeats_unanswered() -> None:
        exchange.state, exchange.beats_fail = "CLOSED", fault
        seen.append(len(exchange.beats))

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            # A stop asked again waits a second each time; with this sleep it would come at once.
            runner = Runner(
                settings(tmp_path / "tokens", market_poll_seconds=0.02), http, sleep=soon
            )
            await follow_until(
                runner,
                exchange,
                lambda: len(exchange.placed) >= 2,
                close_with_heartbeats_unanswered,
            )
            sent = len(exchange.beats)
            await asyncio.sleep(0.1)
            assert len(exchange.beats) == sent, "nothing is asked again after the run ended"

    asyncio.run(scenario())
    # A beat that only renews the lease may be on its way when the close is seen.
    after = [beat["stopped"] for beat in exchange.beats[seen[0] :]]
    assert after.count(True) == 1 and after[-1] is True, "one last beat, answered or not"
    assert exchange.canceled == []
    assert len(closed_lines(caplog)) == 1
    # The control client's own word for a 503; never that a stop was not confirmed.
    assert set(warnings(caplog)) == ({"heartbeat answered 503"} if fault == "503" else set())


def test_refused_tokens_are_not_tried_again_while_the_market_still_reads_open(tmp_path) -> None:
    """The time between a close and the worker noticing it, stretched: every token is dead, and
    nothing else says so yet."""
    accounts = write_tokens(tmp_path / "tokens", 3)
    exchange = Exchange()
    exchange.run = run_body(accounts, ["NOISE"] * 3)

    async def scenario() -> Runner:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            runner = Runner(settings(tmp_path / "tokens", market_poll_seconds=0.1), http)
            task = asyncio.create_task(runner.follow(assigned(exchange.run)))
            await until(lambda: len(exchange.placed) >= 3)
            await runner.drain()
            failed = runner.activity.errors + runner.activity.rejected
            asked = exchange.phases - exchange.polls
            exchange.revoked = True
            # Fifteen turns at the run's rate.
            await asyncio.sleep(0.3)
            assert not task.done(), "refused tokens alone do not end a run"
            assert runner.activity.errors + runner.activity.rejected - failed <= 3
            assert runner.activity.unauthorized == len(exchange.refused)
            assert exchange.phases - exchange.polls - asked <= 3, "asked once per refusal at most"
            exchange.state = "CLOSED"
            await asyncio.wait_for(task, 5)
            return runner

    asyncio.run(scenario())
    bots = [bearer for bearer, _ in exchange.refused]
    assert 1 <= len(bots) <= 3 and len(bots) == len(set(bots)), "each bot is refused once"
    assert exchange.canceled == []


def test_a_refused_token_has_the_market_asked_at_once_not_at_the_next_poll(tmp_path) -> None:
    accounts = write_tokens(tmp_path / "tokens", 3)
    exchange = Exchange()
    exchange.run = run_body(accounts, ["NOISE"] * 3)

    def close_unheard_by_the_heartbeat() -> None:
        exchange.state, exchange.revoked = "CLOSED", True

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            # The next poll is a minute away, and the heartbeats still answer.
            runner = Runner(settings(tmp_path / "tokens", market_poll_seconds=60.0), http)
            await follow_until(
                runner, exchange, lambda: len(exchange.placed) >= 3, close_unheard_by_the_heartbeat
            )

    asyncio.run(scenario())
    assert 1 <= len(exchange.refused) <= 3
    assert exchange.beats[-1]["stopped"] is True
    assert exchange.canceled == []


def test_a_refused_token_with_the_phase_unanswered_ends_neither_the_run_nor_the_worker(
    tmp_path, caplog
) -> None:
    """The market closed, but Execution answers the question about it with a 503: unknown is not
    closed, and it is no reason to fall over either. The next answer that says so ends the run."""
    caplog.set_level(logging.INFO, logger="mse_simulator")
    accounts = write_tokens(tmp_path / "tokens", 3)
    exchange = Exchange()
    exchange.run = run_body(accounts, ["NOISE"] * 3)

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            # The next poll is a minute away: only a refused token or a heartbeat asks.
            runner = Runner(settings(tmp_path / "tokens", market_poll_seconds=60.0), http)
            task = asyncio.create_task(runner.follow(assigned(exchange.run)))
            await until(lambda: len(exchange.placed) >= 3)
            asked = exchange.phases
            exchange.state, exchange.revoked, exchange.phase_down = "CLOSED", True, True
            await until(lambda: len(exchange.refused) >= 1 and exchange.phases > asked)
            # Five more turns at the run's rate.
            await asyncio.sleep(0.1)
            assert not task.done(), "an unanswered question does not end the run"
            assert exchange.phases - asked <= 3, "asked once per refusal, not until it answers"
            # Execution answers again, and the next heartbeat hears that the run is over.
            exchange.phase_down, exchange.refuse = False, "RUN_FINISHED"
            await asyncio.wait_for(task, 5)

    asyncio.run(scenario())
    bots = [bearer for bearer, _ in exchange.refused]
    assert 1 <= len(bots) <= 3 and len(bots) == len(set(bots)), "each bot is refused once"
    assert exchange.canceled == []
    assert len(closed_lines(caplog)) == 1 and warnings(caplog) == []


@pytest.mark.parametrize("command", ["place", "cancel"])
def test_a_command_refused_for_its_token_is_the_bots_last_step_of_the_run(
    tmp_path, command
) -> None:
    """The reads still work, so only the answer to the command can retire the bot."""
    accounts = write_tokens(tmp_path / "tokens", 3)
    exchange = Exchange()
    exchange.run = run_body(accounts, ["NOISE"] * 3)
    exchange.commands_refused = True
    if command == "cancel":
        # At its limit of open orders a noise bot cancels its oldest before it places another.
        for value in accounts.values():
            exchange.open_order(value)

    async def scenario() -> Runner:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            runner = Runner(settings(tmp_path / "tokens", max_open_orders=1), http)
            task = asyncio.create_task(runner.follow(assigned(exchange.run)))
            await until(lambda: len(exchange.refused) >= 3)
            # Fifteen turns at the run's rate, and none of them taken.
            await asyncio.sleep(0.3)
            assert not task.done(), "refused tokens alone do not end a run"
            refused = list(exchange.refused)
            assert sorted(bearer for bearer, _ in refused) == sorted(accounts.values())
            assert all(path.endswith("/cancel") is (command == "cancel") for _, path in refused)
            assert (runner.activity.steps, runner.activity.unauthorized) == (3, 3)
            assert (runner.activity.rejected, runner.activity.errors) == (3, 0)
            exchange.desired = "STOPPED"
            await asyncio.wait_for(task, 5)
            return runner

    runner = asyncio.run(scenario())
    assert exchange.placed == [] and exchange.canceled == []
    # A stop still tries to cancel a retired bot's quotes, and reports each one it could not.
    assert runner.activity.stop_failures == (3 if command == "cancel" else 0)


@pytest.mark.parametrize(("state", "closed"), [("CLOSED", True), ("OPEN", False)])
def test_a_heartbeat_refused_for_a_finished_run_is_a_lost_lease_only_in_a_market_not_closed(
    tmp_path, caplog, state, closed
) -> None:
    caplog.set_level(logging.INFO, logger="mse_simulator")
    accounts = write_tokens(tmp_path / "tokens", 1)
    exchange = Exchange()
    exchange.run = run_body(accounts, ["NOISE"])
    quote_id = exchange.open_order(next(iter(accounts.values())))
    # The answer about the market takes longer than the trading loop takes to stop.
    exchange.phase_delay = 0.1
    seen: list[int] = []

    def finish() -> None:
        exchange.state, exchange.refuse = state, "RUN_FINISHED"
        seen.append(len(exchange.beats))

    async def scenario() -> Runner:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            runner = Runner(settings(tmp_path / "tokens", market_poll_seconds=60.0), http)
            await follow_until(
                runner,
                exchange,
                lambda: len(exchange.placed) >= 2 and len(exchange.beats) >= 2,
                finish,
            )
            return runner

    runner = asyncio.run(scenario())
    assert len(exchange.beats) == seen[0] + 1, "the refusal is heard once and not asked again"
    assert quote_id not in exchange.canceled
    assert runner.activity.errors == 0 and runner.activity.stop_failures == 0
    assert len(closed_lines(caplog)) == (1 if closed else 0)
    assert len(warnings(caplog)) == (0 if closed else 1)
    assert all("lease lost (RUN_FINISHED)" in line for line in warnings(caplog))


def test_a_stopping_run_taken_up_in_a_closed_market_is_ended_without_settling(
    tmp_path, caplog
) -> None:
    caplog.set_level(logging.INFO, logger="mse_simulator")
    accounts = write_tokens(tmp_path / "tokens", 2)
    exchange = Exchange()
    exchange.desired = "STOPPED"
    exchange.run = run_body(accounts, ["NOISE", "NOISE"], desiredState="STOPPED")
    exchange.state, exchange.revoked, exchange.refuse_stop = "CLOSED", True, "RUN_FINISHED"

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            runner = Runner(settings(tmp_path / "tokens"), http)
            await asyncio.wait_for(runner.follow(assigned(exchange.run)), 5)

    asyncio.run(scenario())
    assert exchange.bot_calls == 0, "not even a look at the bots' orders"
    assert exchange.phases == 1, "known to be closed, so the refused beat asks nothing more"
    assert [beat["stopped"] for beat in exchange.beats] == [False, True]
    assert len(closed_lines(caplog)) == 1 and warnings(caplog) == []


def test_a_close_while_a_stop_is_settled_costs_one_refused_read_per_bot(tmp_path, caplog) -> None:
    caplog.set_level(logging.INFO, logger="mse_simulator")
    accounts = write_tokens(tmp_path / "tokens", 2)
    exchange = Exchange()
    exchange.desired = "STOPPED"
    exchange.run = run_body(accounts, ["NOISE", "NOISE"], desiredState="STOPPED")
    exchange.revoked, exchange.refuse_stop = True, "RUN_FINISHED"

    async def handle(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/v1/orders":
            # The close lands after the worker's last look at the market, as it starts to settle.
            exchange.state = "CLOSED"
        return await exchange.handle(request)

    async def scenario() -> Runner:
        async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as http:
            runner = Runner(settings(tmp_path / "tokens"), http)
            await asyncio.wait_for(runner.follow(assigned(exchange.run)), 5)
            return runner

    runner = asyncio.run(scenario())
    assert sorted(exchange.refused) == sorted(
        (value, "/api/v1/orders") for value in accounts.values()
    )
    assert runner.activity.stop_failures == 2, "what could not be read is still reported"
    assert [beat["stopped"] for beat in exchange.beats] == [False, True], "confirmed once"
    assert len(closed_lines(caplog)) == 1 and warnings(caplog) == []


def test_commands_unanswered_at_a_close_are_given_up_after_a_limit(tmp_path, monkeypatch) -> None:
    monkeypatch.setattr(runner_module, "CLOSED_DRAIN_SECONDS", 0.1)
    accounts = write_tokens(tmp_path / "tokens", 2)
    exchange = Exchange()
    exchange.orders_down = True
    exchange.run = run_body(accounts, ["NOISE", "NOISE"])

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            # Retries wait up to five seconds; with this sleep they come round at once.
            runner = Runner(
                settings(tmp_path / "tokens", market_poll_seconds=0.02), http, sleep=soon
            )
            await follow_until(
                runner,
                exchange,
                # Both bots are sending the same order again and again.
                lambda: exchange.order_attempts >= 6,
                lambda: setattr(exchange, "state", "CLOSED"),
            )
            assert not runner._tasks and not any(bot.busy for bot in runner._bots.values())
            attempts = exchange.order_attempts
            await asyncio.sleep(0.1)
            assert exchange.order_attempts == attempts, "no retry outlives the run"

    asyncio.run(scenario())
    assert exchange.placed == []
    assert exchange.beats[-1]["stopped"] is True


def test_with_no_run_and_a_closed_market_the_worker_only_asks_for_its_assignment(
    tmp_path, caplog
) -> None:
    caplog.set_level(logging.INFO, logger="mse_simulator")
    write_tokens(tmp_path / "tokens", 1)
    exchange = Exchange()
    exchange.close()

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            stop = asyncio.Event()
            worker = asyncio.create_task(Runner(settings(tmp_path / "tokens"), http).run(stop))
            await until(lambda: exchange.assignments >= 5)
            stop.set()
            await asyncio.wait_for(worker, 2)

    asyncio.run(scenario())
    assert (exchange.phases, exchange.polls, exchange.bot_calls, exchange.beats) == (0, 0, 0, [])
    assert caplog.records == []


def test_a_finished_run_still_assigned_in_a_closed_market_is_left_without_a_word(
    tmp_path, caplog, monkeypatch
) -> None:
    caplog.set_level(logging.INFO, logger="mse_simulator")
    read: list[str] = []
    monkeypatch.setattr(runner_module, "load_token", lambda directory, ref: read.append(ref))
    accounts = write_tokens(tmp_path / "tokens", 1)
    exchange = Exchange()
    run = run_body(accounts, ["NOISE"])
    exchange.close()
    exchange.run = run

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            runner = Runner(settings(tmp_path / "tokens"), http)
            await asyncio.wait_for(runner.follow(assigned(run)), 5)

    asyncio.run(scenario())
    assert (len(exchange.beats), exchange.phases, exchange.bot_calls) == (1, 1, 0)
    assert read == [] and caplog.records == []


def test_a_halt_pauses_new_orders_until_the_resume_and_the_run_goes_on(tmp_path) -> None:
    accounts = write_tokens(tmp_path / "tokens", 2)
    exchange = Exchange()
    exchange.run = run_body(accounts, ["NOISE", "NOISE"])
    quote_id = exchange.open_order(next(iter(accounts.values())))

    async def scenario() -> None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            runner = Runner(settings(tmp_path / "tokens", market_poll_seconds=0.02), http)
            task = asyncio.create_task(runner.follow(assigned(exchange.run)))
            await until(lambda: len(exchange.placed) >= 2)
            exchange.state = "HALTED"
            polls = exchange.polls
            await until(lambda: exchange.polls >= polls + 2)
            await runner.drain()
            placed, steps, beats = len(exchange.placed), runner.activity.steps, len(exchange.beats)
            attempts = exchange.order_attempts
            await until(lambda: runner.activity.steps >= steps + 5 and len(exchange.beats) > beats)
            assert exchange.order_attempts == attempts, "no new order while halted"
            assert not task.done(), "a halt does not end the run"
            assert exchange.canceled == []
            exchange.state = "OPEN"
            await until(lambda: traders(exchange.placed[placed:]) == set(accounts.values()))
            assert runner.activity.unauthorized == 0, "every bot trades again after the resume"
            exchange.desired = "STOPPED"
            await asyncio.wait_for(task, 5)

    asyncio.run(scenario())
    assert quote_id in exchange.canceled, "a stop after a halt settles as ever"
    assert exchange.beats[-1]["stopped"] is True


def test_an_order_turned_away_by_a_halt_not_seen_yet_does_not_retire_its_bot(tmp_path) -> None:
    """The worker's view is up to a poll old, so an order can reach a market that has just been
    halted and be turned away. That is a rejection like any other: only a refused token is final."""
    accounts = write_tokens(tmp_path / "tokens", 2)
    exchange = Exchange()
    exchange.run = run_body(accounts, ["NOISE", "NOISE"])
    both = set(accounts.values())

    async def scenario() -> Runner:
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            # The first view, an open market, stays the worker's view for the whole run.
            late = settings(
                tmp_path / "tokens", market_poll_seconds=60.0, max_data_age_seconds=60.0
            )
            runner = Runner(late, http)
            task = asyncio.create_task(runner.follow(assigned(exchange.run)))
            await until(lambda: traders(exchange.placed) == both)
            exchange.state = "HALTED"
            await until(lambda: all(exchange.turned_away.count(value) >= 2 for value in both))
            assert not task.done() and runner.activity.unauthorized == 0
            placed = len(exchange.placed)
            exchange.state = "OPEN"
            await until(lambda: traders(exchange.placed[placed:]) == both)
            exchange.desired = "STOPPED"
            await asyncio.wait_for(task, 5)
            return runner

    runner = asyncio.run(scenario())
    assert runner.activity.rejected == len(exchange.turned_away) >= 4
    assert runner.activity.unauthorized == 0 and runner.activity.errors == 0
    assert exchange.phases == 1, "a rejection asks nothing about the market"


@pytest.mark.parametrize(
    ("state", "open", "closed"),
    [("OPEN", True, False), ("HALTED", False, False), ("SETUP", False, False)],
)
def test_the_view_keeps_the_markets_phase(state, open, closed) -> None:
    exchange = Exchange()
    exchange.state = state

    async def scenario():
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            return await fetch(http, "http://execution", "http://market", lambda: 0.0)

    market = asyncio.run(scenario())
    assert (market.state, market.open, market.closed) == (state, open, closed)
    assert market.tradable(1.0, 2.0) is open
    assert not UNSEEN.open and not UNSEEN.closed, "before the first poll nothing is known"


def test_a_closed_market_is_known_without_market_data() -> None:
    exchange = Exchange()
    exchange.state = "CLOSED"
    exchange.listing_down = True

    async def scenario():
        async with httpx.AsyncClient(transport=httpx.MockTransport(exchange.handle)) as http:
            return await fetch(http, "http://execution", "http://market", lambda: 5.0)

    market = asyncio.run(scenario())
    assert market.closed and not market.open
    assert not market.tradable(5.0, 2.0)
