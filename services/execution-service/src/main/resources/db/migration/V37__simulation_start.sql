-- An operator starts a new simulation once the one before it has closed, and gives it a name to tell it from the
-- others. A simulation created by the first setup command has none. The name is written with the row and never
-- changed, so the runtime role's INSERT and SELECT on the table are all it needs.
ALTER TABLE simulation ADD COLUMN name TEXT;
ALTER TABLE simulation ADD CONSTRAINT simulation_name_check
    CHECK (name IS NULL OR (length(name) BETWEEN 1 AND 80 AND name = btrim(name)));

-- Names differ, whatever their case. No command changes a name, so a command that moves the sequence still updates
-- the row in place.
CREATE UNIQUE INDEX simulation_name_unique ON simulation (lower(name));

-- Starting a simulation is an audited operator action: the first command of the simulation it starts, or a refusal
-- recorded in the one that was current.
ALTER TABLE audit_log DROP CONSTRAINT audit_log_action_check;
ALTER TABLE audit_log ADD CONSTRAINT audit_log_action_check
    CHECK (action IN ('MARKET_OPEN', 'MARKET_HALT', 'MARKET_RESUME', 'INSTRUMENT_ADD', 'ACCOUNT_PROVISION',
                      'BOT_PROVISION', 'BOT_RUN_START', 'BOT_RUN_STOP', 'BOT_TOKEN_REVOKE', 'INSTRUMENT_EDIT',
                      'INSTRUMENT_DELETE', 'TRADER_ADD', 'MARKET_CLOSE', 'MARKET_CLOSE_SWEEP', 'SIMULATION_START'));

-- No new grants. A start sets the closed simulation's writer epoch to 0, a value no writer ever holds (a market is
-- created at 1 and every takeover adds one), and the runtime role already updates that column.
