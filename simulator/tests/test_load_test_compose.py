from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def postgres_settings(path: str) -> dict[str, str]:
    """The -c settings under the postgres service's command in a Compose file."""
    lines = (ROOT / path).read_text(encoding="utf-8").splitlines()
    start = lines.index("  postgres:")
    command = next(i for i in range(start, len(lines)) if lines[i].strip() == "command:")
    values = []
    for line in lines[command + 1 :]:
        if not line.startswith("      - "):
            break
        values.append(line.removeprefix("      - ").strip())
    assert values[0] == "postgres"
    settings = {}
    for flag, setting in zip(values[1::2], values[2::2], strict=True):
        assert flag == "-c", values
        name, _, value = setting.partition("=")
        settings[name] = value
    return settings


def test_the_load_test_runs_postgres_with_everyones_durable_settings() -> None:
    # An override's command replaces the base list, so the overlay must repeat every setting, or
    # the load test would measure a database configured unlike everyone else's.
    base = postgres_settings("compose.yaml")
    overlay = postgres_settings("infra/load-test/compose.diagnostics.yaml")

    assert base, "compose.yaml sets PostgreSQL's settings"
    assert {name: overlay.get(name) for name in base} == base
    assert overlay["shared_preload_libraries"] == "pg_stat_statements"


def test_no_setting_turns_durability_off() -> None:
    for path in ("compose.yaml", "infra/load-test/compose.diagnostics.yaml"):
        settings = postgres_settings(path)
        for name in ("fsync", "synchronous_commit", "full_page_writes"):
            assert name not in settings, f"{path} must leave {name} at its default (on)"


def test_everyones_stack_runs_trading_commands_in_runs_of_up_to_32() -> None:
    # The service's own default is one command per transaction; Compose, and so the load test,
    # sets the limit for everyone, and the load test's overlay must not change it.
    lines = (ROOT / "compose.yaml").read_text(encoding="utf-8").splitlines()
    start = lines.index("  execution-service:")
    end = next(
        i for i in range(start + 1, len(lines)) if lines[i].startswith("  ") and lines[i][2] != " "
    )
    assert "      MSE_TRADING_MAX_BATCH: 32" in lines[start:end]
    overlay = (ROOT / "infra/load-test/compose.diagnostics.yaml").read_text(encoding="utf-8")
    assert "MSE_TRADING_MAX_BATCH" not in overlay
