-- The browser test's counterparty: a second trader to trade against the dev trader. There is no API for creating
-- users, by design, so the gate inserts this login into its throwaway execution_db as execution_owner. Everything
-- with money in it (instruments, accounts, endowments, opening) goes through the operator API in the test.
-- The password comes from psql: -v counterparty_password=...
-- It is stored as bcrypt, like every other login; the service accepts no other form. pgcrypto makes the hash in a
-- scratch schema that is dropped afterwards, so nothing of it stays in the database.
\set ON_ERROR_STOP on
BEGIN;
CREATE SCHEMA seed_crypto;
CREATE EXTENSION pgcrypto SCHEMA seed_crypto;
INSERT INTO app_user (id, username, password_hash, role)
VALUES ('10000000-0000-0000-0000-0000000000c2', 'counterparty',
    '{bcrypt}' || seed_crypto.crypt(:'counterparty_password', seed_crypto.gen_salt('bf', 10)), 'TRADER');
DROP EXTENSION pgcrypto;
DROP SCHEMA seed_crypto;
COMMIT;
