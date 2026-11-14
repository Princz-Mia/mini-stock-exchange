from mse_simulator.loadtest.__main__ import SYMBOLS, set_up


class FakeOperator:
    """Records the setup commands the load test sends and answers each as the exchange would."""

    def __init__(self) -> None:
        self.sent: list[tuple[str, dict | None]] = []

    def expect(self, status: int, path: str, body: dict | None = None) -> dict:
        self.sent.append((path, body))
        if path == "/api/v1/admin/instruments":
            return {"instrumentId": f"id-{body['symbol']}"}
        return {}


def test_bots_are_tagged_in_the_split_their_run_uses() -> None:
    operator = FakeOperator()

    instruments = set_up(operator, 250, "a-trader-password")

    assert len(instruments) == len(SYMBOLS)
    batches = [
        (body["strategy"], body["count"])
        for path, body in operator.sent
        if path == "/api/v1/admin/bots"
    ]
    assert batches == [("MARKET_MAKER", 50), ("NOISE", 100), ("NOISE", 50), ("MOMENTUM", 50)]
    assert operator.sent[-1] == ("/api/v1/admin/market/open", None)
    traders = [body for path, body in operator.sent if path == "/api/v1/admin/traders"]
    assert [(t["username"], t["password"], t["cash"]) for t in traders] == [
        ("trader", "a-trader-password", "1000000.00")
    ]
    assert not any(path == "/api/v1/admin/accounts" for path, _ in operator.sent)
