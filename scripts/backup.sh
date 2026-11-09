#!/usr/bin/env bash
# Backs up Execution's database, the only authority: orders, trades, the ledger, accounts, users, sessions, and the
# outbox of every event it has published. Everything else (Kafka's topics, Market Data's projections, Flink's
# candles) is derived from it, and scripts/restore.sh rebuilds those from a backup.
#
#   scripts/backup.sh [--project NAME] [--env-file FILE] [--to DIRECTORY]
#
# Without --to, the backup goes to backups/<UTC time>/. It holds execution_db.dump (pg_dump's custom format, taken
# from one consistent snapshot, so trading can go on meanwhile) and manifest.txt. Run it against the stack Compose
# started with the same project name and env file.
set -euo pipefail

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$root"
project=()
env_file=.env
target=
while [ $# -gt 0 ]; do
  case "$1" in
    --project) project=(-p "$2"); shift 2 ;;
    --env-file) env_file=$2; shift 2 ;;
    --to) target=$2; shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
compose() { MSYS_NO_PATHCONV=1 docker compose "${project[@]}" --env-file "$env_file" --profile full --profile observability "$@"; }
sql() { compose exec -T postgres psql -U postgres -d execution_db -v ON_ERROR_STOP=1 -Atc "$1"; }

target=${target:-backups/$(date -u +%Y%m%dT%H%M%SZ)}
mkdir -p "$target"

# Written inside the container and copied out: piping binary data through `exec` isn't safe on every shell.
compose exec -T postgres pg_dump -U postgres -d execution_db --format=custom --file=/tmp/execution_db.dump
compose cp postgres:/tmp/execution_db.dump "$target/execution_db.dump" > /dev/null
compose exec -T postgres rm -f /tmp/execution_db.dump

{
  echo "created_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "commit=$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
  echo "schema_version=$(sql "SELECT max(version::int) FROM flyway_schema_history WHERE success")"
  echo "last_command_sequence=$(sql "SELECT coalesce(max(command_sequence), 0) FROM outbox")"
  echo "trades=$(sql "SELECT count(*) FROM trades")"
  echo "last_trade_sequence=$(sql "SELECT coalesce(max(trade_sequence), 0) FROM trades")"
  echo "outbox_events=$(sql "SELECT count(*) FROM outbox")"
} > "$target/manifest.txt"

echo "Backed up execution_db to $target"
cat "$target/manifest.txt"
