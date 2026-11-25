-- A run's bots are read in pages in the order their accounts were created. Kept beside each assignment, that order makes
-- each page one range of an index; read from the account, every page joined and sorted all of the run's assignments
-- again, so reading a run's pages took time in the square of its size.
ALTER TABLE bot_assignment ADD COLUMN account_created_at TIMESTAMPTZ;
UPDATE bot_assignment b SET account_created_at = a.created_at FROM account a WHERE a.id = b.account_id;
ALTER TABLE bot_assignment ALTER COLUMN account_created_at SET NOT NULL;
CREATE INDEX bot_assignment_in_order ON bot_assignment (run_id, account_created_at, account_id);

-- The writer copies it as it assigns; an insert that leaves it out gets the account's own.
CREATE FUNCTION bot_assignment_account_created_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    SELECT created_at INTO NEW.account_created_at FROM account WHERE id = NEW.account_id;
    RETURN NEW;
END;
$$;

CREATE TRIGGER bot_assignment_account_created_at BEFORE INSERT ON bot_assignment
    FOR EACH ROW WHEN (NEW.account_created_at IS NULL) EXECUTE FUNCTION bot_assignment_account_created_at();
