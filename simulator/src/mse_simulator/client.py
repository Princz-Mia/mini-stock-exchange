"""One bot's authenticated calls to the exchange, with the retry rules every bot shares."""

import asyncio
import logging
import random
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any
from uuid import UUID, uuid4

import httpx

logger = logging.getLogger(__name__)

# Answers that say nothing about whether the command applied, or that it was never admitted.
# Either way the same key and body are safe to send again: the exchange applies a key at most once.
RETRY = frozenset({429, 500, 502, 503, 504})


@dataclass(frozen=True)
class Answer:
    status: int
    body: dict[str, Any]

    @property
    def code(self) -> str | None:
        return self.body.get("code")


class TraderClient:
    """Calls for one bot account. The token goes only into the Authorization header, never a log.

    A command keeps its idempotency key until the exchange gives a final answer, so a retry can't
    apply it twice. The caller makes one call at a time per account, so an unresolved command
    always blocks the next one.
    """

    def __init__(
        self,
        http: httpx.AsyncClient,
        execution_url: str,
        account_id: UUID,
        token: str,
        in_flight: asyncio.Semaphore,
        jitter: random.Random,
        sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
        max_backoff: float = 5.0,
    ) -> None:
        self.account_id = account_id
        self._http = http
        self._base = execution_url
        self._headers = {"Authorization": f"Bearer {token}"}
        self._in_flight = in_flight
        self._jitter = jitter
        self._sleep = sleep
        self._max_backoff = max_backoff

    async def place(self, order: dict[str, Any]) -> Answer:
        return await self._command("/api/v1/orders", order)

    async def cancel(self, order_id: str) -> Answer:
        return await self._command(f"/api/v1/orders/{order_id}/cancel", None)

    async def portfolio(self) -> dict[str, Any]:
        return await self._read("/api/v1/portfolio")

    async def active_orders(self) -> list[dict[str, Any]]:
        return (await self._read("/api/v1/orders?status=active"))["orders"]

    async def _read(self, path: str) -> dict[str, Any]:
        async with self._in_flight:
            response = await self._http.get(f"{self._base}{path}", headers=self._headers)
        response.raise_for_status()
        return response.json()

    async def _command(self, path: str, body: dict[str, Any] | None) -> Answer:
        headers = self._headers | {"Idempotency-Key": str(uuid4())}
        attempt = 0
        while True:
            try:
                async with self._in_flight:
                    response = await self._http.post(
                        f"{self._base}{path}", json=body, headers=headers
                    )
            except httpx.TransportError as error:
                logger.info(
                    "account %s: %s, retrying the same key", self.account_id, type(error).__name__
                )
                response = None
            if response is not None and response.status_code not in RETRY:
                return Answer(response.status_code, _json(response))
            attempt += 1
            await self._sleep(self._backoff(attempt, response))

    def _backoff(self, attempt: int, response: httpx.Response | None) -> float:
        """Full jitter on a doubling cap, but never sooner than the exchange's Retry-After."""
        cap = min(self._max_backoff, 0.2 * 2**attempt)
        wait = self._jitter.uniform(0, cap)
        if response is not None:
            try:
                wait = max(
                    wait, min(self._max_backoff, float(response.headers.get("Retry-After", 0)))
                )
            except ValueError:
                pass
        return wait


def _json(response: httpx.Response) -> dict[str, Any]:
    try:
        body = response.json()
    except ValueError:
        return {}
    return body if isinstance(body, dict) else {}
