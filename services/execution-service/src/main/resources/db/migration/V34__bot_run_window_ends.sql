-- When a run's window ended, beside its last command: when its worker confirmed the stop, or when the next run started.
-- A run's length is then exact either way. stopped_at can't stand in: a worker that comes back to a run the next start
-- ended may still confirm its stop afterwards, long after the window closed.
ALTER TABLE bot_run ADD COLUMN ended_at TIMESTAMPTZ;

-- Runs already ended. A worker's confirmation ended its window in the same statement that recorded the stop; a run the
-- next start ended stopped when that start began, its window ending one command before the next one's.
UPDATE bot_run SET ended_at = stopped_at WHERE ended_by = 'WORKER';
UPDATE bot_run b SET ended_at = n.started_at
FROM bot_run n
WHERE b.ended_by = 'NEXT_START' AND n.simulation_id = b.simulation_id AND n.start_sequence = b.end_sequence + 1;

ALTER TABLE bot_run ADD CHECK (ended_at IS NULL OR end_sequence IS NOT NULL);
ALTER TABLE bot_run ADD CHECK (ended_at IS NULL OR ended_at >= started_at);

GRANT UPDATE (ended_at) ON bot_run TO execution_app;
