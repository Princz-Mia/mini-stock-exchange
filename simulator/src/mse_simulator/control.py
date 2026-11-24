"""The simulator's control plane: which run to work on, its bots, and the heartbeat that holds its
lease."""

import logging
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any
from uuid import UUID

import httpx

logger = logging.getLogger(__name__)

RUNNING = "RUNNING"
STOPPED = "STOPPED"

# A heartbeat's refusal for a run whose stop is already recorded.
RUN_FINISHED = "RUN_FINISHED"

# Where a worker is with its run: reading the bots' token files and building the bots, then trading.
LOADING = "LOADING"
TRADING = "TRADING"

# The most bots one page holds, as Execution allows: 10,000 bots are ten pages.
BOTS_PER_PAGE = 1000


@dataclass(frozen=True)
class AssignedBot:
    account_id: UUID
    strategy: str
    credential_ref: str


@dataclass(frozen=True)
class Totals:
    """Cumulative for the run. The paused counts are the bots' steps that could place nothing new,
    by why: market data too old or not live, the market not open, or another reason."""

    placed: int = 0
    canceled: int = 0
    rejected: int = 0
    errors: int = 0
    paused_stale_data: int = 0
    paused_market_not_open: int = 0
    paused_other: int = 0


@dataclass(frozen=True)
class Progress:
    """Where the worker is with its run: LOADING ``loaded`` of ``total`` bots, then TRADING."""

    phase: str
    loaded: int
    total: int


@dataclass(frozen=True)
class Assignment:
    run_id: UUID
    desired_state: str
    seed: int
    commands_per_second: float
    max_order_quantity: int
    max_position: int
    lease_seconds: int
    instrument_ids: frozenset[UUID]
    bots: tuple[AssignedBot, ...]
    totals: Totals

    @classmethod
    def parse(cls, run: dict[str, Any], bots: list[dict[str, Any]]) -> Assignment:
        """The run as the assignment gives it, with its bots as their pages gave them."""
        totals = run["totals"]
        return cls(
            run_id=UUID(run["runId"]),
            desired_state=run["desiredState"],
            seed=int(run["seed"]),
            commands_per_second=float(run["commandsPerSecond"]),
            max_order_quantity=run["maxOrderQuantity"],
            max_position=run["maxPosition"],
            lease_seconds=run["leaseSeconds"],
            instrument_ids=frozenset(UUID(item["instrumentId"]) for item in run["instruments"]),
            bots=tuple(
                AssignedBot(UUID(bot["accountId"]), bot["strategy"], bot["credentialRef"])
                for bot in bots
            ),
            totals=Totals(
                totals["placed"],
                totals["canceled"],
                totals["rejected"],
                totals["errors"],
                # An exchange that keeps no paused counts has none to continue from.
                totals.get("pausedStaleData", 0),
                totals.get("pausedMarketNotOpen", 0),
                totals.get("pausedOther", 0),
            ),
        )


@dataclass(frozen=True)
class Beat:
    """A heartbeat's answer. ``held`` is false once the lease belongs to another worker or the run
    is over; ``desired_state`` is only meaningful while held."""

    held: bool
    desired_state: str | None
    code: str | None = None


class ControlClient:
    """Calls with the simulator's control token, which can't trade. The token goes only into the
    Authorization header, never a log."""

    def __init__(self, http: httpx.AsyncClient, execution_url: str, token: str) -> None:
        self._http = http
        self._base = execution_url
        self._headers = {"Authorization": f"Bearer {token}"}
        # The last run's bots, kept because a run's bots never change: a worker asks again while
        # another holds the lease, or after losing it, and reads the pages only for a new run.
        self._bots_of: str | None = None
        self._bots: list[dict[str, Any]] = []

    async def assignment(self) -> Assignment | None:
        """The run to work on with all its bots, or None. A run has no size limit, so its bots come
        in pages, read once, since they never change. Raises on a transport error or an unexpected
        answer, including pages that don't add up to the run's count; the caller asks again."""
        response = await self._http.get(
            f"{self._base}/internal/simulator/assignment", headers=self._headers
        )
        response.raise_for_status()
        run = response.json()["run"]
        if run is None:
            return None
        if run["runId"] == self._bots_of:
            return Assignment.parse(run, self._bots)
        expected = run["botCount"]
        bots: list[dict[str, Any]] = []
        cursor: str | None = None
        while True:
            params: dict[str, Any] = {"limit": BOTS_PER_PAGE}
            if cursor is not None:
                params["cursor"] = cursor
            page = await self._http.get(
                f"{self._base}/internal/simulator/runs/{run['runId']}/bots",
                params=params,
                headers=self._headers,
            )
            page.raise_for_status()
            body = page.json()
            bots.extend(body["bots"])
            cursor = body["nextCursor"]
            if not body["bots"] and cursor is not None:
                # Each page must move on, or the reading would never end.
                raise ValueError("a page with no bots names another")
            if len(bots) > expected or (cursor is None and len(bots) != expected):
                raise ValueError(f"the run's pages hold {len(bots)} bots, not {expected}")
            if cursor is None:
                self._bots_of, self._bots = run["runId"], bots
                return Assignment.parse(run, bots)

    async def heartbeat(
        self,
        run_id: UUID,
        worker_id: str,
        totals: Totals,
        in_flight: int,
        stop_failures: int,
        stopped: bool = False,
        manifest: Mapping[str, Any] | None = None,
        progress: Progress | None = None,
    ) -> Beat | None:
        """None when the answer says nothing either way (a transport error or a server error), so
        the caller tries again with the same cumulative totals. A manifest and the progress go only
        if given."""
        body: dict[str, Any] = {
            "runId": str(run_id),
            "workerId": worker_id,
            "placed": totals.placed,
            "canceled": totals.canceled,
            "rejected": totals.rejected,
            "errors": totals.errors,
            "inFlight": in_flight,
            "stopFailures": stop_failures,
            "stopped": stopped,
            "pausedStaleData": totals.paused_stale_data,
            "pausedMarketNotOpen": totals.paused_market_not_open,
            "pausedOther": totals.paused_other,
        }
        if manifest is not None:
            body["manifest"] = manifest
        if progress is not None:
            body |= {
                "phase": progress.phase,
                "botsLoaded": progress.loaded,
                "botsTotal": progress.total,
            }
        try:
            response = await self._http.post(
                f"{self._base}/internal/simulator/heartbeat", json=body, headers=self._headers
            )
        except httpx.TransportError as error:
            logger.info("heartbeat not delivered: %s", type(error).__name__)
            return None
        if response.status_code == 200:
            return Beat(True, response.json()["desiredState"])
        if response.status_code in (404, 409):
            code = _code(response)
            if code == "RUN_NOT_STOPPING":
                # The run was asked to run again under the same id: impossible today, so stay put.
                return Beat(True, RUNNING, code)
            return Beat(False, None, code)
        logger.warning("heartbeat answered %d", response.status_code)
        return None


def _code(response: httpx.Response) -> str | None:
    try:
        body = response.json()
    except ValueError:
        return None
    return body.get("code") if isinstance(body, dict) else None
