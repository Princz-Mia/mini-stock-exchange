-- An operator may give a simulation a duration when opening it, and the exchange then closes it itself at closes_at:
-- the opening's time on the service's clock plus the duration. Only a simulation that was opened has one, and it
-- keeps it once closed, as the time it was meant to close. It is not compared with opened_at, which the database's
-- clock writes, for the reason closed_at is not.
ALTER TABLE simulation ADD COLUMN closes_at TIMESTAMPTZ;
ALTER TABLE simulation ADD CONSTRAINT simulation_closes_at_opened
    CHECK (closes_at IS NULL OR opened_at IS NOT NULL);

-- A close the exchange made at the simulation's time is recorded under the operator who opened it, as its own
-- action, so the log tells it from one an operator sent.
ALTER TABLE audit_log DROP CONSTRAINT audit_log_action_check;
ALTER TABLE audit_log ADD CONSTRAINT audit_log_action_check
    CHECK (action IN ('MARKET_OPEN', 'MARKET_HALT', 'MARKET_RESUME', 'INSTRUMENT_ADD', 'ACCOUNT_PROVISION',
                      'BOT_PROVISION', 'BOT_RUN_START', 'BOT_RUN_STOP', 'BOT_TOKEN_REVOKE', 'INSTRUMENT_EDIT',
                      'INSTRUMENT_DELETE', 'TRADER_ADD', 'MARKET_CLOSE', 'MARKET_CLOSE_SWEEP', 'SIMULATION_START',
                      'MARKET_CLOSE_SCHEDULED'));

-- The runtime role reads the table already; the opening writes the new column.
GRANT UPDATE (closes_at) ON simulation TO execution_app;
