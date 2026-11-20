import asyncio
import json
import time

import httpx
import pytest

from mse_simulator.loadtest import diagnostics, stats, stress


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
    assert stress.traders(1000.0) == 125
    assert stress.traders(2000.0) == 250


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


QUIET = diagnostics.Downstream(
    1_000.0,
    {
        diagnostics.BACKLOG: stats.QueueVerdict(10.0, 12.0, 40.0, False),
        "mse.market.v1": stats.QueueVerdict(3.0, 4.0, 9.0, False),
    },
)


def result(
    *answers: tuple[float, int],
    lag: float = 0.001,
    downstream: diagnostics.Downstream | None = QUIET,
) -> stress.Result:
    """One command per answer, a millisecond apart, each answered after its latency."""
    sent = [
        stress.Sent(index / 1000, index / 1000 + lag, index / 1000 + latency, status)
        for index, (latency, status) in enumerate(answers)
    ]
    return stress.Result(500.0, 1.0, sent, downstream)


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


@pytest.mark.parametrize(
    "downstream",
    [
        None,
        diagnostics.Downstream(1_000.0, {}),
        diagnostics.Downstream(
            1_000.0, {diagnostics.BACKLOG: stats.QueueVerdict(10.0, 12.0, 40.0, False)}
        ),
        diagnostics.Downstream(
            1_000.0,
            {
                diagnostics.BACKLOG: stats.QueueVerdict(10.0, 12.0, 40.0, False),
                "mse.trades.v1": stats.QueueVerdict(3.0, 4_000.0, 4_100.0, True),
            },
        ),
    ],
    ids=["not sampled", "nothing sampled", "no feed sampled", "a feed grew"],
)
def test_publication_and_the_feeds_must_be_seen_keeping_up(downstream) -> None:
    measured = result(*GOOD, downstream=downstream)

    assert measured.missed() == ["publication and the feeds kept up"]


def test_the_backlog_and_lag_after_the_warm_up_are_judged_over_thirds() -> None:
    sampled = diagnostics.Sampled()
    # A backlog built up during the warm-up and drained steadily after it is not growth; a feed
    # that falls behind across the measured window is.
    sampled.backlog = [(float(at), 9_000.0 if at < 30 else 20.0) for at in range(0, 91, 5)]
    sampled.feed_lag = {
        "mse.market.v1": [
            (float(at), 5.0 if at < 50 else 3_000.0 if at >= 70 else 5.0) for at in range(0, 91, 5)
        ]
    }
    sent = [stress.Sent(at / 10, at / 10 + 0.001, at / 10 + 0.01, 201) for at in range(900)]

    measured = stress.measured(500.0, 30.0, 60.0, sent, sampled)

    assert measured.downstream is not None
    assert measured.downstream.allowance == 1_000.0
    assert measured.downstream.peak(diagnostics.BACKLOG) == 20.0, "the warm-up is left out"
    assert measured.downstream.grew() == ["mse.market.v1"]
    assert measured.missed() == ["publication and the feeds kept up"]


def test_samples_after_the_plan_ends_are_left_out() -> None:
    # The backlog grows through the measured minute, then drains while the last answers come in:
    # judged on the plan's window it grew; with the drain counted it would look flat.
    sampled = diagnostics.Sampled()
    sampled.backlog = [
        (float(at), 20.0 if at < 60 else 4_000.0 if at < 95 else 0.0) for at in range(30, 121, 5)
    ]
    sampled.feed_lag = {"mse.market.v1": [(float(at), 3.0) for at in range(30, 121, 5)]}
    sent = [stress.Sent(at / 10, at / 10 + 0.001, at / 10 + 0.01, 201) for at in range(900)]

    measured = stress.measured(500.0, 30.0, 60.0, sent, sampled)

    assert measured.downstream is not None
    assert measured.downstream.grew() == [diagnostics.BACKLOG]
    assert max(at for at, _ in sampled.backlog) == 120.0
    assert measured.downstream.verdicts[diagnostics.BACKLOG].last_minute == 4_000.0


def cost(second: int, command: str) -> float:
    """Seconds each phase takes for one command started in that second: slow before the window
    (30 s to 90 s), slower after it, and inside it rising by a microsecond a second, so only the
    window's own two scrapes give the expected means. A cancel costs a third of a place."""
    if second < 30:
        base = 0.010
    elif second < 90:
        base = 0.003 + (second - 30) * 0.000001
    else:
        base = 0.5
    return base if command == "place" else base / 3


def writer_scrape(at: int) -> dict:
    """Execution's writer timers after at seconds of 30 places and 10 cancels a second, each phase
    but queue_wait taking cost(); queue_wait takes twice that."""
    counts = {"place": 0, "cancel": 0}
    sums = {"place": 0.0, "cancel": 0.0}
    for second in range(at):
        for command, per_second in (("place", 30), ("cancel", 10)):
            counts[command] += per_second
            sums[command] += per_second * cost(second, command)
    lines = []
    for command in ("place", "cancel"):
        for phase in diagnostics.PHASES:
            total = sums[command] * (2 if phase == "queue_wait" else 1)
            lines.append(
                f'mse_writer_{phase}_seconds_count{{command="{command}"}} {counts[command]}'
            )
            lines.append(f'mse_writer_{phase}_seconds_sum{{command="{command}"}} {total}')
    return stats.parse_prometheus("\n".join(lines))


def test_the_writer_and_the_containers_are_taken_over_the_measured_window() -> None:
    sampled = diagnostics.Sampled()
    sampled.execution = [(float(at), writer_scrape(at)) for at in range(0, 121, 5)]
    sampled.cpu_at = {
        "postgres": [(20.0, 190.0), (30.0, 100.0), (60.0, 120.0), (90.0, 80.0), (100.0, 5.0)],
        "web": [(50.0, 30.0)],
    }
    sent = [stress.Sent(at / 10, at / 10 + 0.001, at / 10 + 0.01, 201) for at in range(900)]

    measured = stress.measured(500.0, 30.0, 60.0, sent, sampled, {"postgres": (0.25, 40.0)})

    split = measured.writer
    assert split is not None
    assert split.commands == {"place": 1_800, "cancel": 600}, "the window's own commands"
    # Inside the window a place's phase averages 3.0295 ms (3 ms plus 29.5 us of rise); a cancel's
    # a third of it. Weighted 3:1, a phase is 2.5246 ms; queue_wait twice that.
    place = 3.0295
    phase = (place * 3 + place / 3) / 4
    assert stress.weighted(split, "work") == pytest.approx(phase)
    assert stress.weighted(split, "queue_wait") == pytest.approx(2 * phase)
    assert split.service_ms == pytest.approx(4 * phase), "begin, work, commit and apply"
    assert measured.cpu == {"postgres": (100.0, 120.0), "web": (30.0, 30.0)}, (
        "readings at 30 s and 90 s belong to the window"
    )
    assert measured.throttled == {"postgres": (0.25, 40.0)}

    text = "\n".join(stress.report([measured], []))
    assert "| 500/s | 5.05 ms | 2.52 ms | 2.52 ms | 2.52 ms | 2.52 ms | 10.10 ms | 99/s |" in text
    assert "| 500/s | n/a | 100 / 120, held 25% | n/a | n/a | 30 / 30 |" in text


def test_fractional_rates_keep_their_decimals_in_every_table() -> None:
    assert stress.label(250.0) == "250/s"
    assert stress.label(12.25) == "12.25/s"
    assert stress.label(12.5) == "12.5/s"
    assert stress.label(1500.0) == "1,500/s"
    text = "\n".join(
        stress.report([result(*GOOD), stress.Result(12.25, 1.0, result(*GOOD).sent)], [12.5])
    )
    assert "| 12.25/s | n/a | n/a |" in text
    assert "Not run, after a rate missed a budget: 12.5/s." in text


def test_without_two_scrapes_in_the_window_the_writer_is_not_reported() -> None:
    sampled = diagnostics.Sampled()
    sampled.execution = [(31.0, writer_scrape(31)), (95.0, writer_scrape(95))]
    sent = [stress.Sent(at / 10, at / 10 + 0.001, at / 10 + 0.01, 201) for at in range(900)]

    measured = stress.measured(500.0, 30.0, 60.0, sent, sampled)

    assert measured.writer is None and measured.cpu == {} and measured.throttled == {}
    assert "| 500/s | n/a | n/a | n/a | n/a | n/a | n/a | n/a |" in "\n".join(
        stress.report([measured], [])
    )


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
    good = stress.Result(250.0, 1.0, result(*GOOD).sent, QUIET)
    bad = stress.Result(
        500.0, 1.0, result(*([(0.010, 201)] * 998 + [(0.010, 429)] * 2)).sent, QUIET
    )

    text = "\n".join(stress.report([good, bad], [750.0, 1000.0]))

    assert "**Highest rate that met every budget: 250 commands a second.**" in text
    assert "| 250/s | 32 | 1,000 | 1,000.0/s | 0 | 0 | 10.0 ms |" in text
    assert "| 500/s | 63 | 1,000 | 998.0/s | 2 | 0 |" in text
    assert "no: refusals and failures below 0.1%" in text
    assert "Not run, after a rate missed a budget: 750/s, 1,000/s." in text
    assert "Could not be measured" not in text
    assert "Answers other than 2xx, by status: 500/s: 429 x 2." in text
    assert "| 1.0 ms | 40 | 9 | yes |" in text, "generator lag, backlog peak, feed lag peak"
    assert "fell behind" not in text
    behind = diagnostics.Downstream(
        1_000.0,
        {
            diagnostics.BACKLOG: stats.QueueVerdict(10.0, 6_000.0, 6_500.0, True),
            "mse.market.v1": stats.QueueVerdict(3.0, 4.0, 9.0, False),
        },
    )
    fell = "\n".join(stress.report([stress.Result(750.0, 1.0, good.sent, behind)], []))
    assert (
        "Publication or a feed fell behind: 750/s: grew: publication backlog 10 to 6,000." in fell
    )
    assert "not sampled" not in fell
    unsampled = "\n".join(stress.report([stress.Result(750.0, 1.0, good.sent)], []))
    assert "| n/a | n/a | no: publication and the feeds kept up |" in unsampled
    assert "fell behind" not in unsampled
    assert "Publication and the feeds were not sampled enough to judge: 750/s." in unsampled
    thin = diagnostics.Downstream(1_000.0, QUIET.verdicts, unsampled=("mse.trades.v1",))
    text = "\n".join(stress.report([stress.Result(750.0, 1.0, good.sent, thin)], []))
    assert "not sampled enough to judge: 750/s (not sampled enough: mse.trades.v1)." in text
    mixed = result(*([(0.010, 201)] * 990 + [(0.010, 422)] * 7 + [(0.010, 0)] * 3))
    assert mixed.by_status() == {0: 3, 422: 7}
    assert "500/s: none x 3, 422 x 7." in "\n".join(stress.report([mixed], []))

    broke = "\n".join(stress.report([good], [], "500/s (ConnectError: refused)"))
    assert "Could not be measured, which ended the run: 500/s (ConnectError: refused)." in broke
