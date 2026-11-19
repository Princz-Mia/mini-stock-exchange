import asyncio
import json
import math
import os
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import httpx
import pytest

from mse_simulator.loadtest import stress


def test_commands_are_planned_evenly_at_the_rate() -> None:
    plan = stress.schedule(200.0, 1.5)

    assert len(plan) == 300
    assert plan[0] == 0.0
    assert plan[1] == pytest.approx(0.005)
    assert plan[-1] == pytest.approx(1.495)
    for rate, seconds in ((0.0, 1.0), (10.0, 0.0), (-1.0, 1.0)):
        with pytest.raises(ValueError):
            stress.schedule(rate, seconds)


def test_each_trader_login_stays_under_a_persons_limit() -> None:
    assert stress.traders(1.0) == 1
    assert stress.traders(8.0) == 1
    assert stress.traders(9.0) == 2
    assert stress.traders(400.0) == 50
    # Three processes of 333.3 a second, 42 logins each.
    assert stress.traders(1000.0) == 126
    assert stress.traders(2000.0) == 250
    for rate in (1.0, 7.5, 399.0, 401.0, 799.0, 1000.0, 1234.5, 2000.0):
        every = stress.processes(rate)
        logins = stress.traders(rate)
        assert logins % every == 0, "each process has as many logins as the others"
        assert rate / logins <= stress.PER_TRADER


def test_each_process_carries_at_most_its_share() -> None:
    assert stress.processes(1.0) == 1
    assert stress.processes(400.0) == 1
    assert stress.processes(401.0) == 2
    assert stress.processes(1000.0) == 3
    assert stress.processes(2000.0) == 5


def test_the_processes_shares_make_up_the_one_plan() -> None:
    whole = stress.schedule(1000.0, 1.0)

    shares = [stress.schedule(1000.0, 1.0, offset, 3) for offset in range(3)]

    assert sorted(at for share in shares for at in share) == whole
    assert shares[1][:2] == [pytest.approx(0.001), pytest.approx(0.004)]
    for offset, every in ((-1, 3), (3, 3), (1, 1)):
        with pytest.raises(ValueError):
            stress.schedule(1000.0, 1.0, offset, every)


OPEN_ORDER_LIMIT = 20


class Exchange:
    """A fake exchange: places get an order id, at most 20 resting per account, and cancels must
    name a placed order. Each answer can be held back, the first cancels can be refused for load,
    and each request is recorded with the session that sent it."""

    def __init__(self, delay: float = 0.0, refuse_cancels: int = 0) -> None:
        self.delay = delay
        self.refuse_cancels = refuse_cancels
        self.requests: list[tuple[str, str, float]] = []
        self.placed: dict[str, str] = {}
        self.open: set[str] = set()
        self.immediate = 0
        self.refused_for_limit = 0
        self.peak_open = 0

    async def __call__(self, request: httpx.Request) -> httpx.Response:
        session = request.headers["X-Session"]
        self.requests.append((session, request.url.path, time.perf_counter()))
        assert request.method == "GET" or request.headers["Idempotency-Key"]
        if self.delay:
            await asyncio.sleep(self.delay)
        if request.method == "GET":
            mine = [order for order in self.open if self.placed[order] == session]
            return httpx.Response(200, json={"orders": [{"orderId": o} for o in mine]})
        if request.url.path == "/api/v1/orders":
            body = json.loads(request.content)
            assert body["side"] == "BUY" and body["quantity"] == 1
            order = f"{session}-{len(self.placed)}"
            self.placed[order] = session
            if body["type"] == "MARKET_PROTECTED":
                # No seller: an IOC buy is canceled at once and never rests.
                assert body["timeInForce"] == "IOC" and body["protectionPrice"] == "1.00"
                self.immediate += 1
                return httpx.Response(201, json={"orderId": order, "state": "CANCELED"})
            assert body == {
                "instrumentId": "instrument",
                "side": "BUY",
                "quantity": 1,
                "type": "LIMIT",
                "timeInForce": "GTC",
                "limitPrice": "1.00",
            }
            if sum(1 for each in self.open if self.placed[each] == session) >= OPEN_ORDER_LIMIT:
                self.refused_for_limit += 1
                return httpx.Response(422, json={"code": "ORDER_LIMIT_EXCEEDED"})
            self.open.add(order)
            self.peak_open = max(self.peak_open, len(self.open))
            return httpx.Response(201, json={"orderId": order, "state": "OPEN"})
        order = request.url.path.split("/")[-2]
        assert self.placed.get(order) == session, "a trader cancels only its own order"
        if self.refuse_cancels:
            self.refuse_cancels -= 1
            return httpx.Response(503, json={})
        self.open.discard(order)
        return httpx.Response(200, json={})


def traders(exchange: Exchange, count: int) -> list[stress.Trader]:
    transport = httpx.MockTransport(exchange)
    return [
        stress.Trader(
            httpx.AsyncClient(
                transport=transport, base_url="http://exchange", headers={"X-Session": f"t{index}"}
            ),
            {},
            "instrument",
        )
        for index in range(count)
    ]


def test_one_process_fires_only_its_share() -> None:
    exchange = Exchange()

    sent = asyncio.run(stress.fire(traders(exchange, 2), 100.0, 1.0, offset=1, every=3))

    assert [each.intended for each in sent] == stress.schedule(100.0, 1.0, 1, 3)
    assert all(each.status in (200, 201) for each in sent)


class Served(BaseHTTPRequestHandler):
    """A fake exchange over real HTTP, for generator processes: signs in a login whose password
    is "pw", places any order and cancels it. Counts what it answered, by username."""

    signed_in: dict[str, str] = {}
    answered: dict[str, int] = {}
    lock = threading.Lock()

    def log_message(self, *args: object) -> None:
        pass

    def answer(self, status: int, body: dict) -> None:
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def session(self) -> str:
        cookie = self.headers.get("Cookie", "")
        return cookie.removeprefix("session=")

    def do_GET(self) -> None:
        if self.path == "/api/v1/auth/csrf":
            self.answer(200, {"headerName": "X-CSRF", "token": "token"})
        elif self.session() not in self.signed_in:
            self.answer(401, {})
        else:
            self.answer(200, {"orders": []})

    def do_POST(self) -> None:
        body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
        if self.path == "/api/v1/auth/login":
            login = json.loads(body)
            if login["password"] != "pw":
                self.answer(401, {})
                return
            session = uuid.uuid4().hex
            with self.lock:
                self.signed_in[session] = login["username"]
            data = b"{}"
            self.send_response(200)
            self.send_header("Set-Cookie", f"session={session}; Path=/")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        with self.lock:
            username = self.signed_in.get(self.session())
            if username is None or self.headers.get("X-CSRF") != "token":
                self.answer(401, {})
                return
            self.answered[username] = self.answered.get(username, 0) + 1
        if self.path == "/api/v1/orders":
            self.answer(201, {"orderId": uuid.uuid4().hex})
        else:
            self.answer(200, {})


@pytest.fixture
def served() -> str:
    Served.signed_in = {}
    Served.answered = {}
    server = ThreadingHTTPServer(("127.0.0.1", 0), Served)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    yield f"http://127.0.0.1:{server.server_address[1]}"
    server.shutdown()
    server.server_close()


def sessions(base_url: str, count: int) -> list[stress.Session]:
    async def signed_in() -> list[stress.Session]:
        traders = [
            await stress.sign_in(base_url, f"stress-{index}", "pw", "instrument")
            for index in range(count)
        ]
        try:
            return [stress.session(trader) for trader in traders]
        finally:
            for trader in traders:
                await trader.client.aclose()

    return asyncio.run(signed_in())


def test_processes_send_the_whole_plan_once_as_their_own_logins(served: str) -> None:
    rate = 801.0
    signed_in = sessions(served, stress.traders(rate))

    sent = stress.fire_in_processes(served, signed_in, rate, 0.25)

    assert stress.processes(rate) == 3
    assert [each.intended for each in sent] == stress.schedule(rate, 0.25)
    assert all(each.status in (200, 201) for each in sent)
    # Every login sent commands, as itself, signed in once before the processes started.
    assert len(Served.signed_in) == len(signed_in)
    assert sorted(Served.answered) == sorted(Served.signed_in.values())
    assert sum(Served.answered.values()) == len(sent)
    # Each login belongs to one process, so none sends more than its process's share of it.
    every = stress.processes(rate)
    share = math.ceil(len(sent) / every) / (len(signed_in) / every)
    assert max(Served.answered.values()) <= math.ceil(share)


def test_a_process_that_fails_ends_the_rate_without_waiting_out_the_others(served: str) -> None:
    rate = 801.0
    signed_in = sessions(served, stress.traders(rate))
    signed_in[-1] = ({"session": "unknown"}, *signed_in[-1][1:])
    began = time.monotonic()

    with pytest.raises(RuntimeError, match="HTTPStatusError"):
        stress.fire_in_processes(served, signed_in, rate, 0.25, ready_timeout=30.0)

    # Released at once, not after the 30 s the others would wait for it.
    assert time.monotonic() - began < 25
    assert sum(Served.answered.values()) == 0


def ends_without_reporting(*args: object) -> None:
    """A generator process that dies at once, as a killed one would: the first ends, the rest run
    as usual."""
    offset = args[4]
    if offset == 0:
        os._exit(3)
    stress.run_share(*args)  # type: ignore[arg-type]


def test_a_process_that_ends_without_reporting_releases_the_others(served: str) -> None:
    rate = 801.0
    signed_in = sessions(served, stress.traders(rate))
    began = time.monotonic()

    with pytest.raises(RuntimeError, match="exit code 3 without reporting"):
        stress.fire_in_processes(
            served, signed_in, rate, 0.25, ready_timeout=30.0, target=ends_without_reporting
        )

    assert time.monotonic() - began < 25
    assert sum(Served.answered.values()) == 0


def test_too_few_logins_for_a_rate_are_refused() -> None:
    session = ({"session": "s"}, {"X-CSRF": "token"}, "instrument")
    with pytest.raises(ValueError, match="126 logins"):
        stress.fire_in_processes("http://unused", [session] * 125, 1000.0, 1.0)


def test_traders_take_turns_and_each_cancels_the_order_it_placed() -> None:
    exchange = Exchange()

    sent = asyncio.run(stress.fire(traders(exchange, 2), 100.0, 1.0))

    assert len(sent) == 100
    assert all(each.status in (200, 201) for each in sent)
    sessions = [session for session, _, _ in exchange.requests]
    assert sessions.count("t0") == 50 and sessions.count("t1") == 50
    for session in ("t0", "t1"):
        paths = [path for who, path, _ in exchange.requests if who == session]
        assert paths[0::2] == ["/api/v1/orders"] * 25
        assert all(path.endswith("/cancel") for path in paths[1::2])
    assert exchange.immediate == 0, "with prompt answers every buy rests and is canceled"
    assert exchange.open == set()


def test_commands_go_at_their_planned_times_however_slow_the_answers() -> None:
    # Each answer takes 200 ms, 20 times the gap between commands: a closed loop would send one
    # command per answer and take 10 s; an open loop keeps the plan.
    exchange = Exchange(delay=0.2)
    began = time.perf_counter()

    sent = asyncio.run(stress.fire(traders(exchange, 5), 100.0, 0.5))

    assert len(sent) == 50
    assert time.perf_counter() - began < 2.0
    assert all(each.latency >= 0.2 for each in sent), "latency counts the whole wait"
    assert max(each.lag for each in sent) < 0.1


def test_slow_answers_never_take_a_login_past_its_open_order_limit() -> None:
    # Answers take 300 ms and one login sends every 10 ms, so about 30 commands are on their way
    # before the first answer: placing a resting buy each time would pass 20 open orders.
    exchange = Exchange(delay=0.3)

    sent = asyncio.run(stress.fire(traders(exchange, 1), 100.0, 0.5))

    assert [each.status for each in sent if each.status not in (200, 201)] == []
    assert exchange.refused_for_limit == 0
    assert exchange.peak_open == stress.MAX_OPEN
    assert exchange.immediate >= 15, "past the cap it sends orders that cannot rest"


def test_a_cancel_that_is_refused_is_sent_again_later() -> None:
    # An account may have only 20 open orders: one left open after each refused cancel would
    # soon fail every place.
    exchange = Exchange(refuse_cancels=3)

    sent = asyncio.run(stress.fire(traders(exchange, 1), 100.0, 0.2))

    assert [each.status for each in sent].count(503) == 3
    # The first order's cancel was refused three times, then sent again until it went through,
    # so at most the last order placed is still open.
    assert len(exchange.open) <= 1


def test_tidy_cancels_every_open_order_a_login_has() -> None:
    exchange = Exchange()
    login = traders(exchange, 1)[0]
    for index in range(3):
        exchange.placed[f"left-{index}"] = "t0"
        exchange.open.add(f"left-{index}")
    exchange.placed["other"] = "t1"
    exchange.open.add("other")
    login.resting.append("left-0")

    asyncio.run(login.tidy())

    assert exchange.open == {"other"}
    assert login.resting == []


def test_tidy_waits_out_refusals_and_gives_up_on_an_order_it_cannot_cancel(monkeypatch) -> None:
    async def no_wait(seconds: float) -> None:
        pass

    monkeypatch.setattr(stress.asyncio, "sleep", no_wait)
    exchange = Exchange(refuse_cancels=2)
    login = traders(exchange, 1)[0]
    exchange.placed["left"] = "t0"
    exchange.open.add("left")

    asyncio.run(login.tidy())

    assert exchange.open == set()

    stuck = Exchange(refuse_cancels=1_000)
    login = traders(stuck, 1)[0]
    stuck.placed["left"] = "t0"
    stuck.open.add("left")
    with pytest.raises(RuntimeError, match="could not be canceled"):
        asyncio.run(login.tidy())


def test_a_command_with_no_answer_is_recorded_as_failed() -> None:
    def refuse(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused", request=request)

    client = httpx.AsyncClient(transport=httpx.MockTransport(refuse), base_url="http://exchange")

    sent = asyncio.run(stress.fire([stress.Trader(client, {}, "instrument")], 50.0, 0.1))

    assert [each.status for each in sent] == [0] * 5


def result(*answers: tuple[float, int], lag: float = 0.001) -> stress.Result:
    """One command per answer, a millisecond apart, each answered after its latency."""
    sent = [
        stress.Sent(index / 1000, index / 1000 + lag, index / 1000 + latency, status)
        for index, (latency, status) in enumerate(answers)
    ]
    return stress.Result(500.0, 1.0, sent)


GOOD = [(0.010, 201)] * 1000


def test_a_rate_that_meets_every_budget() -> None:
    measured = result(*GOOD)

    assert measured.passed and measured.missed() == []
    assert measured.answered_rate == 1000.0
    assert measured.latency(0.99) == pytest.approx(0.010)
    assert measured.overloaded == 0 and measured.failed == 0


@pytest.mark.parametrize(
    ("answers", "lag", "missed"),
    [
        ([(0.010, 201)] * 940 + [(0.300, 201)] * 60, 0.001, "p95 within 150 ms"),
        ([(0.010, 201)] * 985 + [(0.800, 201)] * 15, 0.001, "p99 within 500 ms"),
        ([(0.010, 201)] * 998 + [(0.010, 429)] * 2, 0.001, "refusals and failures below 0.1%"),
        ([(0.010, 201)] * 998 + [(0.010, 0)] * 2, 0.001, "refusals and failures below 0.1%"),
        ([(0.010, 201)] * 998 + [(0.010, 409)] * 2, 0.001, "refusals and failures below 0.1%"),
        ([(0.100, 201)] * 1000, 0.080, "the generator kept up"),
    ],
)
def test_each_budget_can_fail_a_rate_alone(answers, lag, missed) -> None:
    measured = result(*answers, lag=lag)

    assert not measured.passed
    assert measured.missed() == [missed]


def test_answers_below_the_planned_share_fail_a_rate() -> None:
    measured = result(*([(0.010, 201)] * 800 + [(0.010, 503)] * 200))

    assert measured.overloaded == 200
    assert "at least 90% answered" in measured.missed()


def test_the_warm_up_is_left_out_and_times_count_from_its_end() -> None:
    sent = [stress.Sent(at, at + 0.001, at + 0.02, 201) for at in (0.0, 4.9, 5.0, 6.5)]

    measured = stress.measured(100.0, 5.0, 2.0, sent)

    assert [each.intended for each in measured.sent] == [0.0, pytest.approx(1.5)]
    assert measured.planned == 2
    assert measured.latency(0.5) == pytest.approx(0.02)


def test_the_highest_passing_rate_is_the_last_before_the_first_failure() -> None:
    good, bad = result(*GOOD), result(*([(0.010, 201)] * 998 + [(0.010, 429)] * 2))

    assert stress.highest_passing([good, good, bad, good]) is not None
    assert stress.highest_passing([bad, good]) is None
    assert stress.highest_passing([]) is None
    assert stress.highest_passing([good, bad, good]) is good


def test_the_report_names_the_highest_rate_and_each_rate() -> None:
    good = stress.Result(250.0, 1.0, result(*GOOD).sent)
    bad = stress.Result(500.0, 1.0, result(*([(0.010, 201)] * 998 + [(0.010, 429)] * 2)).sent)

    text = "\n".join(stress.report([good, bad], [750.0, 1000.0]))

    assert "**Highest rate that met every budget: 250 commands a second.**" in text
    assert "| 250/s | 1 | 32 | 1,000 | 1,000.0/s | 0 | 0 | 10.0 ms |" in text
    assert "| 500/s | 2 | 64 | 1,000 | 998.0/s | 2 | 0 |" in text
    assert "no: refusals and failures below 0.1%" in text
    assert "Not run, after a rate missed a budget: 750/s, 1,000/s." in text
    assert "Could not be measured" not in text
    assert "Answers other than 2xx, by status: 500/s: 429 x 2." in text
    mixed = result(*([(0.010, 201)] * 990 + [(0.010, 422)] * 7 + [(0.010, 0)] * 3))
    assert mixed.by_status() == {0: 3, 422: 7}
    assert "500/s: none x 3, 422 x 7." in "\n".join(stress.report([mixed], []))

    broke = "\n".join(stress.report([good], [], "500/s (ConnectError: refused)"))
    assert "Could not be measured, which ended the run: 500/s (ConnectError: refused)." in broke
