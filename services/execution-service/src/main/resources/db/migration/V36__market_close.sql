-- Closing the market is an audited operator action. A close cancels every resting order, in parts when there are
-- many: each later part takes its own command sequence and is recorded as MARKET_CLOSE_SWEEP under the operator who
-- closed.
ALTER TABLE audit_log DROP CONSTRAINT audit_log_action_check;
ALTER TABLE audit_log ADD CONSTRAINT audit_log_action_check
    CHECK (action IN ('MARKET_OPEN', 'MARKET_HALT', 'MARKET_RESUME', 'INSTRUMENT_ADD', 'ACCOUNT_PROVISION',
                      'BOT_PROVISION', 'BOT_RUN_START', 'BOT_RUN_STOP', 'BOT_TOKEN_REVOKE', 'INSTRUMENT_EDIT',
                      'INSTRUMENT_DELETE', 'TRADER_ADD', 'MARKET_CLOSE', 'MARKET_CLOSE_SWEEP'));

-- A run still going when the market closes ends there:
--   MARKET_CLOSE  the close ended it, at the close's own sequence. No worker confirms it: the close itself cancels
--                 the bots' orders and revokes their tokens, so nothing of the run can commit after it.
ALTER TABLE bot_run DROP CONSTRAINT bot_run_ended_by_check;
ALTER TABLE bot_run ADD CONSTRAINT bot_run_ended_by_check
    CHECK (ended_by IN ('WORKER', 'NEXT_START', 'NOT_KEPT', 'MARKET_CLOSE'));

-- No new grants: the runtime role already updates every column a close writes (the simulation's state and closed_at,
-- a run's desired state, stop and end, a token's revoked_at, an order's state and reason).
