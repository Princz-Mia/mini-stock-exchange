"""The load test's ramp: one bot run after another at rising rates, each measured on its own, and
the highest rate that met every budget. Pure functions over what each step captured, so the
verdicts can be tested on recorded numbers; the load test drives the runs."""

import math
from collections.abc import Mapping
from dataclasses import dataclass

from mse_simulator.loadtest import diagnostics, stats, stress

Samples = Mapping[tuple[str, stats.Labels], float]

LATENCY = "mse_commands_seconds_bucket"
REFUSED = "mse_commands_refused_total"
RATE_LIMITED = "mse_commands_rate_limited_total"
P95_BUDGET = 0.150
P99_BUDGET = 0.500
FAILURE_BUDGET = 0.001
# A step must be admitted at least this share of its rate, or it did not test that rate.
ADMITTED_SHARE = 0.9
# What a run may ask, in hundredths as the API takes it.
MIN_RATE = 0.01
MAX_RATE = 2000.0
# Probe answers Execution counts itself: a 500 (the command failed in the writer) under the
# histogram's failed outcome, a 503 busy refusal among the refusals. A 504 is not one: the command
# goes on and is usually recorded as committed, late, so the probe counts it. A 503 because the
# writer stopped is in no counter; the load test passes those in as unavailable.
COUNTED_BY_EXECUTION = frozenset({500, 503})
# The shortest step whose first and last thirds are apart enough to show the queue growing.
MIN_STEP_SECONDS = 60
ACTIVITY = ("placed", "canceled", "rejected", "errors")
# The containers whose processor the ramp reports; the decision after the ramp reads these.
CPU_SERVICES = ("execution-service", "postgres", "simulator", "kafka", "market-data-service")


def rates(text: str) -> list[float]:
    """The ramp's rates, from "150,200,300": rising, positive, and at most what a run may ask."""
    try:
        values = [float(part) for part in text.split(",") if part.strip()]
    except ValueError as error:
        raise ValueError(f"not a list of rates: {text!r}") from error
    if not values:
        raise ValueError("a ramp needs at least one rate")
    if any(not math.isfinite(value) or value < MIN_RATE or value > MAX_RATE for value in values):
        raise ValueError(f"each rate must be at least {MIN_RATE} and at most {MAX_RATE:.0f}")
    if any(later <= earlier for earlier, later in zip(values, values[1:], strict=False)):
        raise ValueError("the rates must rise")
    return values


@dataclass(frozen=True)
class Step:
    """One rate, measured over its window."""

    target: float
    seconds: float
    # What the bots report having sent, plus the probe's commands. The bots' totals change only
    # with their heartbeat (every 5 s), so this is a rough figure; the verdicts use admitted.
    offered: float
    # Commands Execution admitted (either outcome), counted as they finish; the probe's included.
    admitted: float
    probe: int
    committed: float
    within_p95: float
    within_p99: float
    p99: float | None
    queue: stats.QueueVerdict
    unexpected: float
    # Commands the exchange refused for load (busy, backlog, an account's rate limit), which were
    # never admitted. The bots retry them with the same key.
    refused: float
    service_ms: float | None
    # The publication backlog and the feeds' lag, judged between the window's thirds.
    downstream: diagnostics.Downstream
    # Mean and peak processor per service, in percent of one CPU.
    cpu: dict[str, tuple[float, float]]

    @property
    def lag_peak(self) -> float:
        return self.downstream.lag_peak

    @property
    def backlog_peak(self) -> float:
        return self.downstream.peak(diagnostics.BACKLOG)

    @property
    def offered_rate(self) -> float:
        return self.offered / self.seconds

    @property
    def admitted_rate(self) -> float:
        return self.admitted / self.seconds

    @property
    def simulator_ms(self) -> float | None:
        """The simulator's processor time per bot command admitted (the probe is not its)."""
        mean = self.cpu.get("simulator", (None, None))[0]
        bot_commands = self.admitted - self.probe
        if mean is None or bot_commands <= 0:
            return None
        return mean / 100 / (bot_commands / self.seconds) * 1000

    @property
    def committed_rate(self) -> float:
        return self.committed / self.seconds

    def verdicts(self) -> dict[str, bool]:
        return {
            "p95 within 150 ms": self.within_p95 >= 0.95,
            "p99 within 500 ms": self.within_p99 >= 0.99,
            "no sustained queue growth": not self.queue.grew,
            "refusals and failures below 0.1%": self.admitted + self.refused > 0
            and (self.unexpected + self.refused) / (self.admitted + self.refused) < FAILURE_BUDGET,
            f"admitted at least {ADMITTED_SHARE:.0%} of the rate": self.admitted_rate
            >= ADMITTED_SHARE * self.target,
            "publication and the feeds kept up": self.downstream.kept_up,
        }

    @property
    def passed(self) -> bool:
        return all(self.verdicts().values())

    def missed(self) -> list[str]:
        return [name for name, met in self.verdicts().items() if not met]


def step(
    target: float,
    before: Samples,
    after: Samples,
    activity_before: Mapping[str, int],
    activity_after: Mapping[str, int],
    seconds: float,
    probe: list[tuple[str, float, int]],
    queue: list[tuple[float, float]],
    sampled: diagnostics.Sampled,
    unavailable: int = 0,
) -> Step:
    """A step's numbers from Execution's scrapes and the run's activity at each end of its window,
    the probe trader's commands and the queue samples taken during it. The queue's growth, and the
    publication backlog's and each feed lag's, are judged between the window's first and last
    thirds."""
    histogram = stats.window(
        stats.bucket_counts(before, LATENCY), stats.bucket_counts(after, LATENCY)
    )
    committed = stats.window(
        stats.bucket_counts(before, LATENCY, outcome="committed"),
        stats.bucket_counts(after, LATENCY, outcome="committed"),
    )
    failed = stats.count(histogram) - stats.count(committed)
    bot = {key: activity_after[key] - activity_before[key] for key in ACTIVITY}
    probe_failures = sum(
        1 for _, _, status in probe if status >= 500 and status not in COUNTED_BY_EXECUTION
    )
    refused = sum(
        stats.total(after, name, **labels) - stats.total(before, name, **labels)
        for name, labels in (
            (REFUSED, {"reason": "busy"}),
            (REFUSED, {"reason": "backlog"}),
            (RATE_LIMITED, {}),
        )
    )
    return Step(
        target=target,
        seconds=seconds,
        offered=sum(bot.values()) + len(probe),
        admitted=stats.count(histogram),
        probe=len(probe),
        committed=stats.count(committed),
        within_p95=stats.share_within(histogram, P95_BUDGET),
        within_p99=stats.share_within(histogram, P99_BUDGET),
        p99=stats.percentile(histogram, 0.99),
        queue=stats.queue_growth(queue, span=seconds / 3),
        unexpected=failed + bot["errors"] + probe_failures + unavailable,
        refused=refused,
        service_ms=diagnostics.writer_split(before, after).service_ms,
        downstream=diagnostics.downstream(sampled, target, seconds / 3),
        cpu=diagnostics.cpu_by_service(sampled),
    )


def highest_passing(steps: list[Step]) -> Step | None:
    """The highest rate that met every budget, counting only the steps up to the first that did
    not: a rate above a failure is not trusted even if it happened to pass."""
    best = None
    for each in steps:
        if not each.passed:
            break
        best = each
    return best


def report(steps: list[Step], skipped: list[float], broke: str | None = None) -> list[str]:
    """The ramp's table; broke names a step that could not be measured, which ended the ramp."""

    def ms(value: float | None) -> str:
        return "n/a" if value is None else f"{value:.1f} ms"

    best = highest_passing(steps)
    lines = ["## The ramp", ""]
    lines.append(
        "**Highest rate that met every budget: "
        + ("none" if best is None else f"{best.target:,.0f} commands a second")
        + ".**"
    )
    lines += [
        "",
        "| Rate | Offered | Admitted | Committed | Within 150 ms | Within 500 ms | p99 | "
        "Queue peak | Refused | Service | Backlog peak | Feed lag peak | Met |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    for each in steps:
        met = "yes" if each.passed else "no: " + ", ".join(each.missed())
        p99 = None if each.p99 is None else each.p99 * 1000
        lines.append(
            f"| {each.target:,.0f}/s | {each.offered_rate:,.1f}/s | {each.admitted_rate:,.1f}/s | "
            f"{each.committed_rate:,.1f}/s | "
            f"{each.within_p95:.2%} | {each.within_p99:.2%} | {ms(p99)} | {each.queue.peak:.0f} | "
            f"{each.refused:,.0f} | {ms(each.service_ms)} | {each.backlog_peak:,.0f} | "
            f"{each.lag_peak:,.0f} | {met} |"
        )
    lines += stress.downstream_lines([(each.target, each.downstream) for each in steps])
    lines += [
        "",
        "Processor per step, mean / peak in percent of one CPU, sampled about every 12 s; the "
        "simulator's per bot command admitted:",
        "",
        "| Rate | " + " | ".join(CPU_SERVICES) + " | Simulator per command |",
        "| --- | " + " | ".join("---" for _ in CPU_SERVICES) + " | --- |",
    ]
    for each in steps:
        cells = [
            "n/a" if service not in each.cpu else "{:.0f} / {:.0f}".format(*each.cpu[service])
            for service in CPU_SERVICES
        ]
        lines.append(
            f"| {each.target:,.0f}/s | " + " | ".join(cells) + f" | {ms(each.simulator_ms)} |"
        )
    if broke:
        lines += ["", f"Could not be measured, which ended the ramp: {broke}."]
    if skipped:
        lines += [
            "",
            "Not run, after a step missed a budget: "
            + ", ".join(f"{rate:,.0f}/s" for rate in skipped)
            + ".",
        ]
    lines.append("")
    return lines
