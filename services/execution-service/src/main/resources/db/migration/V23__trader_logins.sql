-- The operator adds trader logins during SETUP. A username has 3 to 32 characters: lower-case letters, digits, '.',
-- '-' and '_', starting with a letter or digit. Logins that already exist, from configuration, are left as they are
-- (NOT VALID); every new one is checked.
ALTER TABLE app_user
    ADD CONSTRAINT app_user_username_format CHECK (username ~ '^[a-z0-9][a-z0-9._-]{2,31}$') NOT VALID;

-- A username is unique ignoring case, as the sign-in limiter already counts failures by the lower-cased name.
CREATE UNIQUE INDEX app_user_username_lower ON app_user (lower(username));

-- Adding a trader is an audited operator action.
ALTER TABLE audit_log DROP CONSTRAINT audit_log_action_check;
ALTER TABLE audit_log ADD CONSTRAINT audit_log_action_check
    CHECK (action IN ('MARKET_OPEN', 'MARKET_HALT', 'MARKET_RESUME', 'INSTRUMENT_ADD', 'ACCOUNT_PROVISION',
                      'BOT_PROVISION', 'BOT_RUN_START', 'BOT_RUN_STOP', 'BOT_TOKEN_REVOKE', 'INSTRUMENT_EDIT',
                      'INSTRUMENT_DELETE', 'TRADER_ADD'));
