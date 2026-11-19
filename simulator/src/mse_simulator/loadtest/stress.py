"""The load test's open-loop stress mode: commands sent at fixed times, whatever the exchange
answers, and each command's latency counted from when it was meant to go. A closed loop, like the
bots, waits for each answer before its next step, so when the exchange slows down it also sends
less and its latency hides the wait; an open loop does not. Trader sends the commands and fire
keeps the schedule; fire_in_processes splits a rate's schedule across processes, since one Python
process keeps a schedule only up to about 800 commands a second; the verdicts are pure functions
over what was sent and answered."""

import asyncio
import math
import multiprocessing
import queue
import threading
import time
import uuid
from collections.abc import Callable
from dataclasses import astuple, dataclass

import httpx

from mse_simulator.loadtest import stats

# A person's account may send 10 trading commands a second; each trader login stays well under it.
PER_TRADER = 8
# Commands one login may have in flight: four seconds of its commands, far past the latency budgets,
# so a slow exchange is still sent the plan until answers take longer than that.
IN_FLIGHT = 32
# Resting buys one login keeps open or on their way, well under the 20 an account may have open.
MAX_OPEN = 10
# Answers that mean the exchange refused the load or did not answer in time (504: the command was
# admitted and its outcome is unknown), not that the command was wrong.
OVERLOAD = frozenset({429, 503, 504})
# Answers to a cancel after which the order is no longer open.
GONE = frozenset({200, 404, 409})
P95_BUDGET = 0.150
P99_BUDGET = 0.500
FAILURE_BUDGET = 0.001
ANSWERED_SHARE = 0.9
# The generator itself may start a command this late at the 99th percentile; later, and a missed
# budget may be the generator's, not the exchange's.
GENERATOR_LAG_BUDGET = 0.050
# Commands a second one generator process carries. One process starts falling behind its schedule
# somewhere between 800 and 1,000 a second on a desktop; half of that leaves it room.
PER_PROCESS = 400
# How long the processes of one rate wait for each other to be ready before the rate is given up.
READY_TIMEOUT = 300.0


def processes(rate: float) -> int:
    """How many generator processes share the rate, each carrying at most PER_PROCESS."""
    return max(1, math.ceil(rate / PER_PROCESS))


def traders(rate: float) -> int:
    """How many trader logins carry the rate: each process's share of it split across its own
    logins, each login under its own limit."""
    every = processes(rate)
    return every * max(1, math.ceil(rate / every / PER_TRADER))


def schedule(rate: float, seconds: float, offset: int = 0, every: int = 1) -> list[float]:
    """When each command is meant to go, in seconds from the start: evenly, at the rate. With
    several processes, one process's share: the commands at offset, offset + every, and so on."""
    if rate <= 0 or seconds <= 0:
        raise ValueError("a positive rate and duration")
    if not 0 <= offset < every:
        raise ValueError("an offset from 0 to every - 1")
    return [index / rate for index in range(offset, round(rate * seconds), every)]


@dataclass(frozen=True)
class Sent:
    """One command, with times in seconds from the start: when it was meant to go, when the
    generator sent it, when its answer came, and the answer's status (0 for no answer)."""

    intended: float
    started: float
    answered: float
    status: int

    @property
    def latency(self) -> float:
        return self.answered - self.intended

    @property
    def lag(self) -> float:
        return self.started - self.intended


class Trader:
    """One trader login's commands, alternating: a buy of one share at 1.00, which rests far below
    the market and never fills, then a cancel of it.

    An account may have only 20 open orders. When answers are slow, commands overlap and a login
    has no known order to cancel, so it places again; it keeps at most MAX_OPEN resting buys open
    or on their way, and past that sends a protected market buy (IOC, at most 1.00), which finds no
    seller and is canceled at once without resting. A cancel that is not answered leaves its order
    to cancel again."""

    def __init__(self, client: httpx.AsyncClient, csrf: dict[str, str], instrument: str) -> None:
        self.client = client
        self.csrf = csrf
        self.instrument = instrument
        self.resting: list[str] = []
        # Resting buys sent and not yet answered.
        self.placing = 0

    async def send(self, intended: float, clock: Callable[[], float]) -> Sent:
        started = clock()
        headers = self.csrf | {"Idempotency-Key": str(uuid.uuid4())}
        status = 0
        try:
            if self.resting:
                order = self.resting.pop()
                try:
                    response = await self.client.post(
                        f"/api/v1/orders/{order}/cancel", headers=headers
                    )
                    status = response.status_code
                finally:
                    # Canceled (200), unknown (404) or no longer open (409) are final; after any
                    # other answer, or none, the order may still be open.
                    if status not in GONE:
                        self.resting.insert(0, order)
            elif self.placing < MAX_OPEN:
                self.placing += 1
                try:
                    response = await self.client.post(
                        "/api/v1/orders", json=self.order(resting=True), headers=headers
                    )
                    status = response.status_code
                    if status == 201:
                        self.resting.append(response.json()["orderId"])
                finally:
                    self.placing -= 1
            else:
                response = await self.client.post(
                    "/api/v1/orders", json=self.order(resting=False), headers=headers
                )
                status = response.status_code
        except httpx.HTTPError:
            pass
        return Sent(intended, started, clock(), status)

    def order(self, resting: bool) -> dict:
        """A buy of one share at 1.00: a limit order that rests, or a protected market order that
        is canceled at once."""
        body = {"instrumentId": self.instrument, "side": "BUY", "quantity": 1}
        if resting:
            return body | {"type": "LIMIT", "timeInForce": "GTC", "limitPrice": "1.00"}
        return body | {"type": "MARKET_PROTECTED", "timeInForce": "IOC", "protectionPrice": "1.00"}

    async def tidy(self) -> None:
        """Cancels every order this login still has open, such as one whose place timed out but
        committed, so each rate starts with none. Refusals for load are waited out."""
        listed = await self.client.get("/api/v1/orders", params={"status": "active"})
        listed.raise_for_status()
        for order in [each["orderId"] for each in listed.json()["orders"]]:
            for _ in range(10):
                response = await self.client.post(
                    f"/api/v1/orders/{order}/cancel",
                    headers=self.csrf | {"Idempotency-Key": str(uuid.uuid4())},
                )
                if response.status_code in GONE:
                    break
                await asyncio.sleep(1.0)
            else:
                raise RuntimeError(f"order {order} could not be canceled: {response.status_code}")
        self.resting.clear()


# A signed-in login, as a generator process takes it: its cookies, its CSRF header and its
# instrument.
Session = tuple[dict[str, str], dict[str, str], str]


def connections(
    base_url: str, timeout: float = 15.0, cookies: dict[str, str] | None = None
) -> httpx.AsyncClient:
    """A login's own connections, so its cookie stays its own."""
    return httpx.AsyncClient(
        base_url=base_url,
        timeout=timeout,
        cookies=cookies,
        limits=httpx.Limits(max_connections=IN_FLIGHT, max_keepalive_connections=IN_FLIGHT),
    )


def session(trader: Trader) -> Session:
    """What another process needs to send as this signed-in trader."""
    return dict(trader.client.cookies), dict(trader.csrf), trader.instrument


async def sign_in(
    base_url: str, username: str, password: str, instrument: str, timeout: float = 15.0
) -> Trader:
    """A trader's own session, with its own connections, so its cookie and CSRF token stay its
    own."""
    client = connections(base_url, timeout)
    csrf = (await client.get("/api/v1/auth/csrf")).json()
    login = await client.post(
        "/api/v1/auth/login",
        json={"username": username, "password": password},
        headers={csrf["headerName"]: csrf["token"]},
    )
    login.raise_for_status()
    # Login rotates the token; it then stays the same for the session.
    csrf = (await client.get("/api/v1/auth/csrf")).json()
    return Trader(client, {csrf["headerName"]: csrf["token"]}, instrument)


async def fire(
    traders: list[Trader],
    rate: float,
    seconds: float,
    clock: Callable[[], float] = time.perf_counter,
    offset: int = 0,
    every: int = 1,
) -> list[Sent]:
    """Sends each command of the schedule (or of one process's share of it) at its planned time,
    round-robin across the traders, without waiting for earlier answers; returns when every
    command has been answered or has failed, in planned order.

    A finished command is kept only as a tuple of numbers, which Python's collector stops
    tracking, and its task is let go: kept whole, ten minutes of commands made each full
    collection walk hundreds of thousands of objects, pausing the schedule for half a second."""
    plan = schedule(rate, seconds, offset, every)
    start = clock()

    def since() -> float:
        return clock() - start

    answered: list[tuple[float, float, float, int]] = []
    errors: list[BaseException] = []
    pending: set[asyncio.Task[Sent]] = set()

    def finished(task: asyncio.Task[Sent]) -> None:
        pending.discard(task)
        if task.cancelled():
            errors.append(asyncio.CancelledError())
        elif (error := task.exception()) is not None:
            errors.append(error)
        else:
            sent = task.result()
            answered.append((sent.intended, sent.started, sent.answered, sent.status))

    for index, at in enumerate(plan):
        delay = at - since()
        if delay > 0:
            await asyncio.sleep(delay)
        task = asyncio.create_task(traders[index % len(traders)].send(at, since))
        pending.add(task)
        task.add_done_callback(finished)
    while pending:
        await asyncio.wait(set(pending))
    if errors:
        raise errors[0]
    return [Sent(*fields) for fields in sorted(answered)]


def run_share(
    base_url: str,
    sessions: list[Session],
    rate: float,
    seconds: float,
    offset: int,
    every: int,
    ready: threading.Barrier,
    out: multiprocessing.Queue[tuple[int, str, object]],
    ready_timeout: float = READY_TIMEOUT,
) -> None:
    """One generator process's share of a rate: cancels its logins' open orders, waits for the
    other processes, then fires its share of the schedule. Puts its commands on out as tuples of
    Sent's fields, or the error that stopped it."""

    async def share() -> list[Sent]:
        gate = asyncio.Semaphore(8)
        traders = [
            Trader(connections(base_url, cookies=cookies), csrf, instrument)
            for cookies, csrf, instrument in sessions
        ]

        async def tidy(trader: Trader) -> None:
            async with gate:
                await trader.tidy()

        try:
            await asyncio.gather(*(tidy(trader) for trader in traders))
            await asyncio.to_thread(ready.wait, ready_timeout)
            return await fire(traders, rate, seconds, offset=offset, every=every)
        finally:
            await asyncio.gather(*(trader.client.aclose() for trader in traders))

    try:
        sent = asyncio.run(share())
    except BaseException as error:  # noqa: BLE001 - the coordinator reports it
        ready.abort()
        out.put((offset, "error", f"{type(error).__name__}: {error}"))
        return
    out.put((offset, "ok", [astuple(each) for each in sent]))


def fire_in_processes(
    base_url: str,
    sessions: list[Session],
    rate: float,
    seconds: float,
    ready_timeout: float = READY_TIMEOUT,
    target: Callable[..., None] = run_share,
) -> list[Sent]:
    """Fires a rate from processes(rate) processes, each with its own signed-in logins and every
    processes(rate)-th command of the one schedule, started together; returns the commands of
    all of them, in planned order. Times count from each process's start, which a barrier
    aligns to within milliseconds. A process that ends without reporting releases the others,
    and the rate fails with its exit code."""
    every = processes(rate)
    needed = traders(rate)
    if len(sessions) < needed:
        raise ValueError(f"{needed} logins carry {rate:,.0f}/s; {len(sessions)} given")
    per = needed // every
    context = multiprocessing.get_context("spawn")
    ready = context.Barrier(every)
    out = context.Queue()
    workers = [
        context.Process(
            target=target,
            args=(
                base_url,
                sessions[k * per : (k + 1) * per],
                rate,
                seconds,
                k,
                every,
                ready,
                out,
                ready_timeout,
            ),
            daemon=True,
        )
        for k in range(every)
    ]
    sent: list[Sent] = []
    errors: list[str] = []
    reported: set[int] = set()
    # Ended without reporting at the last look: a process puts its answer before it ends, so one
    # still unreported a second later never will.
    ended: set[int] = set()
    deadline = time.monotonic() + ready_timeout + seconds + 120
    try:
        for worker in workers:
            worker.start()
        while len(reported) < every:
            try:
                offset, outcome, value = out.get(timeout=1.0)
            except queue.Empty:
                now_ended = {
                    k for k, w in enumerate(workers) if k not in reported and w.exitcode is not None
                }
                lost = now_ended & ended
                ended = now_ended - lost
                if lost:
                    ready.abort()
                    errors += [
                        f"generator process {k} ended with exit code {workers[k].exitcode} "
                        "without reporting"
                        for k in sorted(lost)
                    ]
                    reported |= lost
                elif time.monotonic() > deadline:
                    ready.abort()
                    errors.append("a generator process gave no answer")
                    break
                continue
            reported.add(offset)
            if outcome == "ok":
                sent.extend(Sent(*fields) for fields in value)
            else:
                errors.append(str(value))
    finally:
        for worker in workers:
            worker.join(timeout=30)
            if worker.is_alive():
                worker.terminate()
    if errors:
        raise RuntimeError("; ".join(errors))
    return sorted(sent, key=lambda each: each.intended)


@dataclass(frozen=True)
class Result:
    """One rate, measured over the commands planned after its warm-up."""

    rate: float
    seconds: float
    sent: list[Sent]

    @property
    def planned(self) -> int:
        return len(self.sent)

    @property
    def ok(self) -> list[Sent]:
        return [each for each in self.sent if 200 <= each.status < 300]

    @property
    def overloaded(self) -> int:
        return sum(1 for each in self.sent if each.status in OVERLOAD)

    @property
    def failed(self) -> int:
        """No answer, a server error, or a refusal the load test did not expect."""
        return self.planned - len(self.ok) - self.overloaded

    def by_status(self) -> dict[int, int]:
        """Commands not answered with 2xx, by status (0 for no answer)."""
        counts: dict[int, int] = {}
        for each in self.sent:
            if not 200 <= each.status < 300:
                counts[each.status] = counts.get(each.status, 0) + 1
        return dict(sorted(counts.items()))

    @property
    def answered_rate(self) -> float:
        return len(self.ok) / self.seconds

    def latency(self, fraction: float) -> float | None:
        return stats.nearest_rank([each.latency for each in self.ok], fraction)

    def within(self, edge: float) -> float:
        ok = self.ok
        return sum(1 for each in ok if each.latency <= edge) / len(ok) if ok else 0.0

    @property
    def generator_lag(self) -> float | None:
        return stats.nearest_rank([each.lag for each in self.sent], 0.99)

    def verdicts(self) -> dict[str, bool]:
        lag = self.generator_lag
        return {
            "p95 within 150 ms": self.within(P95_BUDGET) >= 0.95,
            "p99 within 500 ms": self.within(P99_BUDGET) >= 0.99,
            "refusals and failures below 0.1%": self.planned > 0
            and (self.overloaded + self.failed) / self.planned < FAILURE_BUDGET,
            f"at least {ANSWERED_SHARE:.0%} answered": self.planned > 0
            and len(self.ok) >= ANSWERED_SHARE * self.planned,
            "the generator kept up": lag is not None and lag <= GENERATOR_LAG_BUDGET,
        }

    @property
    def passed(self) -> bool:
        return all(self.verdicts().values())

    def missed(self) -> list[str]:
        return [name for name, met in self.verdicts().items() if not met]


def measured(rate: float, warmup: float, seconds: float, sent: list[Sent]) -> Result:
    """The commands planned after the warm-up, with their times from the warm-up's end."""
    return Result(
        rate,
        seconds,
        [
            Sent(
                each.intended - warmup,
                each.started - warmup,
                each.answered - warmup,
                each.status,
            )
            for each in sent
            if each.intended >= warmup
        ],
    )


def highest_passing(results: list[Result]) -> Result | None:
    """The highest rate that met every budget, up to the first that did not."""
    best = None
    for each in results:
        if not each.passed:
            break
        best = each
    return best


def report(results: list[Result], skipped: list[float], broke: str | None = None) -> list[str]:
    """The stress table; broke names a rate that could not be measured, which ended the run."""

    def ms(value: float | None) -> str:
        return "n/a" if value is None else f"{value * 1000:.1f} ms"

    best = highest_passing(results)
    lines = [
        "## Open-loop stress",
        "",
        "**Highest rate that met every budget: "
        + ("none" if best is None else f"{best.rate:,.0f} commands a second")
        + ".**",
        "",
        "| Rate | Processes | Traders | Planned | Answered | Refused or timed out | Failed | p50 | "
        "p95 | p99 | Generator lag p99 | Met |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    for each in results:
        met = "yes" if each.passed else "no: " + ", ".join(each.missed())
        lines.append(
            f"| {each.rate:,.0f}/s | {processes(each.rate)} | {traders(each.rate)} | "
            f"{each.planned:,} | "
            f"{each.answered_rate:,.1f}/s | {each.overloaded:,} | {each.failed:,} | "
            f"{ms(each.latency(0.5))} | {ms(each.latency(0.95))} | {ms(each.latency(0.99))} | "
            f"{ms(each.generator_lag)} | {met} |"
        )
    unanswered = [
        f"{each.rate:,.0f}/s: "
        + ", ".join(f"{status or 'none'} x {count:,}" for status, count in each.by_status().items())
        for each in results
        if each.by_status()
    ]
    if unanswered:
        lines += ["", "Answers other than 2xx, by status: " + "; ".join(unanswered) + "."]
    if broke:
        lines += ["", f"Could not be measured, which ended the run: {broke}."]
    if skipped:
        lines += [
            "",
            "Not run, after a rate missed a budget: "
            + ", ".join(f"{rate:,.0f}/s" for rate in skipped)
            + ".",
        ]
    lines.append("")
    return lines
