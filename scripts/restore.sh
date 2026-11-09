#!/usr/bin/env bash
# Restores Execution's database from a backup made by scripts/backup.sh, then rebuilds everything derived from it.
#
#   scripts/restore.sh BACKUP_DIRECTORY [--project NAME] [--env-file FILE] [--yes]
#
# Execution is the only authority, so after its database goes back to the backup, anything newer elsewhere would
# describe trades that no longer happened. In order:
#   1. stop the services that write or derive: simulator, Flink, Market Data, Execution;
#   2. restore execution_db from the dump, in one transaction;
#   3. mark every outbox event unpublished again, so the relay republishes the whole history in order;
#   4. empty Market Data's projections and checkpoints, and delete Kafka's and Flink's volumes;
#   5. recreate the topics, and start again whatever was running before.
# Market Data and the candles then rebuild from the republished events. This deletes data: it asks first unless
# --yes is given. Revoking a bot's token after the backup was taken is undone by restoring it; revoke it again.
set -euo pipefail

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$root"
backup=
project=()
env_file=.env
yes=
while [ $# -gt 0 ]; do
  case "$1" in
    --project) project=(-p "$2"); shift 2 ;;
    --env-file) env_file=$2; shift 2 ;;
    --yes) yes=1; shift ;;
    -*) echo "unknown option: $1" >&2; exit 2 ;;
    *) backup=$1; shift ;;
  esac
done
[ -n "$backup" ] && [ -f "$backup/execution_db.dump" ] ||
  { echo "usage: scripts/restore.sh BACKUP_DIRECTORY [--project NAME] [--env-file FILE] [--yes]" >&2; exit 2; }
compose() { MSYS_NO_PATHCONV=1 docker compose "${project[@]}" --env-file "$env_file" --profile full --profile observability "$@"; }
psql_in() { compose exec -T postgres psql -U postgres -d "$1" -v ON_ERROR_STOP=1 -q -c "$2"; }

name=$(compose config --format json | sed -nE 's/^ *"name": *"([^"]+)".*/\1/p' | head -n 1)
cat "$backup/manifest.txt"
if [ -z "$yes" ]; then
  printf 'Restore project %s to this backup? Newer trades, and all of Kafka, Market Data and Flink state, are deleted. [y/N] ' "$name"
  read -r answer
  [ "$answer" = y ] || { echo 'Nothing changed.'; exit 1; }
fi

running=$(compose ps --services --status running | tr '\n' ' ')
echo "Running before: $running"
writers="simulator flink-taskmanager flink-jobmanager market-data-service execution-service"
compose stop $writers

echo 'Restoring execution_db'
compose cp "$backup/execution_db.dump" postgres:/tmp/restore.dump > /dev/null
compose exec -T postgres pg_restore -U postgres -d execution_db --clean --if-exists --single-transaction \
  --exit-on-error /tmp/restore.dump
compose exec -T postgres rm -f /tmp/restore.dump
# Publication is one-way for the services; only this maintenance step, as the database superuser, resets it.
psql_in execution_db "BEGIN;
  ALTER TABLE outbox DISABLE TRIGGER outbox_published_once;
  UPDATE outbox SET published_at = NULL;
  ALTER TABLE outbox ENABLE TRIGGER outbox_published_once;
  COMMIT;"

echo 'Emptying Market Data, Kafka and Flink'
psql_in market_data_db "TRUNCATE instrument_projection, market_snapshot, processed_event, projection_checkpoint,
  public_trade, candle_1m"
compose rm -sf kafka flink-jobmanager flink-taskmanager > /dev/null
docker volume rm -f "${name}_kafka-data" "${name}_flink-checkpoints" > /dev/null

echo 'Recreating topics and starting again'
compose up -d --wait kafka
compose run --rm --no-deps kafka-init > /dev/null
# shellcheck disable=SC2086 # the running services, one word each
compose up -d --wait --wait-timeout 300 $running
echo "Restored. Execution republishes $(sed -n 's/^outbox_events=//p' "$backup/manifest.txt") events; Market Data and the candles rebuild from them."
