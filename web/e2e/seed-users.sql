-- The browser test's counterparty, a second trader to trade against the dev trader, and a newcomer for the tour.
-- There is no API for creating users, by design, so the gate inserts these logins into its throwaway execution_db as
-- execution_owner. Everything with money in it (instruments, accounts, endowments, opening) goes through the operator
-- API in the test.
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
-- A trader who has never signed in, for the first-login tour: same password as the counterparty, and no account.
INSERT INTO app_user (id, username, password_hash, role)
VALUES ('10000000-0000-0000-0000-0000000000c3', 'newcomer',
    '{bcrypt}' || seed_crypto.crypt(:'counterparty_password', seed_crypto.gen_salt('bf', 10)), 'TRADER');
-- Everyone else has seen every tour, so it does not stand in front of the other browser tests.
INSERT INTO user_preference (user_id, tour_version_seen)
SELECT id, 1000 FROM app_user WHERE username IN ('trader', 'operator', 'counterparty');
DROP EXTENSION pgcrypto;
DROP SCHEMA seed_crypto;
COMMIT;
