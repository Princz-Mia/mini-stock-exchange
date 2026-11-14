-- The operator may delete an instrument while the market is in SETUP and nothing refers to it. The writer checks
-- both; the foreign keys refuse a delete of an instrument in use, and the freeze trigger now also refuses a delete
-- once the market has opened, even against the grant.
GRANT DELETE ON instrument TO execution_app;

-- In a DELETE trigger NEW is null, and a BEFORE trigger that returns null skips the row, so the function returns the
-- row the operation works on.
CREATE OR REPLACE FUNCTION instrument_frozen_after_setup() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF (SELECT state FROM simulation WHERE id = OLD.simulation_id) IS DISTINCT FROM 'SETUP' THEN
        RAISE EXCEPTION 'instrument % can only be changed while the market is in SETUP', OLD.symbol
            USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER instrument_delete_frozen_after_setup BEFORE DELETE ON instrument
    FOR EACH ROW EXECUTE FUNCTION instrument_frozen_after_setup();

ALTER TABLE audit_log DROP CONSTRAINT audit_log_action_check;
ALTER TABLE audit_log ADD CONSTRAINT audit_log_action_check
    CHECK (action IN ('MARKET_OPEN', 'MARKET_HALT', 'MARKET_RESUME', 'INSTRUMENT_ADD', 'ACCOUNT_PROVISION',
                      'BOT_PROVISION', 'BOT_RUN_START', 'BOT_RUN_STOP', 'BOT_TOKEN_REVOKE', 'INSTRUMENT_EDIT',
                      'INSTRUMENT_DELETE'));
