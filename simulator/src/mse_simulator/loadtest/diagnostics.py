"""Where a command's time goes during the load test: the database's own statistics before and after
the window, the writer's timers, read rates, the feed's lag and each container's processor. Pure
functions over captured text, so they can be tested on recorded output; the load test captures and
reports."""

from collections.abc import Mapping
from dataclasses import dataclass, field

from mse_simulator.loadtest import stats

Samples = Mapping[tuple[str, stats.Labels], float]

# psql -At output: one row a line, columns separated by "|".
# Grouped by query and level, which is unique: a statement Execution sent is top-level; one run
# inside a trigger (a foreign-key check, say) is nested, and its time is inside its parent's.
STATEMENTS = """
SELECT s.queryid || ':' || s.toplevel, sum(s.calls), sum(s.total_exec_time),
       max(left(regexp_replace(s.query, '\\s+', ' ', 'g'), 120)), s.toplevel
FROM pg_stat_statements s JOIN pg_database d ON d.oid = s.dbid
WHERE d.datname = 'execution_db'
GROUP BY s.queryid, s.toplevel"""

WAL_IO = """
SELECT coalesce(sum(fsyncs), 0), coalesce(sum(fsync_time), 0),
       coalesce(sum(writes), 0), coalesce(sum(write_time), 0)
FROM pg_stat_io WHERE object = 'wal'"""

WAL = "SELECT wal_records, wal_fpi, wal_bytes FROM pg_stat_wal"

CHECKPOINTER = (
    "SELECT num_timed, num_requested, write_time, sync_time, buffers_written"
    " FROM pg_stat_checkpointer"
)

HOT_TABLES = (
    "simulation",
    "account",
    "cash_balance",
    "position",
    "orders",
    "outbox",
    "command_result",
)

TABLES = f"""
SELECT relname, n_tup_upd, n_tup_hot_upd, n_dead_tup FROM pg_stat_user_tables
WHERE relname IN ({", ".join(f"'{name}'" for name in HOT_TABLES)})"""

OUTBOX = "SELECT topic, count(*), avg(octet_length(payload))::bigint FROM outbox GROUP BY topic"

PHASES = ("queue_wait", "begin", "work", "commit", "apply")


def rows(text: str) -> list[list[str]]:
    """psql -At output as rows of columns; blank lines and anything without a separator where one is
    expected are left to the caller."""
    return [line.split("|") for line in text.strip().splitlines() if line.strip()]


@dataclass(frozen=True)
class Statement:
    calls: int
    total_ms: float
    text: str
    nested: bool = False


def statements(text: str) -> dict[str, Statement]:
    return {
        row[0]: Statement(int(row[1]), float(row[2]), row[3], row[4] == "f")
        for row in rows(text)
        if len(row) >= 5
    }


@dataclass(frozen=True)
class Database:
    """One capture of the database's statistics."""

    statements: dict[str, Statement]
    wal_io: tuple[float, float, float, float]
    wal: tuple[float, float, float]
    checkpointer: tuple[float, float, float, float, float]
    tables: dict[str, tuple[float, float, float]]


def database(
    statement_text: str, wal_io: str, wal: str, checkpointer: str, tables: str
) -> Database:
    return Database(
        statements(statement_text),
        tuple(float(v) for v in rows(wal_io)[0]),  # type: ignore[arg-type]
        tuple(float(v) for v in rows(wal)[0]),  # type: ignore[arg-type]
        tuple(float(v) for v in rows(checkpointer)[0]),  # type: ignore[arg-type]
        {row[0]: (float(row[1]), float(row[2]), float(row[3])) for row in rows(tables)},
    )


@dataclass(frozen=True)
class Top:
    calls_per_command: float
    mean_ms: float
    share: float
    text: str


@dataclass(frozen=True)
class DatabaseWindow:
    statements_per_command: float
    server_ms_per_command: float
    nested_per_command: float
    top: list[Top]
    fsyncs_per_command: float
    fsync_ms: float | None
    wal_bytes_per_command: float
    full_page_images_per_command: float
    checkpoints_timed: int
    checkpoints_requested: int
    tables: dict[str, tuple[float | None, float]]


def database_window(
    before: Database, after: Database, committed: float, top: int = 8
) -> DatabaseWindow:
    """What the database did between two captures, per committed command. Statements first seen
    after the window starts count from zero. Only top-level statements count as Execution's: nested
    ones (trigger work such as foreign-key checks) are counted apart, and their time is not added,
    since it is already inside the statement that fired them."""
    per = max(committed, 1.0)
    moved: list[tuple[str, int, float]] = []
    nested = 0
    for key, now in after.statements.items():
        was = before.statements.get(key, Statement(0, 0.0, now.text, now.nested))
        calls = now.calls - was.calls
        if calls <= 0:
            continue
        if now.nested:
            nested += calls
        else:
            moved.append((now.text, calls, now.total_ms - was.total_ms))
    server = sum(ms for _, _, ms in moved)
    ranked = sorted(moved, key=lambda item: item[2], reverse=True)[:top]
    fsyncs = after.wal_io[0] - before.wal_io[0]
    fsync_time = after.wal_io[1] - before.wal_io[1]
    tables = {}
    for name, (updated, hot, dead) in after.tables.items():
        was_updated, was_hot, _ = before.tables.get(name, (0.0, 0.0, 0.0))
        updates = updated - was_updated
        tables[name] = ((hot - was_hot) / updates if updates > 0 else None, dead)
    return DatabaseWindow(
        statements_per_command=sum(calls for _, calls, _ in moved) / per,
        server_ms_per_command=server / per,
        nested_per_command=nested / per,
        top=[
            Top(calls / per, ms / calls, ms / server if server > 0 else 0.0, text)
            for text, calls, ms in ranked
        ],
        fsyncs_per_command=fsyncs / per,
        fsync_ms=fsync_time / fsyncs if fsyncs > 0 else None,
        wal_bytes_per_command=(after.wal[2] - before.wal[2]) / per,
        full_page_images_per_command=(after.wal[1] - before.wal[1]) / per,
        checkpoints_timed=int(after.checkpointer[0] - before.checkpointer[0]),
        checkpoints_requested=int(after.checkpointer[1] - before.checkpointer[1]),
        tables=tables,
    )


@dataclass(frozen=True)
class WriterSplit:
    """Mean milliseconds per phase, by command, over the window, and the rate one writer could
    sustain at the measured service time."""

    means: dict[str, dict[str, float]]
    service_ms: float | None
    ceiling: float | None


def writer_split(before: Samples, after: Samples) -> WriterSplit:
    means: dict[str, dict[str, float]] = {}
    weighted = 0.0
    commands = 0.0
    for command in ("place", "cancel"):
        means[command] = {}
        service = 0.0
        for phase in PHASES:
            name = f"mse_writer_{phase}_seconds"
            count = stats.total(after, f"{name}_count", command=command) - stats.total(
                before, f"{name}_count", command=command
            )
            seconds = stats.total(after, f"{name}_sum", command=command) - stats.total(
                before, f"{name}_sum", command=command
            )
            mean = seconds / count * 1000 if count > 0 else 0.0
            means[command][phase] = mean
            if phase != "queue_wait":
                service += mean
        done = stats.total(after, "mse_writer_commit_seconds_count", command=command) - stats.total(
            before, "mse_writer_commit_seconds_count", command=command
        )
        weighted += service * done
        commands += done
    service_ms = weighted / commands if commands > 0 else None
    ceiling = 1000 / service_ms if service_ms else None
    return WriterSplit(means, service_ms, ceiling)


def reads_per_second(before: Samples, after: Samples, seconds: float) -> dict[str, float]:
    """GET requests a second by URI, from Spring's request counter."""
    rates: dict[str, float] = {}
    for (name, labels), value in after.items():
        if name != "http_server_requests_seconds_count":
            continue
        tags = dict(labels)
        if tags.get("method") != "GET":
            continue
        uri = tags.get("uri", "?")
        earlier = before.get((name, labels), 0.0)
        rates[uri] = rates.get(uri, 0.0) + (value - earlier) / seconds
    return {uri: rate for uri, rate in sorted(rates.items()) if rate > 0}


@dataclass
class Sampled:
    """Values sampled during the window."""

    feed_lag: dict[str, list[float]] = field(default_factory=dict)
    pending: list[float] = field(default_factory=list)
    cpu: dict[str, list[float]] = field(default_factory=dict)

    def scrape(self, market_data: Samples, execution: Samples) -> None:
        for (name, labels), value in market_data.items():
            if name == "mse_feed_lag" and value >= 0:
                self.feed_lag.setdefault(dict(labels).get("topic", "?"), []).append(value)
        self.pending.append(stats.total(execution, "hikaricp_connections_pending"))

    def docker(self, text: str, project: str) -> None:
        """docker stats lines of "name|12.34%"; containers named <project>-<service>-<n>."""
        for line in text.strip().splitlines():
            name, _, percent = line.partition("|")
            if not name.startswith(f"{project}-") or not percent.endswith("%"):
                continue
            service = name[len(project) + 1 :].rsplit("-", 1)[0]
            self.cpu.setdefault(service, []).append(float(percent[:-1]))


def lag_by_topic(sampled: Sampled) -> dict[str, tuple[float, float]]:
    """Peak and last lag per topic."""
    return {topic: (max(values), values[-1]) for topic, values in sorted(sampled.feed_lag.items())}


def cpu_by_service(sampled: Sampled) -> dict[str, tuple[float, float]]:
    """Mean and peak processor per service, in percent of one CPU."""
    return {
        service: (sum(values) / len(values), max(values))
        for service, values in sorted(sampled.cpu.items())
        if values
    }


def outbox(text: str) -> dict[str, tuple[int, int]]:
    """Rows and mean payload bytes per topic."""
    return {row[0]: (int(row[1]), int(row[2] or 0)) for row in rows(text) if len(row) >= 3}


def report(
    split: WriterSplit,
    window: DatabaseWindow,
    reads: dict[str, float],
    sampled: Sampled,
    payloads: dict[str, tuple[int, int]],
    restart_seconds: float | None,
) -> list[str]:
    def ms(value: float | None) -> str:
        return "n/a" if value is None else f"{value:.2f} ms"

    lines = ["## Where the time goes", ""]
    lines += [
        "Means over the window, from the writer's timers (committed transactions only):",
        "",
        "| | Queue | Begin | Work | Commit | Apply |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    for command in ("place", "cancel"):
        m = split.means[command]
        lines.append(f"| {command} | " + " | ".join(ms(m[phase]) for phase in PHASES) + " |")
    ceiling = "n/a" if split.ceiling is None else f"{split.ceiling:.0f} commands a second"
    lines += [
        "",
        f"- Service time (begin to apply, weighted by commands): {ms(split.service_ms)}; one "
        f"writer at that time sustains about {ceiling}.",
        f"- Statements Execution sent, reads included, per committed command: "
        f"{window.statements_per_command:.1f}, taking the database "
        f"{ms(window.server_ms_per_command)}. The writer's own counts are pinned in its tests.",
        f"- Statements run inside triggers (foreign-key checks and the like) per committed "
        f"command: {window.nested_per_command:.1f}; their time is inside the statements above.",
        f"- WAL flushes on the whole server (Market Data's commits included) per committed "
        f"command: {window.fsyncs_per_command:.2f}; each flush took {ms(window.fsync_ms)}.",
        f"- WAL per command: {window.wal_bytes_per_command:,.0f} bytes, "
        f"{window.full_page_images_per_command:.2f} full-page images.",
        f"- Checkpoints in the window: {window.checkpoints_timed} timed, "
        f"{window.checkpoints_requested} requested.",
        "- Restarting Execution, its reconciliation included: "
        + ("n/a" if restart_seconds is None else f"{restart_seconds:.0f} s")
        + ".",
        "",
        "Execution's statements taking the most database time, reads included:",
        "",
        "| Calls a command | Mean | Share | Statement |",
        "| --- | --- | --- | --- |",
    ]
    for item in window.top:
        text = item.text.replace("|", "\\|")
        lines.append(
            f"| {item.calls_per_command:.2f} | {ms(item.mean_ms)} | {item.share:.0%} | `{text}` |"
        )
    lines += ["", "Hot tables (share of updates that were HOT; dead rows at the end):", ""]
    lines += [
        f"- {name}: " + ("no updates" if hot is None else f"{hot:.0%} HOT") + f", {dead:,.0f} dead"
        for name, (hot, dead) in sorted(window.tables.items())
    ]
    lines += ["", "Reads a second, by URI:", ""]
    lines += [f"- {uri}: {rate:.1f}" for uri, rate in reads.items()] or ["- none counted"]
    lines += ["", "Feed lag, records not yet projected (peak, last):", ""]
    lines += [
        f"- {topic}: {peak:.0f}, {last:.0f}"
        for topic, (peak, last) in lag_by_topic(sampled).items()
    ] or ["- not sampled"]
    peak_pending = max(sampled.pending, default=0.0)
    lines += [
        "",
        f"Execution's database pool, waiting connections at peak: {peak_pending:.0f}.",
        "",
    ]
    lines += ["Processor by container, percent of one CPU (mean, peak):", ""]
    lines += [
        f"- {service}: {mean:.0f}%, {peak:.0f}%"
        for service, (mean, peak) in cpu_by_service(sampled).items()
    ] or ["- not sampled"]
    lines += ["", "Outbox at the end, rows and mean payload by topic:", ""]
    lines += [f"- {topic}: {count:,}, {size:,} bytes" for topic, (count, size) in payloads.items()]
    lines.append("")
    return lines
