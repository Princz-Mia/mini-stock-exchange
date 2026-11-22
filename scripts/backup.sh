#!/usr/bin/env bash
# Backs up Execution's database, the only authority: orders, trades, the ledger, accounts, users, sessions, and the
# outbox of every event it has published. Everything else (Kafka's topics, Market Data's projections, Flink's
# candles) is derived from it, and scripts/restore.sh rebuilds those from a backup.
#
#   scripts/backup.sh [--project NAME] [--env-file FILE] [--to DIRECTORY]
#
# Without --to, the backup goes to backups/<UTC time>/. It holds execution_db.dump (pg_dump's custom format, taken
# from one consistent snapshot, so trading can go on meanwhile) and manifest.txt, whose figures come from that same
# snapshot. Run it against the stack Compose started with the same project name and env file.
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

target=${target:-backups/$(date -u +%Y%m%dT%H%M%SZ)}
mkdir -p "$target"

# The dump and the manifest's figures come from one snapshot, inside the container (infra/postgres/backup-execution-db.sh);
# the dump is written there and copied out, as piping binary data through `exec` isn't safe on every shell.
compose cp infra/postgres/backup-execution-db.sh postgres:/tmp/backup-execution-db.sh > /dev/null
figures=$(compose exec -T postgres sh /tmp/backup-execution-db.sh)
compose cp postgres:/tmp/execution_db.dump "$target/execution_db.dump" > /dev/null
compose exec -T postgres rm -f /tmp/execution_db.dump /tmp/backup-execution-db.sh

# The snapshot's time first, then the commit of this checkout, then the snapshot's counts.
{
  printf '%s\n' "$figures" | head -n 1
  echo "commit=$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
  printf '%s\n' "$figures" | tail -n +2
} > "$target/manifest.txt"

echo "Backed up execution_db to $target"
cat "$target/manifest.txt"
