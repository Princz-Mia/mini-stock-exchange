"""The arithmetic of the load test, kept free of I/O so it can be tested on its own.

Latency comes from Execution's Prometheus histogram: cumulative bucket counts per upper edge.
Two scrapes, one at each end of the measured window, give the window's own histogram by
subtraction, so the warm-up and anything before it are left out exactly.
"""

import math
import re
from collections.abc import Iterable, Mapping
from dataclasses import dataclass

# One sample line of the Prometheus text format: name, optional labels, value. Timestamps are not
# used here.
_SAMPLE = re.compile(r"^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{([^}]*)\})?\s+(\S+)")
_LABEL = re.compile(r'([a-zA-Z_][a-zA-Z0-9_]*)="((?:[^"\\]|\\.)*)"')

Labels = frozenset[tuple[str, str]]


def parse_prometheus(text: str) -> dict[tuple[str, Labels], float]:
    """Every sample in a scrape, keyed by metric name and its set of labels."""
    samples: dict[tuple[str, Labels], float] = {}
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        match = _SAMPLE.match(line)
        if not match:
            continue
        name, labels, value = match.groups()
        pairs = frozenset(_LABEL.findall(labels or ""))
        samples[(name, pairs)] = float(value)
    return samples


def total(samples: Mapping[tuple[str, Labels], float], name: str, **labels: str) -> float:
    """The sum of every sample of a metric whose labels include the given ones."""
    wanted = set(labels.items())
    return sum(
        value for (metric, pairs), value in samples.items() if metric == name and wanted <= pairs
    )


def bucket_counts(
    samples: Mapping[tuple[str, Labels], float], name: str, **labels: str
) -> dict[float, float]:
    """Cumulative count per upper edge, summed over every series that matches; +Inf as math.inf."""
    wanted = set(labels.items())
    counts: dict[float, float] = {}
    for (metric, pairs), value in samples.items():
        if metric != name or not wanted <= pairs:
            continue
        edge = dict(pairs).get("le")
        if edge is None:
            continue
        upper = math.inf if edge == "+Inf" else float(edge)
        counts[upper] = counts.get(upper, 0.0) + value
    return counts


def window(
    before: Mapping[float, float], after: Mapping[float, float]
) -> list[tuple[float, float]]:
    """The window's own cumulative histogram, sorted by edge. A series absent before counts from
    zero."""
    edges = sorted(after)
    result = [(edge, after[edge] - before.get(edge, 0.0)) for edge in edges]
    if any(count < 0 for _, count in result):
        raise ValueError("a counter went backwards: was the service restarted in the window?")
    return result


def count(histogram: list[tuple[float, float]]) -> float:
    """How many observations the cumulative histogram holds: its +Inf bucket."""
    return histogram[-1][1] if histogram else 0.0


def share_within(histogram: list[tuple[float, float]], edge: float) -> float:
    """The exact share of observations at or below an edge the histogram has; 1 for an empty one."""
    observed = count(histogram)
    if observed == 0:
        return 1.0
    for upper, cumulative in histogram:
        if math.isclose(upper, edge, rel_tol=1e-9):
            return cumulative / observed
    raise ValueError(f"no bucket edge at {edge}")


def percentile(histogram: list[tuple[float, float]], fraction: float) -> float | None:
    """An estimate of a percentile, interpolated linearly inside the bucket that holds it.

    Exact only at bucket edges; between them it assumes observations spread evenly. None when
    empty. A percentile in the +Inf bucket is reported as that bucket's lower edge, the most that
    can be claimed.
    """
    observed = count(histogram)
    if observed == 0:
        return None
    rank = fraction * observed
    lower_edge, lower_count = 0.0, 0.0
    for upper, cumulative in histogram:
        if cumulative >= rank:
            if math.isinf(upper):
                return lower_edge
            inside = cumulative - lower_count
            if inside == 0:
                return upper
            return lower_edge + (upper - lower_edge) * (rank - lower_count) / inside
        lower_edge, lower_count = upper, cumulative
    return lower_edge


def nearest_rank(values: Iterable[float], fraction: float) -> float | None:
    """A percentile of raw measurements by the nearest-rank method; None for none."""
    ordered = sorted(values)
    if not ordered:
        return None
    rank = max(1, math.ceil(fraction * len(ordered)))
    return ordered[rank - 1]


@dataclass(frozen=True)
class QueueVerdict:
    first_minute: float
    last_minute: float
    peak: float
    grew: bool


def queue_growth(samples: list[tuple[float, float]], allowance: float = 5.0) -> QueueVerdict:
    """Whether the writer queue grew over the window, from (seconds into the window, depth) samples.

    Sustained growth means the last minute's average depth is above the first minute's by more
    than a few commands. A momentary peak is not growth; it is reported beside the verdict.
    """
    if not samples:
        raise ValueError("no queue samples")
    end = max(at for at, _ in samples)
    first = [depth for at, depth in samples if at <= 60] or [samples[0][1]]
    last = [depth for at, depth in samples if at >= end - 60] or [samples[-1][1]]
    first_mean = sum(first) / len(first)
    last_mean = sum(last) / len(last)
    return QueueVerdict(
        first_mean,
        last_mean,
        max(depth for _, depth in samples),
        last_mean > first_mean + allowance,
    )
