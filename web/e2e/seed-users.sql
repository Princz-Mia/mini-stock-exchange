-- The browser test's counterparty: a second trader to trade against the dev trader. There is no API for creating
-- users, by design, so the gate inserts this login into its throwaway execution_db as execution_owner. Everything
-- with money in it (instruments, accounts, endowments, opening) goes through the operator API in the test.
-- The password comes from psql: -v counterparty_password=...
\set ON_ERROR_STOP on
INSERT INTO app_user (id, username, password_hash, role)
VALUES ('10000000-0000-0000-0000-0000000000c2', 'counterparty', '{noop}' || :'counterparty_password', 'TRADER');
