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


def test_a_run_of_several_commands_counts_its_time_and_its_commands() -> None:
    # One transaction is timed once, tagged "run" when it held several commands; the run counter
    # says how many commands the runs held, runs of one included.
    def scrape(
        place: tuple[int, float], run: dict[str, tuple[int, float]], runs: int, held: int
    ) -> dict:
        lines = [
            f'mse_writer_queue_wait_seconds_count{{command="place"}} {held}',
            f'mse_writer_queue_wait_seconds_sum{{command="place"}} {held * 0.001}',
            f"mse_writer_run_size_commands_count {runs}",
            f"mse_writer_run_size_commands_sum {held}",
        ]
        for phase in ("begin", "work", "commit", "apply"):
            lines.append(f'mse_writer_{phase}_seconds_count{{command="place"}} {place[0]}')
            lines.append(f'mse_writer_{phase}_seconds_sum{{command="place"}} {place[1] / 4}')
        for phase, (count, seconds) in run.items():
            lines.append(f'mse_writer_{phase}_seconds_count{{command="run"}} {count}')
            lines.append(f'mse_writer_{phase}_seconds_sum{{command="run"}} {seconds}')
        return stats.parse_prometheus("\n".join(lines))

    before = scrape((0, 0.0), {p: (0, 0.0) for p in ("begin", "work", "commit", "apply")}, 0, 0)
    # 100 orders alone (0.5 s in all), and 50 runs holding 400 orders (0.5 s in all).
    after = scrape(
        (100, 0.5),
        {"begin": (50, 0.005), "work": (50, 0.2), "commit": (50, 0.25), "apply": (50, 0.045)},
        150,
        500,
    )

    split = diagnostics.writer_split(before, after)

    assert split.commands == 500
    assert split.service_ms == pytest.approx(2.0)
    assert split.ceiling == pytest.approx(500)
    assert split.mean_run == pytest.approx(500 / 150)
    assert split.means["run"]["commit"] == pytest.approx(5.0)
    assert split.means["run"]["queue_wait"] is None
    assert split.means["place"]["queue_wait"] == pytest.approx(1.0)


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
    for trades, snapshots in ((0, 5), (12, 3), (4, -1)):
        sampled.scrape(
            stats.parse_prometheus(
                f'mse_feed_lag{{topic="mse.trades.v1"}} {trades}\n'
                f'mse_feed_lag{{topic="mse.market.v1"}} {snapshots}\n'
            ),
            stats.parse_prometheus(
                'hikaricp_connections_pending{pool="execution"} 2\n'
                'hikaricp_connections_pending{pool="writer"} 1\n'
            ),
        )

    assert diagnostics.lag_by_topic(sampled) == {
        "mse.market.v1": (5, 3),
        "mse.trades.v1": (12, 4),
    }, "a lag of -1 means not measured yet and is left out"
    assert max(sampled.pending) == 2, "the application pool alone"
    assert max(sampled.writer_pending) == 1, "the writer's pool apart"


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
    assert "| run | n/a | n/a | n/a | n/a | n/a |" in text
    assert "at a mean run of n/a" in text
