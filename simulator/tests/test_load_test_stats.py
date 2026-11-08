import math
import random

import pytest

from mse_simulator.loadtest.stats import (
    bucket_counts,
    count,
    nearest_rank,
    parse_prometheus,
    percentile,
    queue_growth,
    share_within,
    total,
    window,
)

SCRAPE = """# HELP mse_commands_seconds Trading commands from admission to committed result
# TYPE mse_commands_seconds histogram
mse_commands_seconds_bucket{command="place",outcome="committed",le="0.05"} 10.0
mse_commands_seconds_bucket{command="place",outcome="committed",le="0.15"} 30.0
mse_commands_seconds_bucket{command="place",outcome="committed",le="0.5"} 39.0
mse_commands_seconds_bucket{command="place",outcome="committed",le="+Inf"} 40.0
mse_commands_seconds_bucket{command="cancel",outcome="committed",le="0.05"} 5.0
mse_commands_seconds_bucket{command="cancel",outcome="committed",le="0.15"} 10.0
mse_commands_seconds_bucket{command="cancel",outcome="committed",le="0.5"} 10.0
mse_commands_seconds_bucket{command="cancel",outcome="committed",le="+Inf"} 10.0
mse_commands_seconds_count{command="place",outcome="committed"} 40.0
mse_commands_refused_total{command="place",reason="busy"} 2.0
mse_commands_refused_total{command="place",reason="backlog"} 3.0
mse_writer_queue_depth 4.0
"""


def test_a_scrape_is_read_by_name_and_labels() -> None:
    samples = parse_prometheus(SCRAPE)

    assert total(samples, "mse_writer_queue_depth") == 4
    assert total(samples, "mse_commands_refused_total") == 5
    assert total(samples, "mse_commands_refused_total", reason="busy") == 2
    assert total(samples, "mse_commands_seconds_count", command="cancel") == 0
    assert total(samples, "no_such_metric") == 0


def test_buckets_of_matching_series_are_summed_per_edge() -> None:
    samples = parse_prometheus(SCRAPE)

    assert bucket_counts(samples, "mse_commands_seconds_bucket", outcome="committed") == {
        0.05: 15.0,
        0.15: 40.0,
        0.5: 49.0,
        math.inf: 50.0,
    }
    assert bucket_counts(samples, "mse_commands_seconds_bucket", command="cancel")[math.inf] == 10


def test_the_window_is_the_difference_of_two_scrapes() -> None:
    before = {0.15: 10.0, math.inf: 12.0}
    after = {0.05: 3.0, 0.15: 30.0, math.inf: 40.0}

    assert window(before, after) == [(0.05, 3.0), (0.15, 20.0), (math.inf, 28.0)]
    with pytest.raises(ValueError, match="backwards"):
        window(after, before | {0.05: 3.0})


def test_shares_are_exact_at_edges_the_histogram_has() -> None:
    histogram = [(0.05, 15.0), (0.15, 40.0), (0.5, 49.0), (math.inf, 50.0)]

    assert count(histogram) == 50
    assert share_within(histogram, 0.15) == 0.8
    assert share_within(histogram, 0.5) == 0.98
    assert share_within([], 0.15) == 1.0
    with pytest.raises(ValueError, match="no bucket edge"):
        share_within(histogram, 0.2)


def test_percentiles_interpolate_inside_their_bucket() -> None:
    histogram = [(0.1, 50.0), (0.2, 100.0), (math.inf, 100.0)]

    assert percentile(histogram, 0.5) == pytest.approx(0.1)
    assert percentile(histogram, 0.25) == pytest.approx(0.05)
    assert percentile(histogram, 0.75) == pytest.approx(0.15)
    assert percentile(histogram, 1.0) == pytest.approx(0.2)
    assert percentile([], 0.95) is None
    assert percentile([(0.1, 1.0), (math.inf, 10.0)], 0.99) == 0.1, (
        "never beyond the last finite edge"
    )


def test_seeded_histograms_bracket_the_true_percentile() -> None:
    """Bucketing real samples: the estimate always lies in the same bucket as the true value."""
    rng = random.Random(20261108)
    edges = [0.001 * 1.4**i for i in range(30)] + [math.inf]
    for _ in range(300):
        values = [
            rng.lognormvariate(math.log(0.03), rng.uniform(0.2, 1.5))
            for _ in range(rng.randint(1, 400))
        ]
        histogram = [(edge, float(sum(1 for v in values if v <= edge))) for edge in edges]
        for fraction in (0.5, 0.95, 0.99):
            truth = nearest_rank(values, fraction)
            estimate = percentile(histogram, fraction)
            assert truth is not None and estimate is not None
            upper = next(edge for edge in edges if truth <= edge)
            lower = max([0.0] + [edge for edge in edges if edge < upper])
            if math.isinf(upper):
                assert estimate == lower
            else:
                assert lower - 1e-12 <= estimate <= upper + 1e-12, (fraction, truth, estimate)


def test_nearest_rank_on_raw_measurements() -> None:
    values = [5.0, 1.0, 4.0, 2.0, 3.0]

    assert nearest_rank(values, 0.5) == 3.0
    assert nearest_rank(values, 0.95) == 5.0
    assert nearest_rank(values, 0.0) == 1.0
    assert nearest_rank([], 0.5) is None


def test_a_steady_queue_is_not_growth_but_a_rising_one_is() -> None:
    steady = [(t, 2.0 + (t % 3)) for t in range(0, 600, 5)] + [(300, 40.0)]
    rising = [(t, t / 20) for t in range(0, 600, 5)]

    verdict = queue_growth(steady)
    assert not verdict.grew
    assert verdict.peak == 40
    assert queue_growth(rising).grew
    assert queue_growth(rising).last_minute > queue_growth(rising).first_minute
    with pytest.raises(ValueError):
        queue_growth([])
