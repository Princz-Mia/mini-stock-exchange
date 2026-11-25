"""Follows the bot run Execution assigns: holds its lease, paces its bots from one process, and
settles a stop. One shared market view, one global rate, bounded calls, one command per bot at a
time. A market that closes ends the run with nothing to settle."""

import asyncio
import contextlib
import logging
import time
from collections.abc import Awaitable, Callable
from concurrent.futures import ThreadPoolExecutor
from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path
from uuid import UUID

import httpx
import numpy as np

from mse_simulator import market
from mse_simulator.client import TraderClient
from mse_simulator.control import RUN_FINISHED, STOPPED, Assignment, Beat, ControlClient, Totals
from mse_simulator.credentials import load_token
from mse_simulator.manifest import manifest
from mse_simulator.market import UNSEEN, MarketView
from mse_simulator.market_maker import MarketMaker, MarketMakerConfig
from mse_simulator.momentum import MomentumBot, MomentumConfig, PriceSignal
from mse_simulator.noise import NoiseBot, NoiseConfig
from mse_simulator.pacing import Pacer
from mse_simulator.prices import BASIS_POINT
from mse_simulator.settings import Settings
from mse_simulator.strategy import Cancel, Holdings, OpenOrder, Place, Strategy
from mse_simulator.values import FairValues, View

logger = logging.getLogger(__name__)

# Trading stops this far into a lease without a renewal, before Execution could let another worker
# take the run.
LEASE_MARGIN = 0.8

# Liquidity first: market makers take the first turns of a run, then directional bots.
TURN_ORDER = {"MARKET_MAKER": 0, "MOMENTUM": 1, "NOISE": 1}

# Token files read at once while a run loads: opening a file waits on the disk, not the processor.
TOKEN_READERS = 8

# Bots built between yields to the event loop while a run loads, so heartbeats keep their time.
BUILD_BATCH = 500

# The trading loop's shortest pause: at a high rate it spends the credit of a few milliseconds at a
# time rather than spinning.
MIN_TICK = 0.005

# How long a run that ended with the market waits for the answers of commands already sent. A
# request is answered or times out well within it; one still unanswered then is retrying against an
# exchange that is not there, and nothing it could do matters once the market is closed.
CLOSED_DRAIN_SECONDS = 15.0


@dataclass
class Bot:
    client: TraderClient
    # None for a strategy this worker doesn't know, or once the exchange refuses the bot's token:
    # it trades nothing, but a stop still cancels its quotes.
    strategy: Strategy | None
    busy: bool = False


@dataclass
class Activity:
    """The run's cumulative totals, continuing from what earlier workers reported. Counts only: no
    account, order or token."""

    placed: int = 0
    canceled: int = 0
    rejected: int = 0
    errors: int = 0
    stop_failures: int = 0
    # This worker's own steps and reads, and the bots it stopped stepping because the exchange
    # refused their token, for its log; not reported in the heartbeat.
    steps: int = 0
    reads: int = 0
    unauthorized: int = 0

    def totals(self) -> Totals:
        return Totals(self.placed, self.canceled, self.rejected, self.errors)


class Runner:
    def __init__(
        self,
        settings: Settings,
        http: httpx.AsyncClient,
        clock: Callable[[], float] = time.monotonic,
        sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
    ) -> None:
        self._settings = settings
        self._http = http
        self._clock = clock
        self._sleep = sleep
        self._in_flight = asyncio.Semaphore(settings.max_in_flight)
        self._reads = asyncio.Semaphore(settings.max_reads_in_flight)
        token = settings.simulator_token
        self._control = (
            None
            if token is None
            else ControlClient(http, self.execution_url, token.get_secret_value())
        )
        self._run: Assignment | None = None
        self._bots: dict[UUID, Bot] = {}
        self._order: list[UUID] = []
        self._next = 0
        self._market: MarketView = UNSEEN
        self._signal = self._new_signal()
        self._values = self._new_values(0)
        # How many market makers the current run has built: each takes the next instrument.
        self._makers = 0
        self._tasks: set[asyncio.Task[None]] = set()
        self._stopping = False
        self._lost = False
        # The market closed under the current run: seen on a poll made for this run, never taken
        # from an older view.
        self._closed = False
        # A bot's token was refused since the last look at the market.
        self._refused = False
        self._lease_until = float("-inf")
        self._pacer: Pacer | None = None
        self._shutdown = asyncio.Event()
        self.activity = Activity()
        # What this worker reports with each run, built once: its settings never change.
        self._manifest = manifest(settings)
        # Whether Execution has heard the manifest for the current run: sent until a beat is held.
        self._manifest_heard = False

    @property
    def execution_url(self) -> str:
        return str(self._settings.execution_url).rstrip("/")

    def _new_signal(self) -> PriceSignal:
        return PriceSignal(
            self._settings.momentum_short_polls,
            self._settings.momentum_long_polls,
            self._settings.narrow_book_bp,
        )

    def _new_values(self, seed: int) -> FairValues:
        return FairValues(
            seed,
            self._settings.fair_value_half_life_hours,
            self._settings.fair_value_volatility,
            self._settings.market_poll_seconds,
            self._settings.narrow_book_bp,
        )

    def _view(self, rng: np.random.Generator) -> View:
        """A bot's own idea of the values: the run's, off by a fraction drawn once from its seed."""
        spread = self._settings.view_spread_bp * BASIS_POINT
        return View(
            self._values, float(rng.uniform(-spread, spread)), self._settings.narrow_book_bp
        )

    def _strategy(self, name: str, rng: np.random.Generator, run: Assignment) -> Strategy | None:
        settings = self._settings
        if name == "NOISE":
            return NoiseBot(
                rng,
                NoiseConfig(
                    max_quantity=run.max_order_quantity,
                    spread_bp=settings.noise_spread_bp,
                    max_open_orders=settings.max_open_orders,
                    quote_lifetime=settings.quote_lifetime_seconds,
                    max_position=run.max_position,
                    value_pull=settings.noise_value_pull,
                    narrow_book_bp=settings.narrow_book_bp,
                ),
                self._view(rng),
            )
        if name == "MOMENTUM":
            return MomentumBot(
                rng,
                MomentumConfig(
                    max_quantity=run.max_order_quantity,
                    max_position=run.max_position,
                    threshold=settings.momentum_threshold,
                    cooldown=settings.momentum_cooldown_seconds,
                    order_lifetime=settings.quote_lifetime_seconds,
                    min_ticks=settings.momentum_min_ticks,
                    follow=settings.momentum_follow,
                    reach_bp=settings.momentum_reach_bp,
                    narrow_book_bp=settings.narrow_book_bp,
                ),
                self._signal,
            )
        if name == "MARKET_MAKER":
            place = self._makers
            self._makers += 1
            return MarketMaker(
                rng,
                MarketMakerConfig(
                    quote_size=run.max_order_quantity,
                    max_position=run.max_position,
                    half_spread_bp=settings.maker_half_spread_bp,
                    skew_bp=settings.maker_skew_bp,
                    requote_bp=settings.maker_requote_bp,
                    quote_lifetime=settings.maker_quote_lifetime_seconds,
                ),
                self._view(rng),
                place,
            )
        return None

    def start_run(self, run: Assignment) -> None:
        """Takes up a run with no bots yet: they are loaded once its lease is held."""
        self._run = run
        self._bots.clear()
        self._order.clear()
        self._next = 0
        self._stopping = run.desired_state == STOPPED
        self._lost = False
        self._closed = False
        self._refused = False
        self._lease_until = float("-inf")
        self.activity = Activity(**asdict(run.totals))
        self._signal = self._new_signal()
        self._values = self._new_values(run.seed)
        self._makers = 0
        self._manifest_heard = False

    async def load_bots(self) -> None:
        """The run's bots, each with its own generators seeded by the run and its account, market
        makers first. A run has no size limit, so its token files are read off the event loop, a
        few at a time, and the bots are built in batches: heartbeats keep their time meanwhile."""
        run = self._run
        assert run is not None
        began = self._clock()
        assigned = sorted(run.bots, key=lambda bot: TURN_ORDER.get(bot.strategy, 2))
        directory = self._settings.token_directory
        refs = [bot.credential_ref for bot in assigned]
        tokens = await asyncio.to_thread(_read_tokens, directory, refs)
        missing = unknown = 0
        for index, (bot, token) in enumerate(zip(assigned, tokens, strict=True)):
            if index % BUILD_BATCH == 0 and index > 0:
                await asyncio.sleep(0)
            if token is None:
                missing += 1
                continue
            account = bot.account_id
            client = TraderClient(
                self._http,
                self.execution_url,
                account,
                token,
                self._in_flight,
                account.int ^ run.seed,
                self._sleep,
                reads=self._reads,
            )
            rng = np.random.default_rng([run.seed, account.int])
            strategy = self._strategy(bot.strategy, rng, run)
            if strategy is None:
                unknown += 1
            self._bots[account] = Bot(client, strategy)
            self._order.append(account)
        logger.info(
            "run %s: %d bots in %.1f s, %d without a token file, %d with an unknown strategy",
            run.run_id,
            len(self._bots),
            self._clock() - began,
            missing,
            unknown,
        )

    async def refresh_market(self) -> None:
        try:
            self._market = await market.fetch(
                self._http,
                self.execution_url,
                str(self._settings.market_data_url).rstrip("/"),
                self._clock(),
            )
        except (httpx.HTTPError, KeyError, ValueError) as error:
            # The old view ages out, which pauses new exposure on its own.
            logger.info("market data unavailable: %s", type(error).__name__)
            return
        if self._run is not None:
            self._closed = self._closed or self._market.closed
            # One sample and one step per poll for every bot of the run, not one per bot.
            view = self._market.only(self._run.instrument_ids)
            self._signal.observe(view)
            self._values.observe(view)

    def tick(self) -> bool:
        """Starts one step for the next idle bot with a strategy, round robin. False if there is
        none."""
        for _ in range(len(self._order)):
            account = self._order[self._next % len(self._order)]
            self._next += 1
            bot = self._bots[account]
            if bot.strategy is not None and not bot.busy:
                bot.busy = True
                task = asyncio.create_task(self._step(bot, bot.strategy))
                self._tasks.add(task)
                task.add_done_callback(self._tasks.discard)
                return True
        return False

    def spend(self, pacer: Pacer) -> int:
        """Starts a step for each command the pacer allows, while a bot is idle. A command with no
        idle bot to take it stays in the pacer for the next round. Returns the steps started."""
        started = 0
        while pacer.take():
            if not self.tick():
                pacer.refund()
                break
            started += 1
        return started

    async def _step(self, bot: Bot, strategy: Strategy) -> None:
        assert self._run is not None
        self.activity.steps += 1
        try:
            now = self._clock()
            view = self._market.only(self._run.instrument_ids)
            tradable = view.tradable(now, self._settings.max_data_age_seconds)
            holdings, orders = await self._observe(bot.client, now)
            intent = strategy.decide(view, tradable, holdings, orders, now)
            if intent is None and tradable and self._pacer is not None:
                # Waiting by choice sends nothing, so its command goes to another bot. Not while
                # the data is stale: then the bots pause, at the paced rate of steps.
                self._pacer.refund()
            if isinstance(intent, Place):
                answer = await bot.client.place(
                    {
                        "instrumentId": str(intent.instrument_id),
                        "side": intent.side,
                        "type": "LIMIT",
                        "timeInForce": "GTC",
                        "quantity": intent.quantity,
                        "limitPrice": market.price(intent.limit),
                    }
                )
                if answer.status == 201:
                    self.activity.placed += 1
                else:
                    self.activity.rejected += 1
                    self._unauthorized(bot, answer.status)
            elif isinstance(intent, Cancel):
                answer = await bot.client.cancel(intent.order_id)
                if answer.status == 200:
                    self.activity.canceled += 1
                else:
                    self.activity.rejected += 1
                    self._unauthorized(bot, answer.status)
        except httpx.HTTPStatusError as error:
            self.activity.errors += 1
            self._unauthorized(bot, error.response.status_code)
        except httpx.HTTPError, KeyError, ValueError:
            # A failed read or an unexpected answer skips this step; the next one observes afresh.
            self.activity.errors += 1
        finally:
            bot.busy = False

    def _unauthorized(self, bot: Bot, status: int) -> None:
        """A refused token stays refused: a token is written once and never renewed. So the bot
        takes no more steps, rather than failing at every turn, and the market is looked at before
        any other bot steps: a close revokes every token at once."""
        if status == 401:
            bot.strategy = None
            self._refused = True
            self.activity.unauthorized += 1

    async def _observe(self, client: TraderClient, now: float) -> tuple[Holdings, list[OpenOrder]]:
        portfolio = await client.portfolio()
        active = await client.active_orders()
        self.activity.reads += 2
        holdings = Holdings(
            cash=market.cents(portfolio["cash"]["available"]),
            shares={
                UUID(held["instrumentId"]): held["available"] for held in portfolio["holdings"]
            },
            positions={UUID(held["instrumentId"]): held["total"] for held in portfolio["holdings"]},
        )
        wall = time.time()
        orders = [
            OpenOrder(
                order["orderId"],
                now - (wall - datetime.fromisoformat(order["createdAt"]).timestamp()),
                UUID(order["instrumentId"]),
                order["side"],
                order["workingQuantity"],
                None if order.get("limitPrice") is None else market.cents(order["limitPrice"]),
            )
            for order in active
        ]
        return holdings, orders

    async def drain(self, limit: float | None = None) -> None:
        """Lets every started command reach its final answer. With a limit, a command still
        unanswered after that many seconds is given up."""
        tasks = list(self._tasks)
        if not tasks:
            return
        try:
            async with asyncio.timeout(limit):
                await asyncio.gather(*tasks, return_exceptions=True)
        except TimeoutError:
            # The timeout cancelled them; their steps still have to unwind.
            await asyncio.gather(*tasks, return_exceptions=True)

    async def beat(self, stopped: bool = False) -> Beat | None:
        """Renews the lease and reports the totals. Losing the lease ends the work at once."""
        assert self._control is not None and self._run is not None
        sent = self._clock()
        answer = await self._control.heartbeat(
            self._run.run_id,
            self._settings.worker_id,
            self.activity.totals(),
            len(self._tasks),
            self.activity.stop_failures,
            stopped,
            None if self._manifest_heard else self._manifest,
        )
        if answer is None:
            return None
        if not answer.held:
            first, self._lost = not self._lost, True
            if first and not self._closed:
                if answer.code == RUN_FINISHED:
                    # Finished by the exchange itself, which a close does: ask before calling the
                    # lease lost.
                    await self._check_closed()
                if not self._closed:
                    logger.warning("run %s: lease lost (%s)", self._run.run_id, answer.code)
            return answer
        self._lease_until = sent + self._run.lease_seconds * LEASE_MARGIN
        # Only an accepted beat stored it: a refusal kept as held (RUN_NOT_STOPPING) stored nothing.
        self._manifest_heard = self._manifest_heard or answer.code is None
        if answer.desired_state == STOPPED and not self._stopping:
            logger.info("run %s: stop requested", self._run.run_id)
            self._stopping = True
        return answer

    async def _keep_lease(self) -> None:
        assert self._run is not None
        while not (self._lost or self._closed):
            await self._sleep(self._run.lease_seconds / 3)
            if not self._closed:
                await self.beat()

    async def _trade(self, stop: asyncio.Event) -> None:
        assert self._run is not None
        # The rate counts commands, not steps: see pacing.py. Polled often enough that a high rate
        # is spread over each second rather than sent in bursts.
        self._pacer = pacer = Pacer(self._run.commands_per_second, self._clock())
        interval = max(MIN_TICK, min(1.0 / self._run.commands_per_second, 0.05))
        # The first view before any step; after that the market is polled beside the trading loop,
        # so a slow answer from Market Data never holds the bots' steps back.
        await self.refresh_market()
        poller = asyncio.create_task(self._poll_market(stop))
        last_report = float("-inf")
        try:
            while not (stop.is_set() or self._stopping or self._lost or self._closed):
                if self._refused:
                    # No bot steps into a market that may be gone: one answer settles it.
                    self._refused = False
                    await self._check_closed()
                    continue
                now = self._clock()
                if now - last_report >= 30:
                    logger.info("run %s: %s", self._run.run_id, asdict(self.activity))
                    last_report = now
                # Without a renewed lease another worker may take the run, so no new commands.
                pacer.accrue(self._clock())
                if now < self._lease_until:
                    self.spend(pacer)
                await self._sleep(interval)
        finally:
            poller.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await poller

    async def _poll_market(self, stop: asyncio.Event) -> None:
        while not (stop.is_set() or self._stopping or self._lost or self._closed):
            await self._sleep(self._settings.market_poll_seconds)
            try:
                await self.refresh_market()
            except Exception:
                # The view ages out, which pauses new exposure; the next poll tries again.
                logger.exception("market poll failed")

    async def _check_closed(self) -> None:
        """Asks Execution whether the market closed, between polls. Only the phase: the poll's
        view, and the one sample per poll the bots' signals take from it, stay as they are."""
        try:
            closed = await market.phase(self._http, self.execution_url) == market.CLOSED
        except httpx.HTTPError, KeyError, ValueError, TypeError:
            # Unknown is not closed: the next poll asks again.
            return
        self._closed = self._closed or closed

    async def settle(self) -> None:
        """Cancels every open order of the run's bots, counting each one that can't be cancelled."""
        await asyncio.gather(*(self._cancel_all(bot.client) for bot in self._bots.values()))

    async def _cancel_all(self, client: TraderClient) -> None:
        try:
            orders = await client.active_orders()
        except httpx.HTTPError, KeyError, ValueError:
            self.activity.stop_failures += 1
            return
        for order in orders:
            answer = await client.cancel(order["orderId"])
            if answer.status == 200:
                self.activity.canceled += 1
            else:
                self.activity.stop_failures += 1

    async def follow(self, run: Assignment) -> None:
        """Works on one run until it stops, the lease is lost, or the process stops."""
        self.start_run(run)
        first = await self.beat()
        if first is None or not first.held:
            return
        # Only with the lease: a worker that finds it held reads no token at all.
        keeper = asyncio.create_task(self._keep_lease())
        try:
            await self.load_bots()
            await self._trade(self._shutdown)
            if self._lost:
                # Only the keeper's beat loses the lease here, and it may still be asking whether
                # the market closed: its answer decides how the run ends.
                await keeper
            if not self._closed:
                await self.drain()
                if self._lost or not self._stopping:
                    # Lost, or this process is stopping: the lease lapses and another worker
                    # continues.
                    return
                await self.settle()
        finally:
            keeper.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await keeper
        if not self._closed:
            await self._confirm()
        if self._closed:
            # Also when the close came while a stop was being settled: the confirmation heard it.
            await self._leave()

    async def _leave(self) -> None:
        """Ends a run whose market closed. The exchange has cancelled every order, revoked the
        bots' tokens and ended the run itself, so there is nothing to settle and no cancel to send.
        One last beat reports the totals; a refusal is the expected answer and is not asked
        again."""
        assert self._run is not None
        await self.drain(CLOSED_DRAIN_SECONDS)
        if not self._lost:
            await self.beat(stopped=True)
        logger.info(
            "run %s: the market closed, run ended with nothing to settle, %s",
            self._run.run_id,
            asdict(self.activity),
        )

    async def _confirm(self) -> None:
        assert self._run is not None
        if self._lost:
            return
        for _ in range(5):
            answer = await self.beat(stopped=True)
            if answer is not None:
                if answer.held:
                    logger.info("run %s: stopped, %s", self._run.run_id, asdict(self.activity))
                return
            await self._sleep(1.0)
        logger.warning("run %s: stop not confirmed; the next worker settles it", self._run.run_id)

    async def run(self, stop: asyncio.Event) -> None:
        self._shutdown = stop
        if self._control is None:
            logger.warning("no simulator token; bots stay idle")
            await stop.wait()
            return
        while not stop.is_set():
            try:
                run = await self._control.assignment()
            except (httpx.HTTPError, KeyError, ValueError) as error:
                logger.info("assignment unavailable: %s", type(error).__name__)
                run = None
            if run is not None:
                await self.follow(run)
            with contextlib.suppress(TimeoutError):
                await asyncio.wait_for(stop.wait(), self._settings.control_poll_seconds)


def _read_tokens(directory: Path, refs: list[str]) -> list[str | None]:
    """Each bot's token, or None, in the order given, read by a few threads at once."""
    with ThreadPoolExecutor(TOKEN_READERS, thread_name_prefix="tokens") as pool:
        return list(pool.map(lambda ref: load_token(directory, ref), refs))
