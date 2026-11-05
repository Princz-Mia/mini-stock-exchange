"""Paces every bot from one process: shared market view, global rate, bounded calls, one command
per bot at a time."""

import asyncio
import logging
import random
import time
from collections import Counter
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from datetime import datetime
from uuid import UUID

import httpx
import numpy as np

from mse_simulator import market
from mse_simulator.client import TraderClient
from mse_simulator.credentials import load_tokens
from mse_simulator.market import CLOSED, MarketView
from mse_simulator.noise import Cancel, Holdings, NoiseBot, NoiseConfig, OpenOrder, Place
from mse_simulator.settings import Settings

logger = logging.getLogger(__name__)


@dataclass
class Bot:
    client: TraderClient
    strategy: NoiseBot
    busy: bool = False


@dataclass
class Telemetry:
    """Aggregate counts only: no account, order or token in any label."""

    counts: Counter[str] = field(default_factory=Counter)

    def record(self, outcome: str) -> None:
        self.counts[outcome] += 1


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
        self._bots: dict[UUID, Bot] = {}
        self._order: list[UUID] = []
        self._next = 0
        self._market: MarketView = CLOSED
        self._tasks: set[asyncio.Task[None]] = set()
        self.telemetry = Telemetry()

    @property
    def execution_url(self) -> str:
        return str(self._settings.execution_url).rstrip("/")

    def load_bots(self) -> None:
        """Adds any new bot from the secret directory, each with its own generator seeded by the run
        and its account."""
        config = NoiseConfig(
            max_quantity=self._settings.max_order_quantity,
            spread_ticks=self._settings.spread_ticks,
            max_open_orders=self._settings.max_open_orders,
            quote_lifetime=self._settings.quote_lifetime_seconds,
        )
        for account, token in load_tokens(self._settings.token_directory).items():
            if account in self._bots:
                continue
            rng = np.random.default_rng([self._settings.seed, account.int])
            client = TraderClient(
                self._http,
                self.execution_url,
                account,
                token,
                self._in_flight,
                random.Random(account.int ^ self._settings.seed),
                self._sleep,
            )
            self._bots[account] = Bot(client, NoiseBot(rng, config))
            self._order.append(account)
        if self._bots:
            logger.info("%d bots loaded", len(self._bots))

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
        """Starts one step for the next idle bot, round robin. False if every bot is still busy."""
        for _ in range(len(self._order)):
            account = self._order[self._next % len(self._order)]
            self._next += 1
            bot = self._bots[account]
            if not bot.busy:
                bot.busy = True
                task = asyncio.create_task(self._step(bot))
                self._tasks.add(task)
                task.add_done_callback(self._tasks.discard)
                return True
        return False

    async def _step(self, bot: Bot) -> None:
        try:
            now = self._clock()
            tradable = self._market.tradable(now, self._settings.max_data_age_seconds)
            holdings, orders = await self._observe(bot.client, now)
            intent = bot.strategy.decide(self._market, tradable, holdings, orders, now)
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
                self.telemetry.record(
                    "placed" if answer.status == 201 else f"rejected:{answer.code}"
                )
            elif isinstance(intent, Cancel):
                answer = await bot.client.cancel(intent.order_id)
                self.telemetry.record(
                    "canceled" if answer.status == 200 else f"cancel:{answer.code}"
                )
            else:
                self.telemetry.record("idle")
        except (httpx.HTTPError, KeyError, ValueError) as error:
            # A failed read or an unexpected answer skips this step; the next one observes afresh.
            self.telemetry.record(f"error:{type(error).__name__}")
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
        )
        wall = time.time()
        orders = [
            OpenOrder(
                order["orderId"],
                now - (wall - datetime.fromisoformat(order["createdAt"]).timestamp()),
            )
            for order in active
        ]
        return holdings, orders

    async def drain(self) -> None:
        """Lets every started command reach its answer."""
        if self._tasks:
            await asyncio.gather(*self._tasks, return_exceptions=True)

    async def run(self, stop: asyncio.Event) -> None:
        if self._settings.rate == 0:
            logger.info("trading rate is zero; bots stay idle")
            await stop.wait()
            return
        interval = 1.0 / self._settings.rate
        last_poll = last_scan = last_report = float("-inf")
        while not stop.is_set():
            now = self._clock()
            if now - last_scan >= self._settings.token_rescan_seconds:
                self.load_bots()
                last_scan = now
            if now - last_poll >= self._settings.market_poll_seconds:
                await self.refresh_market()
                last_poll = now
            if now - last_report >= 30:
                if self.telemetry.counts:
                    logger.info("activity: %s", dict(sorted(self.telemetry.counts.items())))
                last_report = now
            if self._bots:
                self.tick()
            await self._sleep(interval)
        await self.drain()
