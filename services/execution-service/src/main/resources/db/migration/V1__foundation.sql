-- Proves this service's migration ran in its own database, owned by its own role.
-- Runtime privileges are granted per table; execution_app has no DDL rights.
CREATE TABLE service_identity (
    service_name TEXT PRIMARY KEY,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO service_identity (service_name) VALUES ('execution-service');

GRANT SELECT ON service_identity TO execution_app;

-- Spring Session JDBC 4.1.1 schema-postgresql.sql, owned here so the runtime role never needs DDL.
CREATE TABLE spring_session (
    primary_id            CHAR(36) NOT NULL,
    session_id            CHAR(36) NOT NULL,
    creation_time         BIGINT NOT NULL,
    last_access_time      BIGINT NOT NULL,
    max_inactive_interval INT NOT NULL,
    expiry_time           BIGINT NOT NULL,
    principal_name        VARCHAR(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);

CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
    session_primary_id CHAR(36) NOT NULL,
    attribute_name     VARCHAR(200) NOT NULL,
    attribute_bytes    BYTEA NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES spring_session (primary_id) ON DELETE CASCADE
);

GRANT SELECT, INSERT, UPDATE, DELETE ON spring_session, spring_session_attributes TO execution_app;
