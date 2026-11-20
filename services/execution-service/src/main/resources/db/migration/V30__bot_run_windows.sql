-- A run's window in the command sequence, so what its bots did can be told from what came before and after.
--   stop_sequence: the operator's stop command, when there was one.
--   end_sequence:  the run's last command. Set once, by whichever comes first: the worker confirming its stop (every
--                  command of the run, its closing cancels included, has committed by then) or the next run's start
--                  (one before it), so two runs' windows never overlap. Null until then: the run is not finished.
-- Runs from before this migration keep both null; their end can't be recovered exactly.
ALTER TABLE bot_run ADD COLUMN stop_sequence BIGINT CHECK (stop_sequence > start_sequence);
ALTER TABLE bot_run ADD COLUMN end_sequence BIGINT CHECK (end_sequence >= start_sequence);
ALTER TABLE bot_run ADD CHECK (stop_sequence IS NULL OR end_sequence IS NULL OR end_sequence >= stop_sequence);

-- Each instrument's newest trade sequence at the run's edges: its trades in the window are those after the first and
-- up to the second, found through the trades' unique (simulation, instrument, trade sequence) index.
ALTER TABLE bot_run_instrument ADD COLUMN start_trade_sequence BIGINT CHECK (start_trade_sequence >= 0);
ALTER TABLE bot_run_instrument ADD COLUMN end_trade_sequence BIGINT
    CHECK (end_trade_sequence >= start_trade_sequence);

GRANT UPDATE (stop_sequence, end_sequence) ON bot_run TO execution_app;
GRANT UPDATE (end_trade_sequence) ON bot_run_instrument TO execution_app;
