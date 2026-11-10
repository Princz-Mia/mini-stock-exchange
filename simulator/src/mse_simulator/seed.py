"""Sets up the demonstration market through the operator API, once, so a fresh stack can trade.

Five instruments at their reference prices, the demo trader funded with USD 100,000.00 and
1,000 shares of each, a population of funded bots, and the market opened. It signs in as the
operator and goes through the same endpoints the operator screen uses.

Each step has an idempotency key derived from its name, so running the seed again repeats the first
answers instead of doing anything twice, and a seed interrupted halfway finishes on the next run. A
market that is already open or halted was set up before, by the seed or by hand, and is left alone.

    python -m mse_simulator.seed

Reads MSE_EXECUTION_URL, MSE_DEMO_SEED (only "true" seeds), MSE_DEMO_OPERATOR_PASSWORD and
MSE_DEMO_BOTS (how many bots, 20 by default).
"""

import logging
import os
import sys
import time
from collections.abc import Callable
from typing import Any
from uuid import NAMESPACE_URL, UUID, uuid5

import httpx

logger = logging.getLogger("mse_simulator.seed")

INSTRUMENTS = {
    "NOVA": "100.00",
    "ORBT": "50.00",
    "HELI": "200.00",
    "QUAD": "25.00",
    "VELO": "80.00",
}
TRADER = "trader"
CASH = "100000.00"
SHARES = 1_000

# Answers that say nothing about whether the command applied: send the same key again.
RETRY = frozenset({429, 500, 502, 503, 504})


class SeedError(RuntimeError):
    pass


def key(step: str) -> str:
    """The same key for the same step on every run."""
    return str(uuid5(NAMESPACE_URL, f"mse-demo-seed:{step}"))


class Operator:
    """An operator session: a cookie, and the CSRF token that goes with it."""

    def __init__(
        self,
        http: httpx.Client,
        password: str,
        sleep: Callable[[float], None] = time.sleep,
        attempts: int = 30,
    ) -> None:
        self._http = http
        self._password = password
        self._sleep = sleep
        self._attempts = attempts
        self._csrf: dict[str, str] = {}

    def sign_in(self) -> None:
        self._refresh_csrf()
        answer = self._http.post(
            "/api/v1/auth/login",
            json={"username": "operator", "password": self._password},
            headers=self._csrf,
        )
        if answer.status_code != 200:
            raise SeedError(f"operator sign-in answered {answer.status_code}")
        # The token rotates with the session at login.
        self._refresh_csrf()

    def market_state(self) -> str:
        return self._http.get("/api/v1/market").raise_for_status().json()["state"]

    def command(self, path: str, step: str, body: dict[str, Any] | None = None) -> dict[str, Any]:
        """Sends a command until it has a final answer, always under the step's own key."""
        headers = {**self._csrf, "Idempotency-Key": key(step)}
        for attempt in range(self._attempts):
            try:
                answer = self._http.post(path, json=body, headers=headers)
            except httpx.TransportError:
                answer = None
            if answer is not None and answer.status_code not in RETRY:
                payload = answer.json() if answer.content else {}
                if answer.status_code >= 400:
                    raise SeedError(f"{step} answered {answer.status_code} {payload.get('code')}")
                return payload
            self._sleep(min(1.0 + attempt, 5.0))
        raise SeedError(f"{step} had no final answer after {self._attempts} attempts")

    def _refresh_csrf(self) -> None:
        token = self._http.get("/api/v1/auth/csrf").raise_for_status().json()
        self._csrf = {token["headerName"]: token["token"]}


def seed(operator: Operator, bots: int) -> str:
    """Sets the market up and opens it, unless it is already running. Returns what it did."""
    operator.sign_in()
    state = operator.market_state()
    if state in {"OPEN", "HALTED"}:
        return f"the market is already {state.lower()}; nothing to do"
    ids: dict[str, UUID] = {}
    for symbol, price in INSTRUMENTS.items():
        created = operator.command(
            "/api/v1/admin/instruments",
            f"instrument:{symbol}",
            {"symbol": symbol, "referencePrice": price},
        )
        ids[symbol] = UUID(created["instrumentId"])
    holdings = [{"instrumentId": str(i), "quantity": SHARES} for i in ids.values()]
    operator.command(
        "/api/v1/admin/accounts",
        f"account:{TRADER}",
        {"username": TRADER, "cash": CASH, "holdings": holdings},
    )
    left = bots
    batch = 0
    while left > 0:
        count = min(left, 100)
        operator.command(
            "/api/v1/admin/bots",
            f"bots:{batch}:{count}",
            {"count": count, "cash": CASH, "holdings": holdings},
        )
        left -= count
        batch += 1
    operator.command("/api/v1/admin/market/open", "open")
    return (
        f"seeded {len(ids)} instruments, the {TRADER} account and {bots} bots,"
        " and opened the market"
    )


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(levelname)s:%(name)s:%(message)s")
    if os.environ.get("MSE_DEMO_SEED", "").lower() != "true":
        logger.info("seeding is off (MSE_DEMO_SEED is not true)")
        return 0
    password = os.environ.get("MSE_DEMO_OPERATOR_PASSWORD", "")
    if not password:
        logger.error("MSE_DEMO_OPERATOR_PASSWORD is not set")
        return 1
    bots = int(os.environ.get("MSE_DEMO_BOTS", "20"))
    url = os.environ.get("MSE_EXECUTION_URL", "http://execution-service:8080")
    with httpx.Client(base_url=url, timeout=10.0) as http:
        try:
            logger.info(seed(Operator(http, password), bots))
        except (SeedError, httpx.HTTPError) as error:
            logger.error("the demo seed failed: %s", error)
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
