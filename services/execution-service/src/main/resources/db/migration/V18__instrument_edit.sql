-- The operator may correct an instrument's reference price while the market is in SETUP. The writer refuses an edit
-- once the market has opened; the trigger keeps the catalogue frozen even against the grant.
GRANT UPDATE (reference_price_cents) ON instrument TO execution_app;

CREATE FUNCTION instrument_frozen_after_setup() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF (SELECT state FROM simulation WHERE id = OLD.simulation_id) IS DISTINCT FROM 'SETUP' THEN
        RAISE EXCEPTION 'instrument % can only be changed while the market is in SETUP', OLD.symbol
            USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;
    RETURN NEW;
END;
$$;

-- Only the columns an operator edits: trading advances last_trade_sequence in every state.
CREATE TRIGGER instrument_frozen_after_setup BEFORE UPDATE OF reference_price_cents ON instrument
    FOR EACH ROW EXECUTE FUNCTION instrument_frozen_after_setup();

ALTER TABLE audit_log DROP CONSTRAINT audit_log_action_check;
ALTER TABLE audit_log ADD CONSTRAINT audit_log_action_check
    CHECK (action IN ('MARKET_OPEN', 'MARKET_HALT', 'MARKET_RESUME', 'INSTRUMENT_ADD', 'ACCOUNT_PROVISION',
                      'BOT_PROVISION', 'BOT_RUN_START', 'BOT_RUN_STOP', 'BOT_TOKEN_REVOKE', 'INSTRUMENT_EDIT'));
