-- Proves this service's migration ran in its own database, owned by its own role.
-- Runtime privileges are granted per table; market_data_app has no DDL rights.
CREATE TABLE service_identity (
    service_name TEXT PRIMARY KEY,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO service_identity (service_name) VALUES ('market-data-service');

GRANT SELECT ON service_identity TO market_data_app;
