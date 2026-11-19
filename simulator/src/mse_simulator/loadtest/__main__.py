"""Runs the steady-load scenario against a throwaway stack and writes a report against the budgets.

From the repository root, with Docker running:

    uv run --project simulator python -m mse_simulator.loadtest

The stack runs as its own Compose project ("mse-load") with its own ports, volumes and freshly
random passwords, so a development stack or a gate run is never touched; it is deleted
afterwards unless --keep-stack is given. The exit code is 0 only when every budget is met.

--ramp (bot runs) and --stress (open loop, no bots) instead look for the highest rate that meets
the budgets, stopping at the first rate that misses one; their exit code is 0 when at least one
rate met every budget and the accounting checks afterwards passed.
"""

import argparse
import asyncio
import json
import logging
import math
import os
import platform
import secrets
import subprocess
import sys
import threading
import time
import uuid
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path

import httpx

from mse_simulator.loadtest import diagnostics, ramp, stats, stress
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
# The longest the bots may take to load before the run counts as failed to start.
LOADING = 600
# The first rate on a fresh stack warms up at least this long: the JVM is still compiling, and a
# 30-second warm-up once failed a rate that passed warm.
FIRST_WARMUP = 120


@dataclass
class Options:
    root: Path
    rate: str
    bots: int
    warmup: int
    duration: int
    seed: int
    keep_stack: bool
    # With a ramp: one run after another at these rates, each warmed up and measured on its own.
    ramp: list[float] | None = None
    step_warmup: int = 30
    step_duration: int = 120
    # With stress: no bots; trader logins send at each of these rates, open loop.
    stress: list[float] | None = None
    stress_warmup: int = 10
    stress_duration: int = 60


@dataclass
class Snapshot:
    at: float
    metrics: dict
    activity: dict


@dataclass
class Phases:
    """How long the parts around the window took, and what the simulator held. A run has no size
    limit, so these grow with the bots and are reported, not budgeted."""

    provisioned: float = 0.0
    loaded: float = 0.0
    stopped: float = 0.0
    simulator_memory: str = "n/a"


@dataclass
class Window:
    probe: list[tuple[str, float, int]] = field(default_factory=list)
    # Probe answers of 503 that were not a busy refusal (TRADING_UNAVAILABLE: the writer stopped),
    # which Execution counts nowhere.
    unavailable: int = 0
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
            "-f",
            "compose.yaml",
            "-f",
            str(Path("infra", "load-test", "compose.diagnostics.yaml")),
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

    def memory(self, service: str) -> str:
        """The service's memory use and limit as Docker reports it, such as "61.2MiB / 512MiB"."""
        container = self.compose("ps", "-q", service).strip()
        if not container:
            return "n/a"
        result = subprocess.run(
            ["docker", "stats", "--no-stream", "--format", "{{.MemUsage}}", container],
            capture_output=True,
            text=True,
            timeout=60,
            check=False,
        )
        return result.stdout.strip() or "n/a"

    def scrape(self, service: str = "execution-service") -> dict:
        text = self.exec(service, "curl", "-fsS", "http://localhost:8080/actuator/prometheus")
        return stats.parse_prometheus(text)

    def database(self) -> diagnostics.Database:
        """The database's statistics now, read as its superuser."""

        def read(query: str, database: str = "execution_db") -> str:
            return self.sql("postgres", database, query)

        return diagnostics.database(
            read(diagnostics.STATEMENTS),
            read(diagnostics.WAL_IO),
            read(diagnostics.WAL),
            read(diagnostics.CHECKPOINTER),
            read(diagnostics.TABLES),
        )

    def cpu(self) -> str:
        """One docker stats reading of every container's processor, as "name|12.34%" lines."""
        result = subprocess.run(
            ["docker", "stats", "--no-stream", "--format", "{{.Name}}|{{.CPUPerc}}"],
            capture_output=True,
            text=True,
            timeout=60,
            check=False,
        )
        return result.stdout


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


def set_up(
    operator: Session,
    bots: int,
    trader_password: str,
    logins: list[tuple[str, str]] | None = None,
) -> list[str]:
    """Five instruments at 100.00, a trader for the probe, added and funded as the operator adds
    one, any further trader logins (username, password) with cash only, the bots tagged in the
    split the run uses, and an open market."""
    instruments = [
        operator.expect(
            201, "/api/v1/admin/instruments", {"symbol": symbol, "referencePrice": "100.00"}
        )["instrumentId"]
        for symbol in SYMBOLS
    ]
    holdings = [{"instrumentId": instrument, "quantity": 1000} for instrument in instruments]
    operator.expect(
        201,
        "/api/v1/admin/traders",
        {
            "username": "trader",
            "password": trader_password,
            "cash": "1000000.00",
            "holdings": holdings,
        },
    )
    for username, password in logins or []:
        operator.expect(
            201,
            "/api/v1/admin/traders",
            {"username": username, "password": password, "cash": "1000000.00"},
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
    stack: Stack,
    operator: Session,
    trader: Session,
    instrument: str,
    seconds: int,
    sampled: diagnostics.Sampled,
) -> Window:
    """The probe trader places and cancels once a second; the queue, feed and outbox are sampled,
    and the feed's lag, the database pool and each container's processor."""
    result = Window()
    done = threading.Event()

    def cpu() -> None:
        # docker stats takes a second or two, so it runs beside the probe, not in its loop.
        while not done.wait(10):
            sampled.docker(stack.cpu(), PROJECT)

    sampler = threading.Thread(target=cpu, name="cpu", daemon=True)
    sampler.start()
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
        result.unavailable += unavailable(placed)
        if placed.status_code == 201:
            began = time.perf_counter()
            canceled = trader.command(f"/api/v1/orders/{placed.json()['orderId']}/cancel")
            result.probe.append(("cancel", time.perf_counter() - began, canceled.status_code))
            result.unavailable += unavailable(canceled)
        if tick % 5 == 0:
            execution = stack.scrape()
            result.queue.append((now, stats.total(execution, "mse_writer_queue_depth")))
            result.feeds.append((now, listing()["feed"]["state"]))
            sampled.scrape(stack.scrape("market-data-service"), execution)
        if tick % 15 == 0:
            pending = stack.sql(
                "execution_owner",
                "execution_db",
                "SELECT count(*) FROM outbox WHERE published_at IS NULL",
            )
            result.outbox.append((now, int(pending)))
        tick += 1
        time.sleep(max(0.0, start + tick - time.monotonic()))
    done.set()
    sampler.join(timeout=70)
    return result


def unavailable(response: httpx.Response) -> int:
    """1 for a 503 that is not a busy refusal, which Execution's counters leave out."""
    if response.status_code != 503:
        return 0
    try:
        return 0 if response.json().get("code") == "EXCHANGE_BUSY" else 1
    except ValueError:
        return 1


def restart_line(restarted: float) -> str:
    if math.isnan(restarted):
        return "- Restarting Execution: not done, since the checks did not run."
    return f"- Restarting Execution, its reconciliation included: {restarted:.0f} s."


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


def correctness(stack: Stack, instruments: list[str]) -> tuple[dict, float]:
    """After the stop: the books and ledger reload and reconcile, and the public side matches
    exactly. Also how long the restart took, its reconciliation included."""
    checks = {}
    began = time.monotonic()
    stack.compose("restart", "execution-service")
    try:
        stack.compose(
            "up", "-d", "--no-deps", "--wait", "--wait-timeout", "240", "execution-service"
        )
        checks["Execution restarts and its startup reconciliation passes"] = True
    except RuntimeError:
        checks["Execution restarts and its startup reconciliation passes"] = False
    restarted = time.monotonic() - began
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
    return checks, restarted


def summarize(
    options: Options,
    before: Snapshot,
    after: Snapshot,
    window: Window,
    env: dict,
    checks: dict,
    phases: Phases,
    where: list[str] | None = None,
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
        "## Starting and stopping",
        "",
        f"- Provisioning {options.bots} bots in batches of 100: {phases.provisioned:.0f} s.",
        f"- From starting the run to its first commands counted, while the simulator takes the "
        f"lease and loads its bots (heartbeats come every few seconds): {phases.loaded:.0f} s.",
        f"- From asking the run to stop to STOPPED, after its open orders are cancelled: "
        f"{phases.stopped:.0f} s.",
        f"- Simulator memory at the end of the window: {phases.simulator_memory}.",
        "",
        *(where or []),
        "## Pipeline during the window",
        "",
        f"- Writer queue depth: first minute {queue.first_minute:.1f}, last minute "
        f"{queue.last_minute:.1f}, peak {queue.peak:.0f} ({len(window.queue)} samples).",
        f"- Outbox unpublished: peak {max((n for _, n in window.outbox), default=0)} "
        f"({len(window.outbox)} samples).",
        f"- Public feed not LIVE in {stale} of {len(window.feeds)} samples.",
        "",
        *environment_lines(env),
    ]
    return "\n".join(lines), passed


def environment_lines(
    env: dict, generator: str = "the simulator container on the same host"
) -> list[str]:
    return [
        "## Environment",
        "",
        f"- Host: {env['host']}; Docker {env['docker']}; {env['cpus']} CPUs and "
        f"{env['memory_gib']} GiB "
        f"visible to Docker. Generator: {generator}.",
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


def begin_run(operator: Session, options: Options, rate: str, minutes: int) -> dict:
    """Starts a bot run with every bot, in the split the bots were tagged with."""
    split = strategy_split(options.bots)
    return operator.expect(
        201,
        "/api/v1/admin/bot-runs",
        {
            "marketMakers": split["MARKET_MAKER"],
            "momentum": split["MOMENTUM"],
            "noise": split["NOISE"],
            "seed": options.seed,
            "commandsPerSecond": rate,
            "maxOrderQuantity": 10,
            "maxPosition": 200,
            "durationMinutes": minutes,
        },
    )


def run_ramp(options: Options) -> int:
    """One run after another at rising rates on one market and its bots, each warmed up, measured
    and stopped; the ramp ends at the first rate that misses a budget. The accounting checks run
    once, after the last step."""
    assert options.ramp is not None
    stack = Stack(options.root)
    logger.info("starting a clean stack as Compose project %s", PROJECT)
    stack.compose("down", "-v", "--remove-orphans", check=False)
    try:
        stack.compose("up", "-d", "--build", "--wait", "--wait-timeout", "600", timeout=1800)
        stack.sql("postgres", "execution_db", "CREATE EXTENSION IF NOT EXISTS pg_stat_statements")
        operator = Session("operator", stack.passwords["operator"])
        instruments = set_up(operator, options.bots, stack.passwords["trader"])
        wait_for(
            "Market Data to list the instruments",
            lambda: len(listing()["instruments"]) >= len(SYMBOLS),
            120,
        )
        trader = Session("trader", stack.passwords["trader"])
        minutes = (
            math.ceil(
                (max(options.step_warmup, FIRST_WARMUP) + options.step_duration + LOADING) / 60
            )
            + 3
        )
        steps: list[ramp.Step] = []
        skipped: list[float] = []
        broke = None
        stopped = True
        for index, rate in enumerate(options.ramp):
            try:
                warmup = (
                    max(options.step_warmup, FIRST_WARMUP) if index == 0 else options.step_warmup
                )
                result = ramp_step(
                    stack, operator, trader, instruments[0], options, rate, minutes, warmup
                )
            except Exception as error:  # noqa: BLE001 - the steps before it are still reported
                # The rate most likely to break the measurement is the one that overloads the
                # exchange; the steps measured before it are kept and reported.
                logger.exception(
                    "step %d at %.0f commands/s could not be measured", index + 1, rate
                )
                broke = f"{rate:,.0f}/s ({type(error).__name__}: {error})"
                skipped = options.ramp[index + 1 :]
                stopped = stop_any_run(operator)
                break
            steps.append(result)
            logger.info(
                "step %d: offered %.1f/s, committed %.1f/s, %s",
                index + 1,
                result.offered_rate,
                result.committed_rate,
                "met every budget" if result.passed else "missed: " + ", ".join(result.missed()),
            )
            if not result.passed:
                skipped = options.ramp[index + 1 :]
                break
        env = read_environment(stack)
        if stopped:
            logger.info("checking accounting and the public side")
            checks, restarted = checked(stack, instruments)
        else:
            checks = {
                "The last run was confirmed stopped, so the accounting checks could run": False
            }
            restarted = math.nan
        lines = [
            f"# Ramp: {datetime.now(UTC):%Y-%m-%d %H:%M} UTC",
            "",
            f"- {len(SYMBOLS)} instruments; {options.bots} bots "
            f"({split_text(strategy_split(options.bots))}), seed {options.seed}.",
            f"- Each step: a new run at its rate, {options.step_warmup} s of warm-up (the first "
            f"{max(options.step_warmup, FIRST_WARMUP)} s), then "
            f"{options.step_duration} s measured, then stopped.",
            "",
            *ramp.report(steps, skipped, broke),
            "## After the last step",
            "",
            *[f"- {name}: {'yes' if met else 'no'}" for name, met in checks.items()],
            restart_line(restarted),
            "",
            *environment_lines(env),
        ]
        report = "\n".join(lines)
        path = stack.run_dir / f"ramp-{datetime.now(UTC):%Y%m%d-%H%M}.md"
        path.write_text(report, encoding="utf-8")
        print(report)
        logger.info("report written to %s", path)
        return 0 if all(checks.values()) and ramp.highest_passing(steps) is not None else 1
    finally:
        if options.keep_stack:
            logger.info("stack kept: docker compose -p %s", PROJECT)
        else:
            stack.compose("down", "-v", "--remove-orphans", check=False)


def ramp_step(
    stack: Stack,
    operator: Session,
    trader: Session,
    instrument: str,
    options: Options,
    rate: float,
    minutes: int,
    warmup: int,
) -> ramp.Step:
    """One step: a new run at the rate, warmed up, measured and stopped."""
    started = begin_run(operator, options, f"{rate:.2f}", minutes)
    logger.info("%.0f commands/s: run %s", rate, started["runId"])
    wait_for("the run to be RUNNING", lambda: activity(operator)["status"] == "RUNNING", 180)
    wait_for("the bots to trade", lambda: activity(operator)["activity"]["placed"] > 0, LOADING)
    time.sleep(warmup)
    before = snapshot(stack, operator)
    sampled = diagnostics.Sampled()
    window = measure(stack, operator, trader, instrument, options.step_duration, sampled)
    after = snapshot(stack, operator)
    operator.expect(200, f"/api/v1/admin/bot-runs/{started['runId']}/stop")
    wait_for("the run to stop", lambda: activity(operator)["status"] == "STOPPED", 600)
    return ramp.step(
        rate,
        before.metrics,
        after.metrics,
        before.activity,
        after.activity,
        after.at - before.at,
        window.probe,
        window.queue,
        sampled,
        window.unavailable,
    )


def stop_any_run(operator: Session) -> bool:
    """Stops the latest run after a step broke, sending the stop until it is taken and waiting for
    the worker to confirm (STOPPING becomes STOPPED). False if that could not be confirmed, so the
    accounting checks do not run beside a run still trading."""
    try:
        latest = activity(operator)
        if latest["status"] == "STOPPED":
            return True

        def stop_taken() -> bool:
            status = operator.command(f"/api/v1/admin/bot-runs/{latest['runId']}/stop").status_code
            # 409: the run is not running, already stopping or stopped.
            return status in (200, 409)

        wait_for("the stop to be taken", stop_taken, 120)
        wait_for("the run to stop", lambda: activity(operator)["status"] == "STOPPED", 600)
        return True
    except Exception:  # noqa: BLE001 - the report is still written
        logger.exception("could not stop the run")
        return False


def read_environment(stack: Stack) -> dict:
    """The environment for the report, or placeholders if it could not be read: the measured
    rates are reported either way."""
    try:
        return environment(stack)
    except Exception:  # noqa: BLE001 - the report is still written
        logger.exception("the environment could not be read")
        unknown = "not read"
        return {
            key: unknown
            for key in ("host", "docker", "cpus", "memory_gib", "commit", "java", "jvm_args")
        } | {"postgres": unknown, "limits": {}}


def checked(stack: Stack, instruments: list[str]) -> tuple[dict, float]:
    """The accounting checks, or one failed check if they could not run at all."""
    try:
        return correctness(stack, instruments)
    except Exception as error:  # noqa: BLE001 - the measured steps are still reported
        logger.exception("the accounting checks could not run")
        return {f"The accounting checks ran ({type(error).__name__}: {error})": False}, math.nan


def run_stress(options: Options) -> int:
    """Open-loop stress on a market with no bots: trader logins send commands at fixed times, at
    each rate in turn, ending at the first rate that misses a budget. The accounting checks run
    once, after the last rate."""
    assert options.stress is not None
    stack = Stack(options.root)
    logger.info("starting a clean stack as Compose project %s", PROJECT)
    stack.compose("down", "-v", "--remove-orphans", check=False)
    try:
        stack.compose("up", "-d", "--build", "--wait", "--wait-timeout", "600", timeout=1800)
        operator = Session("operator", stack.passwords["operator"])
        # Traders can only be added before the market opens, which set_up does last.
        credentials = [
            (f"stress-{index:04d}", secrets.token_urlsafe(24))
            for index in range(stress.traders(max(options.stress)))
        ]
        instruments = set_up(operator, 0, stack.passwords["trader"], credentials)
        logger.info("%d trader logins added", len(credentials))
        wait_for(
            "Market Data to list the instruments",
            lambda: len(listing()["instruments"]) >= len(SYMBOLS),
            120,
        )
        logins = [
            (username, password, instruments[index % len(instruments)])
            for index, (username, password) in enumerate(credentials)
        ]
        results, skipped, broke = asyncio.run(stress_rates(options, logins))
        env = read_environment(stack)
        logger.info("checking accounting and the public side")
        checks, restarted = checked(stack, instruments)
        lines = [
            f"# Stress: {datetime.now(UTC):%Y-%m-%d %H:%M} UTC",
            "",
            f"- {len(SYMBOLS)} instruments, no bots; each trader login sends at most "
            f"{stress.PER_TRADER} commands a second, from this machine through port {WEB_PORT}.",
            f"- Each rate: {options.stress_warmup} s of warm-up (the first "
            f"{max(options.stress_warmup, FIRST_WARMUP)} s), then {options.stress_duration} s "
            "measured; latency counts from each command's planned time.",
            "",
            *stress.report(results, skipped, broke),
            "## After the last rate",
            "",
            *[f"- {name}: {'yes' if met else 'no'}" for name, met in checks.items()],
            restart_line(restarted),
            "",
            *environment_lines(env, f"this machine, through the proxy on port {WEB_PORT}"),
        ]
        report = "\n".join(lines)
        path = stack.run_dir / f"stress-{datetime.now(UTC):%Y%m%d-%H%M}.md"
        path.write_text(report, encoding="utf-8")
        print(report)
        logger.info("report written to %s", path)
        return 0 if all(checks.values()) and stress.highest_passing(results) is not None else 1
    finally:
        if options.keep_stack:
            logger.info("stack kept: docker compose -p %s", PROJECT)
        else:
            stack.compose("down", "-v", "--remove-orphans", check=False)


async def stress_rates(
    options: Options, logins: list[tuple[str, str, str]]
) -> tuple[list[stress.Result], list[float], str | None]:
    """Signs every login in, then fires each rate in turn, each starting with no open orders. A
    rate that cannot be measured ends the run; the rates before it are still returned."""
    assert options.stress is not None
    base_url = f"http://{HOST}:{WEB_PORT}"
    gate = asyncio.Semaphore(8)
    traders: list[stress.Trader] = []

    async def sign_in(username: str, password: str, instrument: str) -> None:
        async with gate:
            traders.append(await stress.sign_in(base_url, username, password, instrument))

    async def tidy(trader: stress.Trader) -> None:
        async with gate:
            await trader.tidy()

    results: list[stress.Result] = []
    skipped: list[float] = []
    broke = None
    try:
        await asyncio.gather(*(sign_in(*login) for login in logins))
        for index, rate in enumerate(options.stress):
            logger.info("rate %d: %.0f commands/s", index + 1, rate)
            warmup = (
                max(options.stress_warmup, FIRST_WARMUP) if index == 0 else options.stress_warmup
            )
            try:
                await asyncio.gather(*(tidy(trader) for trader in traders))
                sent = await stress.fire(
                    traders[: stress.traders(rate)],
                    rate,
                    warmup + options.stress_duration,
                )
            except Exception as error:  # noqa: BLE001 - the rates before it are still reported
                logger.exception(
                    "rate %d at %.0f commands/s could not be measured", index + 1, rate
                )
                broke = f"{rate:,.0f}/s ({type(error).__name__}: {error})"
                skipped = options.stress[index + 1 :]
                break
            result = stress.measured(rate, warmup, options.stress_duration, sent)
            results.append(result)
            logger.info(
                "rate %d: answered %.1f/s, p99 %s, %s",
                index + 1,
                result.answered_rate,
                result.latency(0.99),
                "met every budget" if result.passed else "missed: " + ", ".join(result.missed()),
            )
            if not result.passed:
                skipped = options.stress[index + 1 :]
                break
            # Let the writer's queue empty before the next rate.
            await asyncio.sleep(5)
    finally:
        await asyncio.gather(*(trader.client.aclose() for trader in traders))
    return results, skipped, broke


def run(options: Options) -> int:
    if options.ramp is not None:
        return run_ramp(options)
    if options.stress is not None:
        return run_stress(options)
    stack = Stack(options.root)
    logger.info("starting a clean stack as Compose project %s", PROJECT)
    stack.compose("down", "-v", "--remove-orphans", check=False)
    try:
        stack.compose("up", "-d", "--build", "--wait", "--wait-timeout", "600", timeout=1800)
        stack.sql("postgres", "execution_db", "CREATE EXTENSION IF NOT EXISTS pg_stat_statements")
        operator = Session("operator", stack.passwords["operator"])
        phases = Phases()
        began = time.monotonic()
        instruments = set_up(operator, options.bots, stack.passwords["trader"])
        phases.provisioned = time.monotonic() - began
        wait_for(
            "Market Data to list the instruments",
            lambda: len(listing()["instruments"]) >= len(SYMBOLS),
            120,
        )
        trader = Session("trader", stack.passwords["trader"])
        # Room for the bots to load (up to LOADING seconds) as well as the warm-up and the window;
        # the run is stopped by hand at the end anyway.
        minutes = math.ceil((options.warmup + options.duration + LOADING) / 60) + 3
        started = begin_run(operator, options, options.rate, minutes)
        logger.info("run %s started; waiting for a worker", started["runId"])
        began = time.monotonic()
        wait_for("the run to be RUNNING", lambda: activity(operator)["status"] == "RUNNING", 180)
        # The lease is taken before the bots load, so loading ends when the first commands are
        # counted, which the next heartbeat reports.
        wait_for("the bots to trade", lambda: activity(operator)["activity"]["placed"] > 0, LOADING)
        phases.loaded = time.monotonic() - began
        logger.info("warming up for %d s", options.warmup)
        time.sleep(options.warmup)
        database_before = stack.database()
        before = snapshot(stack, operator)
        logger.info("measuring for %d s", options.duration)
        sampled = diagnostics.Sampled()
        window = measure(stack, operator, trader, instruments[0], options.duration, sampled)
        after = snapshot(stack, operator)
        database_after = stack.database()
        phases.simulator_memory = stack.memory("simulator")
        operator.expect(200, f"/api/v1/admin/bot-runs/{started['runId']}/stop")
        began = time.monotonic()
        wait_for("the run to stop", lambda: activity(operator)["status"] == "STOPPED", 600)
        phases.stopped = time.monotonic() - began
        env = environment(stack)
        logger.info("checking accounting and the public side")
        payloads = diagnostics.outbox(
            stack.sql("execution_owner", "execution_db", diagnostics.OUTBOX)
        )
        checks, restarted = correctness(stack, instruments)
        split = diagnostics.writer_split(before.metrics, after.metrics)
        where = diagnostics.report(
            split,
            diagnostics.database_window(database_before, database_after, split.commands),
            diagnostics.reads_per_second(before.metrics, after.metrics, after.at - before.at),
            sampled,
            payloads,
            restarted,
        )
        report, passed = summarize(options, before, after, window, env, checks, phases, where)
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
    parser.add_argument("--bots", type=int, default=10_000)
    parser.add_argument("--warmup", type=int, default=120, help="seconds before measuring")
    parser.add_argument("--duration", type=int, default=600, help="seconds measured")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--keep-stack", action="store_true")
    parser.add_argument(
        "--ramp",
        nargs="?",
        const="100,125,150,175,200,250,300,400,500,750,1000",
        help="instead of one steady run, a run at each of these rising rates (commands/s, "
        "comma-separated; default 100 to 1000), ending at the first that misses a budget",
    )
    parser.add_argument("--step-warmup", type=int, default=30, help="seconds before each step")
    parser.add_argument("--step-duration", type=int, default=120, help="seconds each step measures")
    parser.add_argument(
        "--stress",
        nargs="?",
        const="100,125,150,175,200,250,300,400,500,750,1000,1500,2000",
        help="instead of bots, trader logins sending at fixed times at each of these rising rates "
        "(commands/s, comma-separated; default 100 to 2000), stopping at the first that misses "
        "a budget",
    )
    parser.add_argument("--stress-warmup", type=int, default=10, help="seconds before each rate")
    parser.add_argument(
        "--stress-duration", type=int, default=60, help="seconds each rate measures"
    )
    args = parser.parse_args(argv)
    if args.ramp is not None and args.stress is not None:
        parser.error("--ramp and --stress are separate runs; choose one")
    if min(args.warmup, args.step_warmup, args.stress_warmup) < 0:
        parser.error("a warm-up cannot be negative")
    if args.stress_duration < 1:
        parser.error("--stress-duration must be at least 1 s")
    if args.step_duration < ramp.MIN_STEP_SECONDS:
        parser.error(
            f"--step-duration must be at least {ramp.MIN_STEP_SECONDS} s, so that its first and "
            "last thirds can show the queue growing"
        )
    root = Path.cwd()
    if not (root / "compose.yaml").exists():
        parser.error("run from the repository root, where compose.yaml is")
    try:
        rates = None if args.ramp is None else ramp.rates(args.ramp)
        stress_rates = None if args.stress is None else ramp.rates(args.stress)
    except ValueError as error:
        parser.error(str(error))
    return Options(
        root,
        args.rate,
        args.bots,
        args.warmup,
        args.duration,
        args.seed,
        args.keep_stack,
        rates,
        args.step_warmup,
        args.step_duration,
        stress_rates,
        args.stress_warmup,
        args.stress_duration,
    )


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
    sys.exit(run(parse(sys.argv[1:])))
