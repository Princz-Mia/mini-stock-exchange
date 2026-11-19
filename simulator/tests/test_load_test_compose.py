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
