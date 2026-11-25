#!/bin/sh
# Runs inside the PostgreSQL container for scripts/backup.sh and scripts/backup.ps1: dumps execution_db to
# /tmp/execution_db.dump and prints the manifest's figures, both from one snapshot, so the dump and its manifest
# describe the same moment however much trading goes on meanwhile.
#
# Command and trade sequences start again in every simulation, so a sequence means something only within one. The
# manifest names the current simulation (the one that is not closed, or else the one created last, as the services
# read it) and gives that simulation's figures, each named current_simulation_*. Two figures are of the whole
# database: how many simulations it holds, and how many events its outbox does, since a restore republishes every
# simulation's.
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

# now() is the transaction's start, the moment the snapshot shows. The current simulation's row is read as JSON, so
# that this also runs on a database from before simulations had a name: the name is then empty. Characters of a name
# outside printable ASCII are shown as a question mark, so the manifest is the same plain text whichever script wrote
# it. With no simulation at all, the current simulation's lines have nothing after the equals sign, and its counts
# are 0.
cat >&3 << 'SQL'
SELECT 'created_at=' || to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"');
SELECT 'schema_version=' || max(version::int) FROM flyway_schema_history WHERE success;
WITH chosen AS (
    SELECT s.id, s.state, s.last_command_sequence, to_jsonb(s) ->> 'name' AS name
    FROM simulation s ORDER BY (s.state = 'CLOSED'), s.created_at DESC, s.id DESC LIMIT 1),
traded AS (
    SELECT count(*) AS trades, coalesce(max(t.trade_sequence), 0) AS last_sequence
    FROM trades t WHERE t.simulation_id = (SELECT id FROM chosen))
SELECT line FROM (VALUES
    (1, 'simulations=' || (SELECT count(*) FROM simulation)),
    (2, 'current_simulation=' || coalesce((SELECT id::text FROM chosen), '')),
    (3, 'current_simulation_name='
        || coalesce((SELECT regexp_replace(name, '[^ -~]', '?', 'g') FROM chosen), '')),
    (4, 'current_simulation_state=' || coalesce((SELECT state FROM chosen), '')),
    (5, 'current_simulation_last_command_sequence=' || coalesce((SELECT last_command_sequence FROM chosen), 0)),
    (6, 'current_simulation_trades=' || (SELECT trades FROM traded)),
    (7, 'current_simulation_last_trade_sequence=' || (SELECT last_sequence FROM traded))
) AS lines (line_number, line) ORDER BY line_number;
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
