"""Follows the bot run Execution assigns: holds its lease, paces its bots from one process, and
settles a stop. One shared market view, one global rate, bounded calls, one command per bot at a
time."""

import asyncio
import contextlib
import logging
import random
import time
from collections.abc import Awaitable, Callable
from dataclasses import asdict, dataclass
from datetime import datetime
from uuid import UUID

import httpx
import numpy as np

from mse_simulator import market
from mse_simulator.client import TraderClient
from mse_simulator.control import STOPPED, Assignment, Beat, ControlClient, Totals
from mse_simulator.credentials import load_token
from mse_simulator.market import CLOSED, MarketView
from mse_simulator.noise import Cancel, Holdings, NoiseBot, NoiseConfig, OpenOrder, Place
from mse_simulator.settings import Settings

logger = logging.getLogger(__name__)

# Trading stops this far into a lease without a renewal, before Execution could let another worker
# take the run.
LEASE_MARGIN = 0.8


@dataclass
class Bot:
    client: TraderClient
    # None while the bot's strategy isn't available: it trades nothing, but a stop still cancels
    # its quotes.
    strategy: NoiseBot | None
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
        self._market: MarketView = CLOSED
        self._tasks: set[asyncio.Task[None]] = set()
        self._stopping = False
        self._lost = False
        self._lease_until = float("-inf")
        self._shutdown = asyncio.Event()
        self.activity = Activity()

    @property
    def execution_url(self) -> str:
        return str(self._settings.execution_url).rstrip("/")

    def start_run(self, run: Assignment) -> None:
        """The run's bots, each with its own generators seeded by the run and its account."""
        self._run = run
        self._bots.clear()
        self._order.clear()
        self._next = 0
        self._stopping = run.desired_state == STOPPED
        self._lost = False
        self._lease_until = float("-inf")
        self.activity = Activity(**asdict(run.totals))
        config = NoiseConfig(
            max_quantity=run.max_order_quantity,
            spread_ticks=self._settings.spread_ticks,
            max_open_orders=self._settings.max_open_orders,
            quote_lifetime=self._settings.quote_lifetime_seconds,
            max_position=run.max_position,
        )
        missing = waiting = 0
        for assigned in run.bots:
            token = load_token(self._settings.token_directory, assigned.credential_ref)
            if token is None:
                missing += 1
                continue
            account = assigned.account_id
            client = TraderClient(
                self._http,
                self.execution_url,
                account,
                token,
                self._in_flight,
                random.Random(account.int ^ run.seed),
                self._sleep,
            )
            strategy = None
            if assigned.strategy == "NOISE":
                strategy = NoiseBot(np.random.default_rng([run.seed, account.int]), config)
            else:
                waiting += 1
            self._bots[account] = Bot(client, strategy)
            self._order.append(account)
        logger.info(
            "run %s: %d bots, %d without a token file, %d whose strategy isn't available yet",
            run.run_id,
            len(self._bots),
            missing,
            waiting,
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

    async def _step(self, bot: Bot, strategy: NoiseBot) -> None:
        assert self._run is not None
        try:
            now = self._clock()
            view = self._market.only(self._run.instrument_ids)
            tradable = view.tradable(now, self._settings.max_data_age_seconds)
            holdings, orders = await self._observe(bot.client, now)
            intent = strategy.decide(view, tradable, holdings, orders, now)
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
            elif isinstance(intent, Cancel):
                answer = await bot.client.cancel(intent.order_id)
                if answer.status == 200:
                    self.activity.canceled += 1
                else:
                    self.activity.rejected += 1
        except httpx.HTTPError, KeyError, ValueError:
            # A failed read or an unexpected answer skips this step; the next one observes afresh.
            self.activity.errors += 1
        finally:
            bot.busy = False

    async def _observe(self, client: TraderClient, now: float) -> tuple[Holdings, list[OpenOrder]]:
        portfolio = await client.portfolio()
        active = await client.active_orders()
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
            )
            for order in active
        ]
        return holdings, orders

    async def drain(self) -> None:
        """Lets every started command reach its final answer."""
        if self._tasks:
            await asyncio.gather(*self._tasks, return_exceptions=True)

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
        )
        if answer is None:
            return None
        if not answer.held:
            if not self._lost:
                logger.warning("run %s: lease lost (%s)", self._run.run_id, answer.code)
            self._lost = True
            return answer
        self._lease_until = sent + self._run.lease_seconds * LEASE_MARGIN
        if answer.desired_state == STOPPED and not self._stopping:
            logger.info("run %s: stop requested", self._run.run_id)
            self._stopping = True
        return answer

    async def _keep_lease(self) -> None:
        assert self._run is not None
        while not self._lost:
            await self._sleep(self._run.lease_seconds / 3)
            await self.beat()

    async def _trade(self, stop: asyncio.Event) -> None:
        assert self._run is not None
        interval = 1.0 / self._run.commands_per_second
        last_poll = last_report = float("-inf")
        while not (stop.is_set() or self._stopping or self._lost):
            now = self._clock()
            if now - last_poll >= self._settings.market_poll_seconds:
                await self.refresh_market()
                last_poll = now
            if now - last_report >= 30:
                logger.info("run %s: %s", self._run.run_id, asdict(self.activity))
                last_report = now
            # Without a renewed lease another worker may take the run, so no new commands.
            if now < self._lease_until:
                self.tick()
            await self._sleep(interval)

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
        keeper = asyncio.create_task(self._keep_lease())
        try:
            await self._trade(self._shutdown)
            await self.drain()
            if self._lost or not self._stopping:
                # Lost, or this process is stopping: the lease lapses and another worker continues.
                return
            await self.settle()
        finally:
            keeper.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await keeper
        await self._confirm()

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
