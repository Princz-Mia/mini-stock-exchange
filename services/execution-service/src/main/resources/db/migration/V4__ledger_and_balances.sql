-- Append-only journal. Every movement of cash or shares is a batch of postings that sums to zero
-- for each asset; a reservation moves an amount from AVAILABLE to RESERVED in the same account.
CREATE TABLE journal_batch (
    id               UUID PRIMARY KEY,
    simulation_id    UUID NOT NULL REFERENCES simulation (id),
    cause            TEXT NOT NULL CHECK (cause IN ('GENESIS', 'RESERVE', 'RELEASE', 'TRADE')),
    command_sequence BIGINT NOT NULL CHECK (command_sequence > 0),
    trade_id         UUID,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- instrument_id NULL means cash; otherwise the posting is in shares of that instrument.
CREATE TABLE ledger_entry (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    batch_id      UUID NOT NULL REFERENCES journal_batch (id),
    account_id    UUID NOT NULL REFERENCES account (id),
    instrument_id UUID REFERENCES instrument (id),
    bucket        TEXT NOT NULL CHECK (bucket IN ('AVAILABLE', 'RESERVED')),
    delta         BIGINT NOT NULL CHECK (delta <> 0)
);

CREATE INDEX ledger_entry_batch ON ledger_entry (batch_id);
CREATE INDEX ledger_entry_account ON ledger_entry (account_id, instrument_id);

-- A row CHECK cannot see other rows, so balance is checked per batch when the transaction commits.
CREATE FUNCTION ledger_batch_must_balance() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM ledger_entry WHERE batch_id = NEW.batch_id
               GROUP BY instrument_id HAVING sum(delta) <> 0) THEN
        RAISE EXCEPTION 'journal batch % does not balance', NEW.batch_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ledger_entry_balanced
    AFTER INSERT ON ledger_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_batch_must_balance();

-- Grants already stop the runtime role; these triggers also stop the owner and any future role.
CREATE FUNCTION reject_append_only_change() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME USING ERRCODE = 'insufficient_privilege';
END;
$$;

-- Customer balances only. The ISSUANCE account may go negative, so it has no row here; its
-- balance is derived from the journal. The composite key makes that a constraint, not a convention.
CREATE TABLE cash_balance (
    account_id     UUID PRIMARY KEY,
    account_kind   TEXT NOT NULL CHECK (account_kind IN ('TRADER', 'BOT')),
    total_cents    BIGINT NOT NULL CHECK (total_cents >= 0),
    reserved_cents BIGINT NOT NULL CHECK (reserved_cents >= 0),
    CHECK (reserved_cents <= total_cents),
    FOREIGN KEY (account_id, account_kind) REFERENCES account (id, kind)
);

CREATE TABLE position (
    account_id        UUID NOT NULL,
    account_kind      TEXT NOT NULL CHECK (account_kind IN ('TRADER', 'BOT')),
    instrument_id     UUID NOT NULL REFERENCES instrument (id),
    total_quantity    BIGINT NOT NULL CHECK (total_quantity >= 0),
    reserved_quantity BIGINT NOT NULL CHECK (reserved_quantity >= 0),
    PRIMARY KEY (account_id, instrument_id),
    CHECK (reserved_quantity <= total_quantity),
    FOREIGN KEY (account_id, account_kind) REFERENCES account (id, kind)
);

-- The scenario's starting allocations, each linked to the genesis batch that funded it.
CREATE TABLE initial_allocation (
    account_id       UUID NOT NULL REFERENCES account (id),
    instrument_id    UUID REFERENCES instrument (id),
    amount           BIGINT NOT NULL CHECK (amount > 0),
    journal_batch_id UUID NOT NULL REFERENCES journal_batch (id),
    UNIQUE NULLS NOT DISTINCT (account_id, instrument_id)
);

CREATE TRIGGER journal_batch_append_only BEFORE UPDATE OR DELETE ON journal_batch
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_change();
CREATE TRIGGER ledger_entry_append_only BEFORE UPDATE OR DELETE ON ledger_entry
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_change();
CREATE TRIGGER initial_allocation_append_only BEFORE UPDATE OR DELETE ON initial_allocation
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_change();
CREATE TRIGGER journal_batch_no_truncate BEFORE TRUNCATE ON journal_batch
    FOR EACH STATEMENT EXECUTE FUNCTION reject_append_only_change();
CREATE TRIGGER ledger_entry_no_truncate BEFORE TRUNCATE ON ledger_entry
    FOR EACH STATEMENT EXECUTE FUNCTION reject_append_only_change();

GRANT SELECT, INSERT ON journal_batch, ledger_entry, initial_allocation, cash_balance, position TO execution_app;
GRANT UPDATE (total_cents, reserved_cents) ON cash_balance TO execution_app;
GRANT UPDATE (total_quantity, reserved_quantity) ON position TO execution_app;
