import pytest

from mse_simulator.loadtest import diagnostics, stats


def capture(statements: str, fsyncs: str, wal: str, checkpoints: str, tables: str):
    return diagnostics.database(statements, fsyncs, wal, checkpoints, tables)


# Each statement's key is its query and level; "f" marks one run inside a trigger.
BEFORE = capture(
    "11:t|100|50.0|SELECT a|t\n22:t|10|20.0|INSERT b|t\n44:f|1000|10.0|SELECT 1 FOR KEY SHARE|f\n",
    "1000|2000.0|1500|300.0\n",
    "500|5|100000\n",
    "3|0|10.0|5.0|40\n",
    "orders|100|20|5\naccount|50|50|0\n",
)

AFTER = capture(
    "11:t|300|150.0|SELECT a|t\n22:t|30|80.0|INSERT b|t\n33:t|20|10.0|UPDATE c|t\n"
    "44:f|2500|40.0|SELECT 1 FOR KEY SHARE|f\n",
    "1150|2300.0|1700|340.0\n",
    "900|7|300000\n",
    "4|1|12.0|6.0|60\n",
    "orders|200|70|9\naccount|50|50|1\n",
)


def test_the_database_window_is_per_committed_command() -> None:
    window = diagnostics.database_window(BEFORE, AFTER, committed=100)

    # 200 + 20 + 20 statements over 100 commands; 100 + 60 + 10 ms of database time. The 1,500
    # nested checks are counted apart, and their 30 ms is not added: it is inside their parents'.
    assert window.statements_per_command == pytest.approx(2.4)
    assert window.server_ms_per_command == pytest.approx(1.7)
    assert window.nested_per_command == pytest.approx(15.0)
    assert [item.text for item in window.top] == ["SELECT a", "INSERT b", "UPDATE c"]
    assert window.top[0].mean_ms == pytest.approx(0.5)
    assert window.top[0].calls_per_command == pytest.approx(2.0)
    assert window.top[1].share == pytest.approx(60 / 170)
    assert window.top[2].calls_per_command == pytest.approx(0.2), (
        "a statement first seen counts from zero"
    )
    assert window.fsyncs_per_command == pytest.approx(1.5)
    assert window.fsync_ms == pytest.approx(2.0)
    assert window.wal_bytes_per_command == pytest.approx(2000)
    assert window.full_page_images_per_command == pytest.approx(0.02)
    assert (window.checkpoints_timed, window.checkpoints_requested) == (1, 1)
    assert window.tables["orders"] == (pytest.approx(0.5), 9)
    assert window.tables["account"] == (None, 1), "no updates in the window has no HOT share"


def test_the_top_statements_are_capped() -> None:
    window = diagnostics.database_window(BEFORE, AFTER, committed=100, top=1)

    assert [item.text for item in window.top] == ["SELECT a"]


def test_no_flush_in_the_window_has_no_flush_time() -> None:
    assert diagnostics.database_window(BEFORE, BEFORE, committed=10).fsync_ms is None


def writer(place: dict[str, tuple[float, float]], cancel: dict[str, tuple[float, float]]) -> dict:
    """A scrape with each phase's count and total seconds, by command."""
    lines = []
    for command, phases in (("place", place), ("cancel", cancel)):
        for phase, (count, seconds) in phases.items():
            name = f"mse_writer_{phase}_seconds"
            lines.append(f'{name}_count{{command="{command}"}} {count}')
            lines.append(f'{name}_sum{{command="{command}"}} {seconds}')
    return stats.parse_prometheus("\n".join(lines))


ZERO = {phase: (0, 0.0) for phase in diagnostics.PHASES}


def test_the_writer_split_is_the_window_and_the_ceiling_follows() -> None:
    before = writer(
        {
            "queue_wait": (10, 1.0),
            "begin": (10, 1.0),
            "work": (10, 1.0),
            "commit": (10, 1.0),
            "apply": (10, 1.0),
        },
        ZERO,
    )
    after = writer(
        {
            "queue_wait": (110, 1.1),
            "begin": (110, 1.01),
            "work": (110, 1.3),
            "commit": (110, 1.2),
            "apply": (110, 1.0 + 100 * 0.0001),
        },
        {
            "queue_wait": (100, 0.0),
            "begin": (100, 0.01),
            "work": (100, 0.2),
            "commit": (100, 0.19),
            "apply": (100, 0.0),
        },
    )

    split = diagnostics.writer_split(before, after)

    assert split.means["place"]["work"] == pytest.approx(3.0)
    assert split.means["place"]["commit"] == pytest.approx(2.0)
    assert split.means["place"]["queue_wait"] == pytest.approx(1.0)
    assert split.means["cancel"]["commit"] == pytest.approx(1.9)
    # place: 0.1 + 3 + 2 + 0.1 = 5.2 ms; cancel: 0.1 + 2 + 1.9 = 4 ms; 100 of each.
    assert split.service_ms == pytest.approx(4.6)
    assert split.ceiling == pytest.approx(1000 / 4.6)


def test_no_commands_in_the_window_has_no_ceiling() -> None:
    same = writer(ZERO, ZERO)

    split = diagnostics.writer_split(same, same)

    assert split.service_ms is None and split.ceiling is None


def requests(*rows: tuple[str, str, str, int]) -> dict:
    """A scrape of Spring's request counter: method, URI, status and count."""
    name = "http_server_requests_seconds_count"
    return stats.parse_prometheus(
        "\n".join(
            f'{name}{{method="{method}",uri="{uri}",status="{status}"}} {n}'
            for method, uri, status, n in rows
        )
    )


def test_reads_are_get_requests_a_second_by_uri() -> None:
    before = requests(
        ("GET", "/api/v1/portfolio", "200", 100), ("POST", "/api/v1/orders", "201", 50)
    )
    after = requests(
        ("GET", "/api/v1/portfolio", "200", 700),
        ("GET", "/api/v1/portfolio", "429", 10),
        ("GET", "/api/v1/orders", "200", 300),
        ("POST", "/api/v1/orders", "201", 650),
    )

    assert diagnostics.reads_per_second(before, after, seconds=10) == {
        "/api/v1/orders": pytest.approx(30),
        "/api/v1/portfolio": pytest.approx(61),
    }


def test_the_feed_lag_and_the_pool_are_sampled_by_topic() -> None:
    sampled = diagnostics.Sampled()
    for at, (trades, snapshots, backlog) in enumerate(((0, 5, "7"), (12, 3, None), (4, -1, "9"))):
        sampled.scrape(
            stats.parse_prometheus(
                f'mse_feed_lag{{topic="mse.trades.v1"}} {trades}\n'
                f'mse_feed_lag{{topic="mse.market.v1"}} {snapshots}\n'
            ),
            stats.parse_prometheus(
                'hikaricp_connections_pending{pool="execution"} 2\n'
                'hikaricp_connections_pending{pool="writer"} 1\n'
                'hikaricp_connections_pending{pool="sessions"} 3\n'
                + ("" if backlog is None else f"mse_outbox_pending {backlog}\n")
            ),
            at * 5.0,
        )

    assert diagnostics.lag_by_topic(sampled) == {
        "mse.market.v1": (5, 3),
        "mse.trades.v1": (12, 4),
    }, "a lag of -1 means not measured yet and is left out"
    assert sampled.feed_lag["mse.trades.v1"] == [(0.0, 0), (5.0, 12), (10.0, 4)]
    assert [at for at, _ in sampled.execution] == [0.0, 5.0, 10.0], "Execution's scrapes kept"
    assert sampled.backlog == [(0.0, 7), (10.0, 9)], "a backlog Execution did not report is no zero"
    assert max(sampled.pending) == 2, "the application pool alone"
    assert max(sampled.writer_pending) == 1, "the writer's pool apart"
    assert max(sampled.session_pending) == 3, "the session store's pool apart"


def series(value, later=None, seconds: int = 60) -> list[tuple[float, float]]:
    """Every 5 s, at value, then at later from two thirds of the window on."""
    return [
        (float(at), value if later is None or at < 2 * seconds / 3 else later)
        for at in range(0, seconds + 1, 5)
    ]


def test_downstream_names_what_grew() -> None:
    sampled = diagnostics.Sampled()
    sampled.backlog = series(50.0, 900.0)
    sampled.feed_lag = {"mse.trades.v1": series(2.0)}

    flow = diagnostics.downstream(sampled, 300.0, 20.0)

    assert flow.allowance == 600.0
    assert list(flow.verdicts) == [diagnostics.BACKLOG, "mse.trades.v1"]
    assert flow.measured and flow.grew() == [diagnostics.BACKLOG] and not flow.kept_up
    assert flow.describe() == "grew: publication backlog 50 to 900"
    assert flow.peak(diagnostics.BACKLOG) == 900.0 and flow.lag_peak == 2.0
    sampled.backlog = series(50.0)
    quiet = diagnostics.downstream(sampled, 300.0, 20.0)
    assert quiet.kept_up and quiet.describe() == "backlog peak 50, feed lag peak 2"


def test_downstream_needs_the_backlog_and_a_feed_each_sampled_at_both_ends() -> None:
    sampled = diagnostics.Sampled()
    sampled.backlog = series(50.0)
    assert not diagnostics.downstream(sampled, 300.0, 20.0).measured, "no feed"
    sampled.feed_lag = {"mse.trades.v1": series(2.0)}
    sampled.backlog = []
    assert not diagnostics.downstream(sampled, 300.0, 20.0).measured, "no backlog"
    sampled.backlog = series(50.0)
    # One sample in the last span: too thin to judge, whatever its value.
    sampled.feed_lag["mse.market.v1"] = [(at, value) for at, value in series(2.0) if at < 40]
    sampled.feed_lag["mse.market.v1"].append((60.0, 2.0))
    thin = diagnostics.downstream(sampled, 300.0, 20.0)
    assert thin.unsampled == ("mse.market.v1",) and not thin.measured and not thin.kept_up
    assert thin.describe() == "not sampled enough: mse.market.v1"
    sampled.feed_lag["mse.market.v1"] = []
    assert diagnostics.downstream(sampled, 300.0, 20.0).unsampled == ("mse.market.v1",), (
        "a topic seen with no lag measured"
    )
    sampled.feed_lag["mse.market.v1"] = series(2.0)
    assert diagnostics.downstream(sampled, 300.0, 20.0).kept_up


def test_a_feed_seen_not_live_has_not_kept_up_even_with_a_flat_lag() -> None:
    sampled = diagnostics.Sampled()
    sampled.backlog = series(50.0)
    sampled.feed_lag = {"mse.trades.v1": series(7.0)}
    sampled.feed_live = {"mse.trades.v1": series(1.0, 0.0), "mse.market.v1": series(1.0)}

    flow = diagnostics.downstream(sampled, 300.0, 20.0)

    assert flow.measured and flow.grew() == []
    assert flow.not_live == ("mse.trades.v1",)
    assert not flow.kept_up and flow.fell_behind()
    assert flow.describe() == "not live: mse.trades.v1"


def test_the_feed_state_is_sampled_by_topic() -> None:
    sampled = diagnostics.Sampled()
    sampled.scrape(
        stats.parse_prometheus(
            'mse_feed_live{topic="mse.trades.v1"} 1.0\nmse_feed_live{topic="mse.market.v1"} 0.0\n'
        ),
        {},
        5.0,
    )

    assert sampled.feed_live == {"mse.market.v1": [(5.0, 0.0)], "mse.trades.v1": [(5.0, 1.0)]}


def test_cgroup_cpu_counters_are_read_and_turned_into_throttling() -> None:
    text = (
        "usage_usec 912345\nuser_usec 800000\nsystem_usec 112345\nnr_periods 1000\n"
        "nr_throttled 50\nthrottled_usec 2500000\nnr_bursts 0\nburst_usec 0\n"
    )
    assert diagnostics.cpu_stat(text) == (1000, 50, 2_500_000)
    assert diagnostics.cpu_stat("usage_usec 1\nuser_usec 1\n") is None, "no CPU limit"

    shares = diagnostics.throttling(
        {"postgres": (1000, 50, 2_500_000), "web": (10, 0, 0), "kafka": (5, 0, 0)},
        {"postgres": (1600, 230, 11_500_000), "web": (10, 0, 0)},
        60.0,
    )

    # 180 of 600 periods held back, for 9 s in 60 s: 150 ms a second.
    assert shares["postgres"] == (pytest.approx(0.30), pytest.approx(150.0))
    assert "web" not in shares, "no periods passed"
    assert "kafka" not in shares, "read at one end only"


def test_timed_processor_readings_are_kept_with_their_time() -> None:
    sampled = diagnostics.Sampled()
    sampled.docker("mse-load-postgres-1|150.00%\n", "mse-load", 12.5)
    sampled.docker("mse-load-postgres-1|50.00%\n", "mse-load")

    assert sampled.cpu["postgres"] == [150.0, 50.0]
    assert sampled.cpu_at == {"postgres": [(12.5, 150.0)]}, "an untimed reading is not timed"


def test_processor_samples_are_read_by_service() -> None:
    sampled = diagnostics.Sampled()
    sampled.docker(
        "mse-load-postgres-1|150.00%\nmse-load-market-data-service-1|20.5%\nother-web-1|90%\n",
        "mse-load",
    )
    sampled.docker("mse-load-postgres-1|50.00%\nmse-load-market-data-service-1|--\n", "mse-load")

    assert diagnostics.cpu_by_service(sampled) == {
        "market-data-service": (20.5, 20.5),
        "postgres": (100.0, 150.0),
    }


def test_the_outbox_is_read_by_topic() -> None:
    assert diagnostics.outbox("mse.market.v1|1200|4100\nmse.trades.v1|300||\n") == {
        "mse.market.v1": (1200, 4100),
        "mse.trades.v1": (300, 0),
    }


def test_the_report_names_what_it_measured() -> None:
    split = diagnostics.writer_split(writer(ZERO, ZERO), writer(ZERO, ZERO))
    window = diagnostics.database_window(BEFORE, AFTER, committed=100)
    sampled = diagnostics.Sampled()
    sampled.docker("mse-load-postgres-1|80%\n", "mse-load")

    text = "\n".join(
        diagnostics.report(
            split, window, {"/api/v1/portfolio": 61.0}, sampled, {"mse.market.v1": (10, 400)}, 21.4
        )
    )

    assert "## Where the time goes" in text
    assert "each flush took 2.00 ms" in text
    assert "Restarting Execution, its reconciliation included: 21 s." in text
    assert "/api/v1/portfolio: 61.0" in text
    assert "postgres: 80%, 80%" in text
    assert "`SELECT a`" in text
    assert "one writer at that time sustains about n/a" in text
