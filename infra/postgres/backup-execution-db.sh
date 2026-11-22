#!/bin/sh
# Runs inside the PostgreSQL container for scripts/backup.sh and scripts/backup.ps1: dumps execution_db to
# /tmp/execution_db.dump and prints the manifest's figures, both from one snapshot, so the dump and its manifest
# describe the same moment however much trading goes on meanwhile.
set -eu

work=$(mktemp -d)
session=
cleanup() {
  exec 3>&- 2> /dev/null || true
  if [ -n "$session" ]; then kill "$session" 2> /dev/null || true; fi
  rm -rf "$work"
}
trap cleanup EXIT

# One session holds the snapshot open: it exports it for pg_dump, then reads the figures in the same transaction.
mkfifo "$work/in"
psql -U postgres -d execution_db -v ON_ERROR_STOP=1 -Atq < "$work/in" > "$work/out" 2> "$work/err" &
session=$!
exec 3> "$work/in"
echo 'BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY; SELECT pg_export_snapshot();' >&3
until [ -s "$work/out" ]; do
  if ! kill -0 "$session" 2> /dev/null; then
    cat "$work/err" >&2
    exit 1
  fi
  sleep 0.1
done
snapshot=$(head -n 1 "$work/out")

pg_dump -U postgres -d execution_db --format=custom --snapshot="$snapshot" --file=/tmp/execution_db.dump

# now() is the transaction's start, the moment the snapshot shows.
cat >&3 << 'SQL'
SELECT 'created_at=' || to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"');
SELECT 'schema_version=' || max(version::int) FROM flyway_schema_history WHERE success;
SELECT 'last_command_sequence=' || coalesce(max(command_sequence), 0) FROM outbox;
SELECT 'trades=' || count(*) FROM trades;
SELECT 'last_trade_sequence=' || coalesce(max(trade_sequence), 0) FROM trades;
SELECT 'outbox_events=' || count(*) FROM outbox;
COMMIT;
SQL
exec 3>&-
if ! wait "$session"; then
  session=
  cat "$work/err" >&2
  exit 1
fi
session=
tail -n +2 "$work/out"
