#!/bin/bash
# Runs once, on an empty data directory. One server, two logical databases, no shared access:
# each service has an owner role for Flyway and a least-privilege runtime role, and can connect only to its own database.
set -euo pipefail

: "${EXECUTION_DB_OWNER_PASSWORD:?must be set}"
: "${EXECUTION_DB_APP_PASSWORD:?must be set}"
: "${MARKET_DATA_DB_OWNER_PASSWORD:?must be set}"
: "${MARKET_DATA_DB_APP_PASSWORD:?must be set}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
  -v execution_owner_password="$EXECUTION_DB_OWNER_PASSWORD" \
  -v execution_app_password="$EXECUTION_DB_APP_PASSWORD" \
  -v market_data_owner_password="$MARKET_DATA_DB_OWNER_PASSWORD" \
  -v market_data_app_password="$MARKET_DATA_DB_APP_PASSWORD" <<'SQL'
CREATE ROLE execution_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'execution_owner_password';
CREATE ROLE execution_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'execution_app_password';
CREATE ROLE market_data_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'market_data_owner_password';
CREATE ROLE market_data_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'market_data_app_password';

CREATE DATABASE execution_db OWNER execution_owner;
CREATE DATABASE market_data_db OWNER market_data_owner;

REVOKE ALL ON DATABASE execution_db FROM PUBLIC;
REVOKE ALL ON DATABASE market_data_db FROM PUBLIC;
REVOKE CONNECT ON DATABASE postgres FROM PUBLIC;
REVOKE CONNECT ON DATABASE template1 FROM PUBLIC;

GRANT CONNECT ON DATABASE execution_db TO execution_app;
GRANT CONNECT ON DATABASE market_data_db TO market_data_app;

\connect execution_db
ALTER SCHEMA public OWNER TO execution_owner;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO execution_app;

\connect market_data_db
ALTER SCHEMA public OWNER TO market_data_owner;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO market_data_app;
SQL
