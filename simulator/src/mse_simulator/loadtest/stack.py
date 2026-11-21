"""A throwaway stack to measure on, and the operator's session with it: a Compose project with
its own volumes and fresh random passwords, set up as an operator sets up a market, with bot runs
started and stopped through the API. Given a project name and ports no other stack uses, a
development stack or a gate run is never touched."""

import json
import logging
import math
import os
import platform
import secrets
import subprocess
import time
import uuid
from dataclasses import dataclass
from pathlib import Path

import httpx

from mse_simulator.loadtest import diagnostics, manifests, stats
from mse_simulator.loadtest.ramp import MAX_RATE, MIN_RATE

logger = logging.getLogger(__name__)

SYMBOLS = ("ALPHA", "BRAVO", "CHARLIE", "DELTA", "ECHO")
# Not "localhost": on Windows that tries IPv6 first and waits about two seconds before falling back,
# which would show up as client latency whenever a connection is reopened.
HOST = "127.0.0.1"


@dataclass(frozen=True)
class Ports:
    """The host ports a stack publishes, apart from every other stack's."""

    web: int
    postgres: int
    kafka: int
    flink_ui: int


class Stack:
    """A throwaway Compose project and the commands run inside it. Its files, the passwords among
    them, go to runtime/<directory>/; ``overlays`` are Compose files laid over compose.yaml."""

    def __init__(
        self,
        root: Path,
        project: str,
        directory: str,
        ports: Ports,
        overlays: tuple[str, ...] = (),
        env_name: str = "stack.env",
    ) -> None:
        self.root = root
        self.project = project
        self.ports = ports
        self.overlays = overlays
        self.run_dir = root / "runtime" / directory
        self.run_dir.mkdir(parents=True, exist_ok=True)
        self.env_file = self.run_dir / env_name
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
            f"WEB_HOST_PORT={ports.web}",
            f"POSTGRES_HOST_PORT={ports.postgres}",
            f"KAFKA_HOST_PORT={ports.kafka}",
            f"FLINK_UI_HOST_PORT={ports.flink_ui}",
            # The commit the simulator image is built from, which its worker reports with each run.
            f"MSE_SIMULATOR_REVISION={revision(root)}",
        ]
        self.env_file.write_text("\n".join(lines) + "\n", encoding="utf-8")
        try:
            os.chmod(self.env_file, 0o600)
        except OSError:
            pass

    @property
    def web_url(self) -> str:
        """The stack's reverse proxy, which every client goes through."""
        return f"http://{HOST}:{self.ports.web}"

    def compose(self, *args: str, check: bool = True, timeout: float = 900) -> str:
        command = [
            "docker",
            "compose",
            "-p",
            self.project,
            "--env-file",
            str(self.env_file),
            "-f",
            "compose.yaml",
            *(item for overlay in self.overlays for item in ("-f", str(Path(overlay)))),
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

    def __init__(self, username: str, password: str, base_url: str) -> None:
        self.http = httpx.Client(base_url=base_url, timeout=15.0)
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
    split: dict[str, int],
    trader_password: str,
    logins: list[tuple[str, str]] | None = None,
) -> list[str]:
    """Five instruments at 100.00, a trader for the probe, added and funded as the operator adds
    one, any further trader logins (username, password) with cash only, the bots tagged by
    strategy as ``split`` counts them, and an open market. Returns the instruments' ids."""
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
    for strategy, total in split.items():
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


def start_run(
    operator: Session,
    split: dict[str, int],
    seed: int,
    rate: str,
    minutes: int,
    max_order_quantity: int = 10,
    max_position: int = 200,
) -> dict:
    """Starts a bot run on every instrument with the oldest bots tagged with each strategy, as many
    as ``split`` counts, at ``rate`` commands a second in all; it ends by itself after
    ``minutes``."""
    return operator.expect(
        201,
        "/api/v1/admin/bot-runs",
        {
            "marketMakers": split["MARKET_MAKER"],
            "momentum": split["MOMENTUM"],
            "noise": split["NOISE"],
            "seed": seed,
            "commandsPerSecond": rate,
            "maxOrderQuantity": max_order_quantity,
            "maxPosition": max_position,
            "durationMinutes": minutes,
        },
    )


def run_rate(text: str) -> str:
    """A run rate as the API takes it, with two decimals: "225" becomes "225.00". Refused here,
    before a stack is started, if the API would refuse it."""
    try:
        value = float(text)
    except ValueError as error:
        raise ValueError(f"--rate is not a number: {text!r}") from error
    if not math.isfinite(value):
        raise ValueError(f"--rate is not a finite number: {text!r}")
    if round(value, 2) != value:
        raise ValueError(f"--rate takes at most two decimals: {text!r}")
    if not MIN_RATE <= value <= MAX_RATE:
        raise ValueError(f"--rate must be from {MIN_RATE} to {MAX_RATE:.0f}")
    return f"{value:.2f}"


def listing(base_url: str) -> dict:
    return httpx.get(f"{base_url}/api/v1/instruments", timeout=10.0).json()


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


def read_manifest(operator: Session, run_id: str) -> manifests.Reading:
    """The manifest the run's worker reported, None if none did, or why it could not be read: the
    report is written either way."""
    try:
        response = operator.http.get(f"/api/v1/admin/bot-runs/{run_id}")
        response.raise_for_status()
        return response.json()["run"]["manifest"]
    except httpx.HTTPStatusError as error:
        # Its message runs over two lines with a link; the status says it.
        return f"HTTP {error.response.status_code}"
    except (httpx.HTTPError, KeyError, TypeError, ValueError) as error:
        return f"{type(error).__name__}: {error}"


def revision(root: Path) -> str:
    """The checkout's revision label for the simulator image, or empty without git."""

    def git(*args: str) -> str:
        return subprocess.run(
            ["git", *args], cwd=root, capture_output=True, text=True, check=True
        ).stdout.strip()

    try:
        return manifests.revision_label(
            git("rev-parse", "--short=12", "HEAD"),
            git("status", "--porcelain", "--untracked-files=no"),
        )
    except OSError, subprocess.CalledProcessError:
        return ""


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
        "admission": admission_limits(stack.scrape()),
    }


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


def admission_limits(execution: diagnostics.Samples) -> tuple[float, float] | None:
    """The publication backlog, in events and bytes, beyond which Execution refuses new orders, as
    it reports them; None if it does not."""
    names = {(name, dict(labels).get("limit")) for name, labels in execution}
    if not {("mse_outbox_admission_limit", "events"), ("mse_outbox_admission_limit", "bytes")} <= (
        names
    ):
        return None
    return (
        stats.total(execution, "mse_outbox_admission_limit", limit="events"),
        stats.total(execution, "mse_outbox_admission_limit", limit="bytes"),
    )


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
        admission_line(env.get("admission")),
        "",
    ]


def admission_line(limits: tuple[float, float] | None) -> str:
    if limits is None:
        return "- Publication backlog limit: not read."
    events, size = limits
    return (
        f"- Publication backlog limit, beyond which new orders are refused: {events:,.0f} events "
        f"or {size / 2**20:,.0f} MiB."
    )
