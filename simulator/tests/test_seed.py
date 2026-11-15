import json
from uuid import uuid4

import httpx
import pytest

from mse_simulator import seed as seeding
from mse_simulator.seed import INSTRUMENTS, Operator, SeedError, seed

TRADER_PASSWORD = "a-trader-password"


class FakeExchange:
    """Just enough of Execution: a CSRF token per session, operator login, and setup commands that
    remember their answer per idempotency key, as the real ones do."""

    def __init__(self, state: str = "NOT_SET_UP", users: set[str] | None = None) -> None:
        self.state = state
        # Trader logins that exist already, from an earlier configuration.
        self.users = set(users or ())
        self.traders: list[dict] = []
        self.accounts: list[tuple[str, dict]] = []
        self.answers: dict[str, httpx.Response] = {}
        self.applied: list[str] = []
        self.bots: list[tuple[str, dict]] = []
        self.busy: set[str] = set()
        self.signed_in = False

    def __call__(self, request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path == "/api/v1/auth/csrf":
            return httpx.Response(200, json={"headerName": "X-CSRF-TOKEN", "token": "t"})
        if path == "/api/v1/auth/login":
            body = json.loads(request.content)
            self.signed_in = body == {"username": "operator", "password": "secret"}
            return httpx.Response(200 if self.signed_in else 401, json={"username": "operator"})
        if path == "/api/v1/market":
            return httpx.Response(200, json={"state": self.state})
        assert self.signed_in and request.headers["X-CSRF-TOKEN"] == "t"
        idempotency = request.headers["Idempotency-Key"]
        if path in self.busy:
            self.busy.discard(path)
            return httpx.Response(503, json={"code": "EXCHANGE_BUSY"})
        if idempotency in self.answers:
            return self.answers[idempotency]
        self.applied.append(path)
        if path == "/api/v1/admin/bots":
            self.bots.append((idempotency, json.loads(request.content)))
        if path == "/api/v1/admin/accounts":
            self.accounts.append((idempotency, json.loads(request.content)))
        if path == "/api/v1/admin/traders":
            body = json.loads(request.content)
            self.traders.append(body)
            if body["username"] in self.users:
                answer = httpx.Response(409, json={"code": "USERNAME_TAKEN"})
            else:
                self.users.add(body["username"])
                answer = httpx.Response(201, json={"username": body["username"]})
        elif path == "/api/v1/admin/instruments":
            answer = httpx.Response(201, json={"instrumentId": str(uuid4())})
            self.state = "SETUP"
        elif path == "/api/v1/admin/market/open":
            answer = httpx.Response(200, json={"state": "OPEN"})
            self.state = "OPEN"
        else:
            answer = httpx.Response(201, json={})
        self.answers[idempotency] = answer
        return answer


def operator(exchange: FakeExchange, password: str = "secret") -> Operator:
    http = httpx.Client(base_url="http://execution", transport=httpx.MockTransport(exchange))
    return Operator(http, password, sleep=lambda seconds: None)


def test_a_fresh_market_is_set_up_and_opened() -> None:
    exchange = FakeExchange()

    done = seed(operator(exchange), bots=150, trader_password=TRADER_PASSWORD)

    assert exchange.applied.count("/api/v1/admin/instruments") == len(INSTRUMENTS)
    # The trader is added with its endowment, as the operator adds one; nothing funded apart.
    assert exchange.applied.count("/api/v1/admin/accounts") == 0
    assert [
        (t["username"], t["password"], t["cash"], len(t["holdings"])) for t in exchange.traders
    ] == [("trader", TRADER_PASSWORD, "100000.00", len(INSTRUMENTS))]
    assert all(holding["quantity"] == 1000 for holding in exchange.traders[0]["holdings"])
    assert [(body["strategy"], body["count"]) for _, body in exchange.bots] == [
        ("MARKET_MAKER", 30),
        ("NOISE", 90),
        ("MOMENTUM", 30),
    ], "a command per strategy, market makers first"
    assert exchange.applied[-1] == "/api/v1/admin/market/open"
    assert exchange.state == "OPEN"
    assert "150 bots (30 market makers, 90 noise, 30 momentum)" in done


def test_each_strategy_is_provisioned_100_at_a_time_under_its_own_keys() -> None:
    exchange = FakeExchange()

    seed(operator(exchange), bots=600, trader_password=TRADER_PASSWORD)

    assert [(key, body["strategy"], body["count"]) for key, body in exchange.bots] == [
        (seeding.key("bots:MARKET_MAKER:0:100"), "MARKET_MAKER", 100),
        (seeding.key("bots:MARKET_MAKER:1:20"), "MARKET_MAKER", 20),
        (seeding.key("bots:NOISE:0:100"), "NOISE", 100),
        (seeding.key("bots:NOISE:1:100"), "NOISE", 100),
        (seeding.key("bots:NOISE:2:100"), "NOISE", 100),
        (seeding.key("bots:NOISE:3:60"), "NOISE", 60),
        (seeding.key("bots:MOMENTUM:0:100"), "MOMENTUM", 100),
        (seeding.key("bots:MOMENTUM:1:20"), "MOMENTUM", 20),
    ]
    funded = exchange.bots[0][1]
    assert funded["cash"] == "100000.00"
    assert len(funded["holdings"]) == len(INSTRUMENTS)
    assert all(body["holdings"] == funded["holdings"] for _, body in exchange.bots)


def test_a_few_bots_still_include_a_market_maker_and_a_noise_bot() -> None:
    for bots, expected in [
        (1, [("MARKET_MAKER", 1)]),
        (2, [("MARKET_MAKER", 1), ("NOISE", 1)]),
        (4, [("MARKET_MAKER", 1), ("NOISE", 3)]),
        (0, []),
    ]:
        exchange = FakeExchange()

        seed(operator(exchange), bots=bots, trader_password=TRADER_PASSWORD)

        assert [(body["strategy"], body["count"]) for _, body in exchange.bots] == expected, bots
        assert exchange.state == "OPEN"


def test_an_interrupted_seed_finishes_without_doing_anything_twice() -> None:
    # A first run stopped after the instruments; the next finds the exchange busy once for the bots.
    exchange = FakeExchange()
    exchange.busy = {"/api/v1/admin/bots"}
    first = operator(exchange)
    first.sign_in()
    for symbol, price in INSTRUMENTS.items():
        first.command(
            "/api/v1/admin/instruments",
            f"instrument:{symbol}",
            {"symbol": symbol, "referencePrice": price},
        )

    seed(operator(exchange), bots=20, trader_password=TRADER_PASSWORD)

    assert exchange.applied.count("/api/v1/admin/instruments") == len(INSTRUMENTS)
    assert [body["strategy"] for _, body in exchange.bots] == [
        "MARKET_MAKER",
        "NOISE",
        "MOMENTUM",
    ], "retried after busy, each applied once"
    assert exchange.state == "OPEN"


def test_a_wrong_operator_password_stops_before_anything() -> None:
    exchange = FakeExchange()

    with pytest.raises(SeedError, match="sign-in answered 401"):
        seed(operator(exchange, password="wrong"), bots=20, trader_password=TRADER_PASSWORD)

    assert exchange.applied == []


def test_a_running_market_is_left_alone() -> None:
    for state in ("OPEN", "HALTED"):
        exchange = FakeExchange(state)

        done = seed(operator(exchange), bots=20, trader_password=TRADER_PASSWORD)

        assert exchange.applied == []
        assert "nothing to do" in done


def test_a_refused_field_is_named_in_the_error() -> None:
    exchange = FakeExchange()
    exchange.answers[seeding.key("trader:trader")] = httpx.Response(
        400,
        json={
            "code": "VALIDATION_FAILED",
            "errors": [{"field": "password", "code": "INVALID_PASSWORD", "message": "too short"}],
        },
    )

    with pytest.raises(SeedError, match=r"400 VALIDATION_FAILED \(password: too short\)"):
        seed(operator(exchange), bots=0, trader_password="short")


def test_a_refusal_stops_the_seed_with_its_code() -> None:
    exchange = FakeExchange()
    exchange.answers[seeding.key("trader:trader")] = httpx.Response(
        422, json={"code": "UNKNOWN_INSTRUMENT"}
    )

    with pytest.raises(SeedError, match="trader:trader answered 422 UNKNOWN_INSTRUMENT"):
        seed(operator(exchange), bots=20, trader_password=TRADER_PASSWORD)
    assert "/api/v1/admin/accounts" not in exchange.applied


def test_a_configured_trader_from_an_earlier_stack_is_funded_instead() -> None:
    exchange = FakeExchange(users={"trader"})

    seed(operator(exchange), bots=20, trader_password=TRADER_PASSWORD)

    assert exchange.applied.count("/api/v1/admin/traders") == 1
    # Under the key the seed used before, so a seed stopped then replays its answer.
    [(funding_key, body)] = exchange.accounts
    assert funding_key == seeding.key("account:trader")
    assert body["username"] == "trader"
    assert body["cash"] == "100000.00"
    assert len(body["holdings"]) == len(INSTRUMENTS)
    assert exchange.state == "OPEN"


def test_a_resumed_seed_repeats_the_traders_answer_without_adding_twice() -> None:
    exchange = FakeExchange()
    seed(operator(exchange), bots=0, trader_password=TRADER_PASSWORD)
    exchange.state = "SETUP"

    seed(operator(exchange), bots=0, trader_password=TRADER_PASSWORD)

    assert exchange.applied.count("/api/v1/admin/traders") == 1, "the stored answer replays"


def test_seeding_is_off_unless_asked_for(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    monkeypatch.delenv("MSE_DEMO_SEED", raising=False)
    assert seeding.main() == 0
    monkeypatch.setenv("MSE_DEMO_SEED", "true")
    monkeypatch.delenv("MSE_DEMO_OPERATOR_PASSWORD", raising=False)
    assert seeding.main() == 1, "on, but without the operator's password"
    assert "MSE_DEMO_OPERATOR_PASSWORD is not set" in caplog.text
    monkeypatch.setenv("MSE_DEMO_OPERATOR_PASSWORD", "secret")
    monkeypatch.delenv("MSE_DEMO_TRADER_PASSWORD", raising=False)
    caplog.clear()
    # Stopped before reaching the exchange, not by failing to reach it.
    assert seeding.main() == 1, "on, but without the trader's password"
    assert "MSE_DEMO_TRADER_PASSWORD is not set" in caplog.text
    assert "the demo seed failed" not in caplog.text


def test_keys_are_the_same_on_every_run_and_differ_by_step() -> None:
    assert seeding.key("open") == seeding.key("open")
    assert seeding.key("instrument:NOVA") != seeding.key("instrument:ORBT")
