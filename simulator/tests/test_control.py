import asyncio
import json
from uuid import UUID, uuid4

import httpx
import pytest

from mse_simulator.control import BOTS_PER_PAGE, Assignment, Beat, ControlClient, Progress, Totals

CONTROL = "test-control-token"
BASE = "http://execution:8080"


def run(bots: int) -> dict:
    return {
        "runId": str(uuid4()),
        "desiredState": "RUNNING",
        "seed": "7",
        "commandsPerSecond": "100.00",
        "maxOrderQuantity": 5,
        "maxPosition": 100,
        "endsAt": None,
        "leaseSeconds": 15,
        "instruments": [{"instrumentId": str(uuid4()), "symbol": "NOVA"}],
        "botCount": bots,
        "worker": {"workerId": None, "leaseExpiresAt": None},
        "totals": {"placed": 0, "canceled": 0, "rejected": 0, "errors": 0},
    }


class Control:
    """The control plane's two reads: the run, and its bots in pages that start at the cursor.
    ``served`` is how many bots the pages hold, which a broken server may get wrong; with
    ``endless``, every page names another."""

    def __init__(self, assignment: dict | None, served: int | None = None) -> None:
        self.assignment = assignment
        count = 0 if assignment is None else assignment["botCount"]
        self.bots = [
            {"accountId": str(uuid4()), "strategy": "NOISE", "credentialRef": "x.token"}
            for _ in range(count if served is None else served)
        ]
        self.endless = False
        self.pages: list[dict] = []

    def handle(self, request: httpx.Request) -> httpx.Response:
        assert request.headers["Authorization"] == f"Bearer {CONTROL}"
        if request.url.path == "/internal/simulator/assignment":
            return httpx.Response(200, json={"run": self.assignment})
        assert self.assignment is not None
        assert request.url.path == f"/internal/simulator/runs/{self.assignment['runId']}/bots"
        params = dict(request.url.params)
        self.pages.append(params)
        # A client that never stops reading fails here instead of hanging the test.
        assert len(self.pages) <= 50, "the client keeps reading pages"
        start = int(params.get("cursor", "0"))
        limit = int(params["limit"])
        page = self.bots[start : start + limit]
        more = self.endless or start + limit < len(self.bots)
        return httpx.Response(
            200, json={"bots": page, "nextCursor": str(start + limit) if more else None}
        )


def read(control: Control, times: int = 1):
    """The assignment, asked for ``times`` times by one client; the last answer."""

    async def scenario():
        async with httpx.AsyncClient(transport=httpx.MockTransport(control.handle)) as http:
            client = ControlClient(http, BASE, CONTROL)
            answer = None
            for _ in range(times):
                answer = await client.assignment()
            return answer

    return asyncio.run(scenario())


def test_a_run_of_any_size_is_read_in_pages_in_order() -> None:
    control = Control(run(2_500))

    assignment = read(control)

    assert assignment is not None
    assert [bot.account_id for bot in assignment.bots] == [
        UUID(bot["accountId"]) for bot in control.bots
    ]
    assert control.pages == [
        {"limit": str(BOTS_PER_PAGE)},
        {"limit": str(BOTS_PER_PAGE), "cursor": "1000"},
        {"limit": str(BOTS_PER_PAGE), "cursor": "2000"},
    ]


def test_a_run_that_fits_one_page_is_one_read() -> None:
    control = Control(run(3))

    assignment = read(control)

    assert assignment is not None and len(assignment.bots) == 3
    assert len(control.pages) == 1


def test_a_run_asked_for_again_reuses_its_bots_and_a_new_run_reads_its_own() -> None:
    control = Control(run(2_500))

    again = read(control, times=3)

    assert again is not None and len(again.bots) == 2_500
    assert len(control.pages) == 3, "a run's bots never change, so its pages are read once"

    async def scenario():
        async with httpx.AsyncClient(transport=httpx.MockTransport(control.handle)) as http:
            client = ControlClient(http, BASE, CONTROL)
            await client.assignment()
            control.assignment = run(5)
            control.bots = control.bots[:5]
            return await client.assignment()

    control.pages.clear()
    fresh = asyncio.run(scenario())
    assert fresh is not None and len(fresh.bots) == 5
    assert len(control.pages) == 4, "three pages for the first run, one for the new one"


def test_pages_refused_are_read_again_next_time() -> None:
    control = Control(run(2_500), served=2_499)

    async def scenario():
        async with httpx.AsyncClient(transport=httpx.MockTransport(control.handle)) as http:
            client = ControlClient(http, BASE, CONTROL)
            with pytest.raises(ValueError):
                await client.assignment()
            control.bots.append(
                {"accountId": str(uuid4()), "strategy": "NOISE", "credentialRef": "x.token"}
            )
            return await client.assignment()

    assignment = asyncio.run(scenario())
    assert assignment is not None and len(assignment.bots) == 2_500
    assert len(control.pages) == 6


def test_no_run_reads_no_pages() -> None:
    control = Control(None)

    assert read(control) is None
    assert control.pages == []


@pytest.mark.parametrize("served", [2_499, 2_501])
def test_pages_that_do_not_add_up_to_the_count_are_refused(served) -> None:
    with pytest.raises(ValueError, match=f"hold {served} bots, not 2500"):
        read(Control(run(2_500), served=served))


def test_pages_that_never_end_stop_once_past_the_count() -> None:
    control = Control(run(1_500), served=5_000)
    control.endless = True

    with pytest.raises(ValueError, match="hold 2000 bots, not 1500"):
        read(control)
    assert len(control.pages) == 2


def test_empty_pages_that_name_another_are_refused() -> None:
    control = Control(run(1_500))
    control.endless = True

    with pytest.raises(ValueError, match="no bots names another"):
        read(control)
    assert len(control.pages) == 3


TOTALS = Totals(
    placed=7,
    canceled=2,
    rejected=1,
    errors=0,
    paused_stale_data=5,
    paused_market_not_open=4,
    paused_other=3,
)


def beat_with(
    answer: httpx.Response, manifest: dict | None = None, progress: Progress | None = None
) -> tuple[Beat | None, list[dict]]:
    sent: list[dict] = []

    def handle(request: httpx.Request) -> httpx.Response:
        assert request.headers["Authorization"] == f"Bearer {CONTROL}"
        assert request.url.path == "/internal/simulator/heartbeat"
        sent.append(json.loads(request.content))
        return answer

    async def go() -> Beat | None:
        async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as http:
            client = ControlClient(http, BASE, CONTROL)
            return await client.heartbeat(
                RUN, "worker-1", TOTALS, 3, 0, manifest=manifest, progress=progress
            )

    return asyncio.run(go()), sent


RUN = UUID("00000000-0000-0000-0000-0000000000aa")

HELD = httpx.Response(
    200,
    json={
        "runId": str(RUN),
        "desiredState": "RUNNING",
        "leaseExpiresAt": "2026-11-21T09:00:00Z",
        "stopped": False,
    },
)


def test_a_heartbeat_reports_the_totals_and_carries_a_manifest_only_when_given() -> None:
    held, sent = beat_with(HELD)
    assert held == Beat(True, "RUNNING")
    assert sent == [
        {
            "runId": str(RUN),
            "workerId": "worker-1",
            "placed": 7,
            "canceled": 2,
            "rejected": 1,
            "errors": 0,
            "inFlight": 3,
            "stopFailures": 0,
            "stopped": False,
            "pausedStaleData": 5,
            "pausedMarketNotOpen": 4,
            "pausedOther": 3,
        }
    ]
    _, with_manifest = beat_with(HELD, {"version": "0.1.0+27a70d6c1e2f"})
    assert with_manifest[0]["manifest"] == {"version": "0.1.0+27a70d6c1e2f"}
    _, loading = beat_with(HELD, progress=Progress("LOADING", 1_500, 20_000))
    assert {key: loading[0][key] for key in ("phase", "botsLoaded", "botsTotal")} == {
        "phase": "LOADING",
        "botsLoaded": 1_500,
        "botsTotal": 20_000,
    }


def test_the_paused_counts_continue_from_the_assignment_and_start_at_zero_without_them() -> None:
    body = run(1)
    old = Assignment.parse(body, [])
    assert (old.totals.paused_stale_data, old.totals.paused_other) == (0, 0)
    body["totals"] |= {"pausedStaleData": 9, "pausedMarketNotOpen": 8, "pausedOther": 7}
    totals = Assignment.parse(body, []).totals
    assert (totals.paused_stale_data, totals.paused_market_not_open, totals.paused_other) == (
        9,
        8,
        7,
    )


@pytest.mark.parametrize(
    ("answer", "expected"),
    [
        (httpx.Response(409, json={"code": "LEASE_HELD"}), Beat(False, None, "LEASE_HELD")),
        (httpx.Response(404, json={"code": "RUN_NOT_FOUND"}), Beat(False, None, "RUN_NOT_FOUND")),
        (
            httpx.Response(409, json={"code": "RUN_NOT_STOPPING"}),
            Beat(True, "RUNNING", "RUN_NOT_STOPPING"),
        ),
        (httpx.Response(400, json={"code": "VALIDATION_FAILED"}), None),
        (httpx.Response(503), None),
    ],
)
def test_a_heartbeat_answer_says_whether_the_lease_is_held(answer, expected) -> None:
    assert beat_with(answer)[0] == expected
