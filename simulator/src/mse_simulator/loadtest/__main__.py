"""Runs the steady-load scenario against a throwaway stack and writes a report against the budgets.

From the repository root, with Docker running:

    uv run --project simulator python -m mse_simulator.loadtest

The stack runs as its own Compose project ("mse-load") with its own ports, volumes and freshly
random passwords, so a development stack or a gate run is never touched; it is deleted
afterwards unless --keep-stack is given. The exit code is 0 only when every budget is met.
"""

import argparse
import json
import logging
import math
import os
import platform
import secrets
import subprocess
import sys
import time
import uuid
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path

import httpx

from mse_simulator.loadtest import stats
from mse_simulator.population import split_text, strategy_split

logger = logging.getLogger("mse_simulator.loadtest")

PROJECT = "mse-load"
SYMBOLS = ("ALPHA", "BRAVO", "CHARLIE", "DELTA", "ECHO")
WEB_PORT = 38080
# Not "localhost": on Windows that tries IPv6 first and waits about two seconds before falling back,
# which would show up as client latency whenever a connection is reopened.
HOST = "127.0.0.1"
P95_BUDGET = 0.150
P99_BUDGET = 0.500
FAILURE_BUDGET = 0.001
LATENCY = "mse_commands_seconds_bucket"


@dataclass
class Options:
    root: Path
    rate: str
    bots: int
    warmup: int
    duration: int
    seed: int
    keep_stack: bool


@dataclass
class Snapshot:
    at: float
    metrics: dict
    activity: dict


@dataclass
class Window:
    probe: list[tuple[str, float, int]] = field(default_factory=list)
    queue: list[tuple[float, float]] = field(default_factory=list)
    feeds: list[tuple[float, str]] = field(default_factory=list)
    outbox: list[tuple[float, int]] = field(default_factory=list)


class Stack:
    """The throwaway Compose project and the commands run inside it."""

    def __init__(self, root: Path) -> None:
        self.root = root
        self.run_dir = root / "runtime" / "load-test"
        self.run_dir.mkdir(parents=True, exist_ok=True)
        self.env_file = self.run_dir / "load.env"
        self.passwords = {
            name: secrets.token_hex(24)
            for name in (
                "postgres",
                "execution_owner",
                "execution_app",
                "market_data_owner",
                "market_data_app",
                "grafana",
                "operator",
                "trader",
                "export",
                "simulator",
            )
        }
        lines = [
            f"POSTGRES_PASSWORD={self.passwords['postgres']}",
            f"EXECUTION_DB_OWNER_PASSWORD={self.passwords['execution_owner']}",
            f"EXECUTION_DB_APP_PASSWORD={self.passwords['execution_app']}",
            f"MARKET_DATA_DB_OWNER_PASSWORD={self.passwords['market_data_owner']}",
            f"MARKET_DATA_DB_APP_PASSWORD={self.passwords['market_data_app']}",
            f"GRAFANA_ADMIN_PASSWORD={self.passwords['grafana']}",
            f"DEV_OPERATOR_PASSWORD={self.passwords['operator']}",
            f"DEV_TRADER_PASSWORD={self.passwords['trader']}",
            f"MSE_EXPORT_TOKEN={self.passwords['export']}",
            f"MSE_SIMULATOR_TOKEN={self.passwords['simulator']}",
            f"WEB_HOST_PORT={WEB_PORT}",
            "POSTGRES_HOST_PORT=35432",
            "KAFKA_HOST_PORT=39092",
            "FLINK_UI_HOST_PORT=38081",
        ]
        self.env_file.write_text("\n".join(lines) + "\n", encoding="utf-8")
        try:
            os.chmod(self.env_file, 0o600)
        except OSError:
            pass

    def compose(self, *args: str, check: bool = True, timeout: float = 900) -> str:
        command = [
            "docker",
            "compose",
            "-p",
            PROJECT,
            "--env-file",
            str(self.env_file),
            "--profile",
            "full",
            *args,
        ]
        result = subprocess.run(
            command,
            cwd=self.root,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
        )
        if check and result.returncode != 0:
            raise RuntimeError(f"docker compose {' '.join(args)} failed: {result.stderr[-2000:]}")
        return result.stdout + result.stderr

    def exec(self, service: str, *command: str, env: dict[str, str] | None = None) -> str:
        flags = [item for name, value in (env or {}).items() for item in ("-e", f"{name}={value}")]
        return self.compose("exec", "-T", *flags, service, *command, timeout=120)

    def sql(self, role: str, database: str, query: str) -> str:
        return self.exec(
            "postgres",
            "psql",
            "-h",
            "127.0.0.1",
            "-v",
            "ON_ERROR_STOP=1",
            "-U",
            role,
            "-d",
            database,
            "-Atc",
            query,
            env={"PGPASSWORD": self.passwords[role]},
        ).strip()

    def scrape(self) -> dict:
        text = self.exec(
            "execution-service", "curl", "-fsS", "http://localhost:8080/actuator/prometheus"
        )
        return stats.parse_prometheus(text)


class Session:
    """One user's session through the proxy, with its CSRF token and a fresh key per command."""

    def __init__(self, username: str, password: str) -> None:
        self.http = httpx.Client(base_url=f"http://{HOST}:{WEB_PORT}", timeout=15.0)
        csrf = self.http.get("/api/v1/auth/csrf").json()
        login = self.http.post(
            "/api/v1/auth/login",
            json={"username": username, "password": password},
            headers={csrf["headerName"]: csrf["token"]},
        )
        login.raise_for_status()
        # Login rotates the token; it then stays the same for the session.
        csrf = self.http.get("/api/v1/auth/csrf").json()
        self.csrf = {csrf["headerName"]: csrf["token"]}

    def command(self, path: str, body: dict | None = None) -> httpx.Response:
        headers = self.csrf | {"Idempotency-Key": str(uuid.uuid4())}
        return self.http.post(path, json=body, headers=headers)

    def expect(self, status: int, path: str, body: dict | None = None) -> dict:
        response = self.command(path, body)
        if response.status_code != status:
            raise RuntimeError(
                f"POST {path}: expected {status}, got {response.status_code}: {response.text}"
            )
        return response.json() if response.content else {}


def wait_for(what: str, condition, timeout: float, interval: float = 2.0):
    deadline = time.monotonic() + timeout
    while True:
        try:
            value = condition()
        except httpx.HTTPError, RuntimeError, ValueError, KeyError:
            value = None
        if value:
            return value
        if time.monotonic() > deadline:
            raise RuntimeError(f"timed out waiting for {what}")
        time.sleep(interval)


def set_up(operator: Session, bots: int) -> list[str]:
    """Five instruments at 100.00, a funded trader for the probe, the bots tagged in the split
    the run uses, and an open market."""
    instruments = [
        operator.expect(
            201, "/api/v1/admin/instruments", {"symbol": symbol, "referencePrice": "100.00"}
        )["instrumentId"]
        for symbol in SYMBOLS
    ]
    holdings = [{"instrumentId": instrument, "quantity": 1000} for instrument in instruments]
    operator.expect(
        201,
        "/api/v1/admin/accounts",
        {"username": "trader", "cash": "1000000.00", "holdings": holdings},
    )
    bot_holdings = [{"instrumentId": instrument, "quantity": 200} for instrument in instruments]
    for strategy, total in strategy_split(bots).items():
        for start in range(0, total, 100):
            operator.expect(
                201,
                "/api/v1/admin/bots",
                {
                    "count": min(100, total - start),
                    "cash": "100000.00",
                    "holdings": bot_holdings,
                    "strategy": strategy,
                },
            )
    operator.expect(200, "/api/v1/admin/market/open")
    return instruments


def activity(operator: Session) -> dict:
    return operator.http.get("/api/v1/admin/bot-runs?limit=1").json()["runs"][0]


def listing() -> dict:
    return httpx.get(f"http://{HOST}:{WEB_PORT}/api/v1/instruments", timeout=10.0).json()


def measure(
    stack: Stack, operator: Session, trader: Session, instrument: str, seconds: int
) -> Window:
    """The probe trader places and cancels once a second; the queue, feed and outbox are sampled."""
    result = Window()
    order = {
        "instrumentId": instrument,
        "side": "BUY",
        "type": "LIMIT",
        "timeInForce": "GTC",
        "quantity": 1,
        "limitPrice": "1.00",
    }
    start = time.monotonic()
    tick = 0
    while (now := time.monotonic() - start) < seconds:
        began = time.perf_counter()
        placed = trader.command("/api/v1/orders", order)
        result.probe.append(("place", time.perf_counter() - began, placed.status_code))
        if placed.status_code == 201:
            began = time.perf_counter()
            canceled = trader.command(f"/api/v1/orders/{placed.json()['orderId']}/cancel")
            result.probe.append(("cancel", time.perf_counter() - began, canceled.status_code))
        if tick % 5 == 0:
            result.queue.append((now, stats.total(stack.scrape(), "mse_writer_queue_depth")))
            result.feeds.append((now, listing()["feed"]["state"]))
        if tick % 15 == 0:
            pending = stack.sql(
                "execution_owner",
                "execution_db",
                "SELECT count(*) FROM outbox WHERE published_at IS NULL",
            )
            result.outbox.append((now, int(pending)))
        tick += 1
        time.sleep(max(0.0, start + tick - time.monotonic()))
    return result


def snapshot(stack: Stack, operator: Session) -> Snapshot:
    return Snapshot(time.monotonic(), stack.scrape(), activity(operator)["activity"])


def environment(stack: Stack) -> dict:
    info = json.loads(
        subprocess.run(
            ["docker", "info", "--format", "{{json .}}"],
            capture_output=True,
            text=True,
            encoding="utf-8",
        ).stdout
    )
    config = json.loads(stack.compose("config", "--format", "json"))
    limits = {
        name: (service.get("mem_limit"), service.get("cpus"))
        for name, service in config["services"].items()
        if name
        in (
            "execution-service",
            "market-data-service",
            "postgres",
            "kafka",
            "simulator",
            "flink-jobmanager",
            "flink-taskmanager",
            "web",
        )
    }
    commit = subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=stack.root, capture_output=True, text=True
    ).stdout.strip()
    dirty = bool(
        subprocess.run(
            ["git", "status", "--porcelain"], cwd=stack.root, capture_output=True, text=True
        ).stdout.strip()
    )
    java = [
        line
        for line in stack.exec("execution-service", "java", "-version").splitlines()
        if "version" in line
    ]
    flags = stack.exec(
        "execution-service",
        "sh",
        "-c",
        "jcmd 1 VM.command_line 2>/dev/null | grep -i 'jvm_args' || echo none",
    ).strip()
    return {
        "host": f"{platform.system()} {platform.release()} ({platform.machine()})",
        "docker": f"{info.get('ServerVersion')} on {info.get('OperatingSystem')}, kernel "
        f"{info.get('KernelVersion')}",
        "cpus": info.get("NCPU"),
        "memory_gib": round(info.get("MemTotal", 0) / 2**30, 1),
        "limits": limits,
        "commit": commit + (" (uncommitted changes)" if dirty else ""),
        "java": java[0] if java else "unknown",
        "jvm_args": flags,
        "postgres": stack.sql(
            "execution_owner",
            "execution_db",
            "SELECT 'fsync=' || current_setting('fsync') || ', synchronous_commit=' "
            "|| current_setting('synchronous_commit') || ', ' || version()",
        ),
    }


def correctness(stack: Stack, instruments: list[str]) -> dict:
    """After the stop: the books and ledger reload and reconcile, and the public side matches
    exactly."""
    checks = {}
    stack.compose("restart", "execution-service")
    try:
        stack.compose(
            "up", "-d", "--no-deps", "--wait", "--wait-timeout", "240", "execution-service"
        )
        checks["Execution restarts and its startup reconciliation passes"] = True
    except RuntimeError:
        checks["Execution restarts and its startup reconciliation passes"] = False
    totals = (
        "SELECT count(*) || ' ' || coalesce(sum(quantity), 0) || ' ' "
        "|| coalesce(sum(price_cents * quantity), 0) || ' ' || coalesce(max(trade_sequence), 0)"
    )

    def same_totals() -> bool:
        return all(
            stack.sql(
                "execution_owner",
                "execution_db",
                f"{totals} FROM trades WHERE instrument_id = '{i}'",
            )
            == stack.sql(
                "market_data_app",
                "market_data_db",
                f"{totals} FROM public_trade WHERE instrument_id = '{i}'",
            )
            for i in instruments
        )

    try:
        wait_for("public trades to match", same_totals, 120)
        checks["Every trade is public, with the same count, shares, notional and last sequence"] = (
            True
        )
    except RuntimeError:
        checks["Every trade is public, with the same count, shares, notional and last sequence"] = (
            False
        )

    def candles_current() -> bool:
        return all(
            httpx.get(
                f"http://{HOST}:{WEB_PORT}/api/v1/instruments/{i}/candles", timeout=10.0
            ).json()["freshness"]["tradesBehind"]
            == 0
            for i in instruments
        )

    try:
        wait_for("candles to catch up", candles_current, 300)
        report = stack.exec(
            "market-data-service",
            "curl",
            "-fsS",
            "http://localhost:8080/internal/reconciliation/candles",
        )
        checks["Candles reconcile exactly with the trade export"] = '"reconciled":true' in report
    except RuntimeError:
        checks["Candles reconcile exactly with the trade export"] = False
    return checks


def summarize(
    options: Options, before: Snapshot, after: Snapshot, window: Window, env: dict, checks: dict
) -> tuple[str, bool]:
    seconds = after.at - before.at
    histogram = stats.window(
        stats.bucket_counts(before.metrics, LATENCY), stats.bucket_counts(after.metrics, LATENCY)
    )
    committed = stats.window(
        stats.bucket_counts(before.metrics, LATENCY, outcome="committed"),
        stats.bucket_counts(after.metrics, LATENCY, outcome="committed"),
    )
    failed = stats.count(histogram) - stats.count(committed)
    keys = ("placed", "canceled", "rejected", "errors")
    bot = {key: after.activity[key] - before.activity[key] for key in keys}
    attempts = sum(bot.values())
    probe_failures = sum(1 for _, _, status in window.probe if status >= 500)
    offered = attempts + len(window.probe)

    def delta(name: str, **labels: str) -> float:
        return stats.total(after.metrics, name, **labels) - stats.total(
            before.metrics, name, **labels
        )

    refused = {
        "busy": delta("mse_commands_refused_total", reason="busy"),
        "backlog": delta("mse_commands_refused_total", reason="backlog"),
        "rate limit": delta("mse_commands_rate_limited_total"),
    }
    unexpected = failed + bot["errors"] + probe_failures
    queue = stats.queue_growth(window.queue)
    within_p95 = stats.share_within(histogram, P95_BUDGET)
    within_p99 = stats.share_within(histogram, P99_BUDGET)
    client = [latency for _, latency, status in window.probe if status in (200, 201)]
    stale = sum(1 for _, state in window.feeds if state != "LIVE")

    verdicts = {
        f"p95 within 150 ms ({within_p95:.2%} of commands)": within_p95 >= 0.95,
        f"p99 within 500 ms ({within_p99:.2%} of commands)": within_p99 >= 0.99,
        f"No sustained queue growth (first minute {queue.first_minute:.1f}, last minute "
        f"{queue.last_minute:.1f}, peak {queue.peak:.0f})": not queue.grew,
        f"Unexpected infrastructure failures below 0.1% ({unexpected:.0f} of {offered})": offered
        > 0
        and unexpected / offered < FAILURE_BUDGET,
        f"Offered rate near {options.rate}/s ({offered / seconds:.1f}/s)": offered / seconds
        >= 0.9 * float(options.rate),
    } | checks
    passed = all(verdicts.values())

    def ms(value: float | None) -> str:
        return "n/a" if value is None else f"{value * 1000:.1f} ms"

    lines = [
        f"# Steady load: {datetime.now(UTC):%Y-%m-%d %H:%M} UTC",
        "",
        f"**Result: {'all budgets met' if passed else 'budget missed'}.**",
        "",
        "## Verdicts",
        "",
        "| Check | Met |",
        "| --- | --- |",
        *[f"| {name} | {'yes' if met else '**no**'} |" for name, met in verdicts.items()],
        "",
        "## Scenario",
        "",
        f"- {len(SYMBOLS)} instruments; {options.bots} bots, each tagged with the strategy it "
        f"runs ({split_text(strategy_split(options.bots))}), seed {options.seed}, "
        f"run rate {options.rate} commands/s.",
        f"- Warm-up {options.warmup} s, then a measured window of {seconds:.0f} s.",
        "- Probe: one trader places and cancels once a second through the public proxy, for client "
        "latency; its commands are part of the load.",
        "",
        "## Rates in the window",
        "",
        "| | Commands | Per second |",
        "| --- | --- | --- |",
        f"| Offered (bot attempts and probe) | {offered} | {offered / seconds:.1f} |",
        f"| Admitted to the writer | {stats.count(histogram):.0f} | "
        f"{stats.count(histogram) / seconds:.1f} |",
        f"| Committed | {stats.count(committed):.0f} | {stats.count(committed) / seconds:.1f} |",
        f"| Business rejections (committed, e.g. insufficient cash) | {bot['rejected']} | |",
        f"| Failed after admission | {failed:.0f} | |",
        f"| Refused before admission: busy, backlog, rate limit | "
        f"{refused['busy']:.0f}, {refused['backlog']:.0f}, {refused['rate limit']:.0f} | |",
        f"| Bot errors (transport or unexpected answers) | {bot['errors']} | |",
        "",
        "Bot counters come from the run's heartbeats, so each end may lag by one heartbeat.",
        "",
        "## Latency",
        "",
        "| | p50 | p95 | p99 |",
        "| --- | --- | --- | --- |",
        f"| Admission to committed result (Execution histogram, interpolated) | "
        f"{ms(stats.percentile(histogram, 0.5))} | {ms(stats.percentile(histogram, 0.95))} | "
        f"{ms(stats.percentile(histogram, 0.99))} |",
        f"| Client, through the proxy ({len(client)} probe commands) | "
        f"{ms(stats.nearest_rank(client, 0.5))} | {ms(stats.nearest_rank(client, 0.95))} | "
        f"{ms(stats.nearest_rank(client, 0.99))} |",
        "",
        f"Exact shares at the budget edges: {within_p95:.3%} within 150 ms, "
        f"{within_p99:.3%} within "
        "500 ms. The percentiles between edges are estimates.",
        "",
        "## Pipeline during the window",
        "",
        f"- Writer queue depth: first minute {queue.first_minute:.1f}, last minute "
        f"{queue.last_minute:.1f}, peak {queue.peak:.0f} ({len(window.queue)} samples).",
        f"- Outbox unpublished: peak {max((n for _, n in window.outbox), default=0)} "
        f"({len(window.outbox)} samples).",
        f"- Public feed not LIVE in {stale} of {len(window.feeds)} samples.",
        "",
        "## Environment",
        "",
        f"- Host: {env['host']}; Docker {env['docker']}; {env['cpus']} CPUs and "
        f"{env['memory_gib']} GiB "
        "visible to Docker. Generator: the simulator container on the same host.",
        f"- Commit: {env['commit']}.",
        f"- Execution JVM: {env['java']}; {env['jvm_args']}.",
        f"- PostgreSQL: {env['postgres']}.",
        "- Container limits (memory, CPUs): "
        + "; ".join(
            f"{name} {mem or '-'}, {cpus or '-'}"
            for name, (mem, cpus) in sorted(env["limits"].items())
        )
        + ".",
        "",
    ]
    return "\n".join(lines), passed


def run(options: Options) -> int:
    stack = Stack(options.root)
    logger.info("starting a clean stack as Compose project %s", PROJECT)
    stack.compose("down", "-v", "--remove-orphans", check=False)
    try:
        stack.compose("up", "-d", "--build", "--wait", "--wait-timeout", "600", timeout=1800)
        operator = Session("operator", stack.passwords["operator"])
        instruments = set_up(operator, options.bots)
        wait_for(
            "Market Data to list the instruments",
            lambda: len(listing()["instruments"]) >= len(SYMBOLS),
            120,
        )
        trader = Session("trader", stack.passwords["trader"])
        split = strategy_split(options.bots)
        minutes = math.ceil((options.warmup + options.duration) / 60) + 3
        started = operator.expect(
            201,
            "/api/v1/admin/bot-runs",
            {
                "marketMakers": split["MARKET_MAKER"],
                "momentum": split["MOMENTUM"],
                "noise": split["NOISE"],
                "seed": options.seed,
                "commandsPerSecond": options.rate,
                "maxOrderQuantity": 10,
                "maxPosition": 200,
                "durationMinutes": minutes,
            },
        )
        logger.info("run %s started; waiting for a worker", started["runId"])
        wait_for("the run to be RUNNING", lambda: activity(operator)["status"] == "RUNNING", 180)
        logger.info("warming up for %d s", options.warmup)
        time.sleep(options.warmup)
        before = snapshot(stack, operator)
        logger.info("measuring for %d s", options.duration)
        window = measure(stack, operator, trader, instruments[0], options.duration)
        after = snapshot(stack, operator)
        operator.expect(200, f"/api/v1/admin/bot-runs/{started['runId']}/stop")
        wait_for("the run to stop", lambda: activity(operator)["status"] == "STOPPED", 180)
        env = environment(stack)
        logger.info("checking accounting and the public side")
        checks = correctness(stack, instruments)
        report, passed = summarize(options, before, after, window, env, checks)
        path = stack.run_dir / f"report-{datetime.now(UTC):%Y%m%d-%H%M}.md"
        path.write_text(report, encoding="utf-8")
        print(report)
        logger.info("report written to %s", path)
        return 0 if passed else 1
    finally:
        if options.keep_stack:
            logger.info("stack kept: docker compose -p %s", PROJECT)
        else:
            stack.compose("down", "-v", "--remove-orphans", check=False)


def parse(argv: list[str]) -> Options:
    parser = argparse.ArgumentParser(
        prog="python -m mse_simulator.loadtest",
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--rate", default="100.00", help="run rate in commands/s, as the API takes it"
    )
    parser.add_argument("--bots", type=int, default=1000)
    parser.add_argument("--warmup", type=int, default=120, help="seconds before measuring")
    parser.add_argument("--duration", type=int, default=600, help="seconds measured")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--keep-stack", action="store_true")
    args = parser.parse_args(argv)
    root = Path.cwd()
    if not (root / "compose.yaml").exists():
        parser.error("run from the repository root, where compose.yaml is")
    return Options(
        root, args.rate, args.bots, args.warmup, args.duration, args.seed, args.keep_stack
    )


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
    sys.exit(run(parse(sys.argv[1:])))
