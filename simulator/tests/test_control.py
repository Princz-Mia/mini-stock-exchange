import asyncio
from uuid import UUID, uuid4

import httpx
import pytest

from mse_simulator.control import BOTS_PER_PAGE, ControlClient

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
