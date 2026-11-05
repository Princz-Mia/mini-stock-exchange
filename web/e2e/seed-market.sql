-- A small open market for the browser test, written straight into a throwaway stack's execution_db as
-- execution_owner, until the operator setup command exists. Execution must be restarted afterwards so that it
-- rebuilds its books from the database. The instrument reaches Market Data with the first order's snapshot.
--
-- One instrument, NOVA, with a reference price of 100.00, and two funded traders: the dev user "trader" and a
-- counterparty, "counterparty", whose password comes from psql: -v counterparty_password=...
-- Each trader gets 100,000.00 and 500 shares from a balanced genesis batch.
\set ON_ERROR_STOP on
BEGIN;
INSERT INTO simulation (id, state, opened_at) VALUES ('10000000-0000-0000-0000-000000000001', 'OPEN', now());
INSERT INTO account (id, simulation_id, kind, display_name)
VALUES ('10000000-0000-0000-0000-0000000000a0', '10000000-0000-0000-0000-000000000001', 'ISSUANCE', 'Issuance');
INSERT INTO instrument (id, simulation_id, symbol, reference_price_cents)
VALUES ('10000000-0000-0000-0000-0000000000b1', '10000000-0000-0000-0000-000000000001', 'NOVA', 10000);
INSERT INTO app_user (id, username, password_hash, role)
VALUES ('10000000-0000-0000-0000-0000000000c2', 'counterparty', '{noop}' || :'counterparty_password', 'TRADER');
INSERT INTO account (id, simulation_id, kind, display_name, owner_user_id)
SELECT '10000000-0000-0000-0000-0000000000d1', '10000000-0000-0000-0000-000000000001', 'TRADER', 'trader', id
FROM app_user WHERE username = 'trader';
INSERT INTO account (id, simulation_id, kind, display_name, owner_user_id)
VALUES ('10000000-0000-0000-0000-0000000000d2', '10000000-0000-0000-0000-000000000001', 'TRADER', 'counterparty',
        '10000000-0000-0000-0000-0000000000c2');
INSERT INTO journal_batch (id, simulation_id, cause, command_sequence)
VALUES ('10000000-0000-0000-0000-0000000000e1', '10000000-0000-0000-0000-000000000001', 'GENESIS', 1);
INSERT INTO ledger_entry (batch_id, account_id, instrument_id, bucket, delta) VALUES
  ('10000000-0000-0000-0000-0000000000e1', '10000000-0000-0000-0000-0000000000a0', NULL, 'AVAILABLE', -20000000),
  ('10000000-0000-0000-0000-0000000000e1', '10000000-0000-0000-0000-0000000000d1', NULL, 'AVAILABLE', 10000000),
  ('10000000-0000-0000-0000-0000000000e1', '10000000-0000-0000-0000-0000000000d2', NULL, 'AVAILABLE', 10000000),
  ('10000000-0000-0000-0000-0000000000e1', '10000000-0000-0000-0000-0000000000a0', '10000000-0000-0000-0000-0000000000b1', 'AVAILABLE', -1000),
  ('10000000-0000-0000-0000-0000000000e1', '10000000-0000-0000-0000-0000000000d1', '10000000-0000-0000-0000-0000000000b1', 'AVAILABLE', 500),
  ('10000000-0000-0000-0000-0000000000e1', '10000000-0000-0000-0000-0000000000d2', '10000000-0000-0000-0000-0000000000b1', 'AVAILABLE', 500);
INSERT INTO cash_balance (account_id, account_kind, total_cents, reserved_cents) VALUES
  ('10000000-0000-0000-0000-0000000000d1', 'TRADER', 10000000, 0),
  ('10000000-0000-0000-0000-0000000000d2', 'TRADER', 10000000, 0);
INSERT INTO position (account_id, account_kind, instrument_id, total_quantity, reserved_quantity) VALUES
  ('10000000-0000-0000-0000-0000000000d1', 'TRADER', '10000000-0000-0000-0000-0000000000b1', 500, 0),
  ('10000000-0000-0000-0000-0000000000d2', 'TRADER', '10000000-0000-0000-0000-0000000000b1', 500, 0);
COMMIT;
