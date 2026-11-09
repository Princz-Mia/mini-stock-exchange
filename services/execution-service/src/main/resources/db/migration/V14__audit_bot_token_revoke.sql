-- Revoking a bot's token is an audited operator action.
ALTER TABLE audit_log DROP CONSTRAINT audit_log_action_check;
ALTER TABLE audit_log ADD CONSTRAINT audit_log_action_check
    CHECK (action IN ('MARKET_OPEN', 'MARKET_HALT', 'MARKET_RESUME', 'INSTRUMENT_ADD', 'ACCOUNT_PROVISION',
                      'BOT_PROVISION', 'BOT_RUN_START', 'BOT_RUN_STOP', 'BOT_TOKEN_REVOKE'));
