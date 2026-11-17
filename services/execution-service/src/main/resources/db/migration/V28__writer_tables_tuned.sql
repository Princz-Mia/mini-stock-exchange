-- The writer's reads of recent trades filter by simulation and instrument and go newest first, which a backward scan
-- of the trades' unique index serves; this index duplicated it.
DROP INDEX trades_recent;

-- Rows of these small tables change with nearly every command. Free space left on each page lets PostgreSQL update a
-- row in place on its own page (a HOT update) when no indexed column changes, which saves index writes and bloat.
ALTER TABLE simulation SET (fillfactor = 80);
ALTER TABLE instrument SET (fillfactor = 80);
ALTER TABLE account SET (fillfactor = 80);
ALTER TABLE cash_balance SET (fillfactor = 80);
ALTER TABLE position SET (fillfactor = 80);

-- Market snapshots are written with every command; lz4 compresses them faster than the default pglz. New rows only.
ALTER TABLE outbox ALTER COLUMN payload SET COMPRESSION lz4;
