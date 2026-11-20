-- Every outbox row is updated once, when it is published, which leaves one dead row behind, and rows are never
-- deleted. By default autovacuum waits until a fifth of the table is dead, so as the table grows the gaps between
-- vacuums grow with it, and the dead entries in outbox_pending slow the relay's and admission's reads of the backlog.
-- Vacuum after a fixed number of dead rows instead of a share of the table. (On a very large table PostgreSQL may still
-- skip cleaning the indexes when the dead rows touch few pages, so the gaps then stretch a little; far less than now.)
ALTER TABLE outbox SET (autovacuum_vacuum_scale_factor = 0, autovacuum_vacuum_threshold = 20000);
