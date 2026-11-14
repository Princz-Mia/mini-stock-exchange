-- An instrument may have a name to show beside its symbol, set when it is added and changed while the market is in
-- SETUP. The writer checks the full rule (printable characters in any language, plain single spaces); the CHECK keeps
-- the parts SQL can see: the length in characters, no space at either end or twice in a row, no control characters.
ALTER TABLE instrument ADD COLUMN display_name TEXT
    CHECK (display_name IS NULL OR (char_length(display_name) BETWEEN 1 AND 100
        AND display_name = btrim(display_name, ' ')
        AND position('  ' IN display_name) = 0
        AND display_name !~ '[[:cntrl:]]'));

GRANT UPDATE (display_name) ON instrument TO execution_app;

-- The name is frozen with the catalogue once the market opens, as the price is.
DROP TRIGGER instrument_frozen_after_setup ON instrument;
CREATE TRIGGER instrument_frozen_after_setup BEFORE UPDATE OF reference_price_cents, display_name ON instrument
    FOR EACH ROW EXECUTE FUNCTION instrument_frozen_after_setup();
