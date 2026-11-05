-- Provisioning bots during SETUP is an audited operator action too.
ALTER TABLE audit_log DROP CONSTRAINT audit_log_action_check;
ALTER TABLE audit_log ADD CONSTRAINT audit_log_action_check
    CHECK (action IN ('MARKET_OPEN', 'MARKET_HALT', 'MARKET_RESUME', 'INSTRUMENT_ADD', 'ACCOUNT_PROVISION',
                      'BOT_PROVISION'));
