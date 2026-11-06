"""The simulator's control plane: which run to work on, and the heartbeat that holds its lease."""

import logging
from dataclasses import dataclass
from typing import Any
from uuid import UUID

import httpx

logger = logging.getLogger(__name__)

RUNNING = "RUNNING"
STOPPED = "STOPPED"


@dataclass(frozen=True)
class AssignedBot:
    account_id: UUID
    strategy: str
    credential_ref: str


@dataclass(frozen=True)
class Totals:
    placed: int = 0
    canceled: int = 0
    rejected: int = 0
    errors: int = 0


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
    def parse(cls, run: dict[str, Any]) -> Assignment:
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
                for bot in run["bots"]
            ),
            totals=Totals(
                totals["placed"], totals["canceled"], totals["rejected"], totals["errors"]
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

    async def assignment(self) -> Assignment | None:
        """The run to work on, or None. Raises on a transport error or an unexpected answer."""
        response = await self._http.get(
            f"{self._base}/internal/simulator/assignment", headers=self._headers
        )
        response.raise_for_status()
        run = response.json()["run"]
        return None if run is None else Assignment.parse(run)

    async def heartbeat(
        self,
        run_id: UUID,
        worker_id: str,
        totals: Totals,
        in_flight: int,
        stop_failures: int,
        stopped: bool = False,
    ) -> Beat | None:
        """None when the answer says nothing either way (a transport error or a server error), so
        the caller tries again with the same cumulative totals."""
        body = {
            "runId": str(run_id),
            "workerId": worker_id,
            "placed": totals.placed,
            "canceled": totals.canceled,
            "rejected": totals.rejected,
            "errors": totals.errors,
            "inFlight": in_flight,
            "stopFailures": stop_failures,
            "stopped": stopped,
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
