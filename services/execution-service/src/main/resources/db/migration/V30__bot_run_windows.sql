-- A run's window in the command sequence, so what its bots did can be told from what came before and after.
--   stop_sequence: the operator's stop command, when there was one.
--   end_sequence:  the run's last command, set once. ended_by says how:
--                  WORKER      its worker confirmed the stop; every command of the run, its closing cancels included,
--                              had committed by then.
--                  NEXT_START  the next run started first, one before it. A start waits while a worker still holds a
--                              live lease on a stopping run, so this happens only to a run that never had a worker, or
--                              whose worker went quiet; anything that worker sends later falls after its end.
--                  NOT_KEPT    recorded before windows were kept: no end, ever, since it can't be recovered exactly.
-- Two windows never overlap.
ALTER TABLE bot_run ADD COLUMN stop_sequence BIGINT CHECK (stop_sequence > start_sequence);
ALTER TABLE bot_run ADD COLUMN end_sequence BIGINT CHECK (end_sequence >= start_sequence);
ALTER TABLE bot_run ADD COLUMN ended_by TEXT CHECK (ended_by IN ('WORKER', 'NEXT_START', 'NOT_KEPT'));
UPDATE bot_run SET ended_by = 'NOT_KEPT';
ALTER TABLE bot_run ADD CHECK (CASE WHEN ended_by IS NULL OR ended_by = 'NOT_KEPT' THEN end_sequence IS NULL
                                    ELSE end_sequence IS NOT NULL END);
ALTER TABLE bot_run ADD CHECK (stop_sequence IS NULL OR end_sequence IS NULL OR end_sequence >= stop_sequence);

-- Each instrument's newest trade sequence at the run's edges: its trades in the window are those after the first and
-- up to the second, found through the trades' unique (simulation, instrument, trade sequence) index.
ALTER TABLE bot_run_instrument ADD COLUMN start_trade_sequence BIGINT CHECK (start_trade_sequence >= 0);
ALTER TABLE bot_run_instrument ADD COLUMN end_trade_sequence BIGINT
    CHECK (end_trade_sequence >= start_trade_sequence);

GRANT UPDATE (stop_sequence, end_sequence, ended_by) ON bot_run TO execution_app;
GRANT UPDATE (end_trade_sequence) ON bot_run_instrument TO execution_app;
