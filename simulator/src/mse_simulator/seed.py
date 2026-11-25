"""Sets up the demonstration market through the operator API, once, so a fresh stack can trade.

Five instruments at their reference prices, the demo trader added with USD 100,000.00 and
1,000 shares of each, a population of funded bots, and the market opened. The bots are tagged with
their strategy: a fifth market makers and a fifth momentum, the rest noise, with at least one market
maker (see population.strategy_split). It signs in as the operator and goes through the same
endpoints the operator screen uses.

Each step has an idempotency key derived from its name, so running the seed again repeats the first
answers instead of doing anything twice, and a seed interrupted halfway finishes on the next run. A
market that is already open or halted was set up before, by the seed or by hand, and is left alone.
So is a closed one: starting a new market after a close is the operator's step, not the seed's.

The seed never sets up a simulation an operator started. It acts when no market exists, and it goes
on with a market in SETUP only if that market has no name: one made by the first setup command,
which is how the seed itself makes it. A simulation an operator started has a name, and is theirs
to set up.

    python -m mse_simulator.seed

The trader is added as the operator adds one on the setup screen, with MSE_DEMO_TRADER_PASSWORD. A
stack set up before traders were added that way already has a configured 'trader', which is funded
instead.

Reads MSE_EXECUTION_URL, MSE_DEMO_SEED (only "true" seeds), MSE_DEMO_OPERATOR_PASSWORD,
MSE_DEMO_TRADER_PASSWORD and MSE_DEMO_BOTS (how many bots, 20 by default).
"""

import logging
import os
import sys
import time
from collections.abc import Callable
from typing import Any
from uuid import NAMESPACE_URL, UUID, uuid5

import httpx

from mse_simulator.population import split_text, strategy_split

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
    def __init__(self, message: str, code: str | None = None) -> None:
        super().__init__(message)
        self.code = code


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

    def market(self) -> dict[str, Any]:
        """The market's status: its state, and the name of its simulation if it was given one."""
        return self._http.get("/api/v1/market").raise_for_status().json()

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
                    code = payload.get("code")
                    # Which field was refused, if any: a trader password too short, say.
                    fields = "; ".join(
                        f"{error.get('field')}: {error.get('message')}"
                        for error in payload.get("errors") or []
                    )
                    detail = f" ({fields})" if fields else ""
                    raise SeedError(f"{step} answered {answer.status_code} {code}{detail}", code)
                return payload
            self._sleep(min(1.0 + attempt, 5.0))
        raise SeedError(f"{step} had no final answer after {self._attempts} attempts")

    def _refresh_csrf(self) -> None:
        token = self._http.get("/api/v1/auth/csrf").raise_for_status().json()
        self._csrf = {token["headerName"]: token["token"]}


def seed(operator: Operator, bots: int, trader_password: str) -> str:
    """Sets the market up and opens it, unless it is already running or closed, or an operator
    started it. Returns what it did."""
    operator.sign_in()
    market = operator.market()
    state = market["state"]
    if state in {"OPEN", "HALTED"}:
        return f"the market is already {state.lower()}; nothing to do"
    if state == "CLOSED":
        # Setup commands are refused on a closed market, and a new one is the operator's to start.
        return "the market is closed; the demo seed starts no new one, so nothing to do"
    name = market.get("name")
    if state == "SETUP" and name is not None:
        # A market the seed made, or one it was interrupted in, has no name.
        return (
            f"the simulation in setup ({name}) was started by an operator;"
            " the demo seed sets up only a market of its own, so nothing to do"
        )
    ids: dict[str, UUID] = {}
    for symbol, price in INSTRUMENTS.items():
        created = operator.command(
            "/api/v1/admin/instruments",
            f"instrument:{symbol}",
            {"symbol": symbol, "referencePrice": price},
        )
        ids[symbol] = UUID(created["instrumentId"])
    holdings = [{"instrumentId": str(i), "quantity": SHARES} for i in ids.values()]
    endowment = {"cash": CASH, "holdings": holdings}
    try:
        operator.command(
            "/api/v1/admin/traders",
            f"trader:{TRADER}",
            {"username": TRADER, "password": trader_password, **endowment},
        )
    except SeedError as error:
        if error.code != "USERNAME_TAKEN":
            raise
        # A trader from an earlier configuration: fund that login, under the key the seed used then.
        operator.command(
            "/api/v1/admin/accounts", f"account:{TRADER}", {"username": TRADER, **endowment}
        )
    split = strategy_split(bots)
    for strategy, total in split.items():
        left = total
        batch = 0
        while left > 0:
            count = min(left, 100)
            operator.command(
                "/api/v1/admin/bots",
                f"bots:{strategy}:{batch}:{count}",
                {"count": count, "cash": CASH, "holdings": holdings, "strategy": strategy},
            )
            left -= count
            batch += 1
    operator.command("/api/v1/admin/market/open", "open")
    return (
        f"seeded {len(ids)} instruments, the {TRADER} account and {bots} bots"
        f" ({split_text(split)}), and opened the market"
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
    trader_password = os.environ.get("MSE_DEMO_TRADER_PASSWORD", "")
    if not trader_password:
        logger.error("MSE_DEMO_TRADER_PASSWORD is not set")
        return 1
    bots = int(os.environ.get("MSE_DEMO_BOTS", "20"))
    url = os.environ.get("MSE_EXECUTION_URL", "http://execution-service:8080")
    with httpx.Client(base_url=url, timeout=10.0) as http:
        try:
            logger.info(seed(Operator(http, password), bots, trader_password))
        except (SeedError, httpx.HTTPError) as error:
            logger.error("the demo seed failed: %s", error)
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
