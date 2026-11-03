-- The first committed result of each command, by principal and idempotency key. A retry with the
-- same key and request hash gets this stored response; a different request with that key is a conflict.
CREATE TABLE command_result (
    simulation_id    UUID NOT NULL REFERENCES simulation (id),
    principal_id     UUID NOT NULL,
    idempotency_key  UUID NOT NULL,
    route            TEXT NOT NULL CHECK (length(route) BETWEEN 1 AND 200),
    request_hash     BYTEA NOT NULL CHECK (octet_length(request_hash) = 32),
    response_status  INT NOT NULL CHECK (response_status BETWEEN 100 AND 599),
    -- A stored HTTP response for replay, not domain state; the domain lives in the tables above.
    response_body    JSONB NOT NULL,
    command_sequence BIGINT NOT NULL CHECK (command_sequence > 0),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (simulation_id, principal_id, idempotency_key),
    UNIQUE (simulation_id, command_sequence)
);

CREATE TRIGGER command_result_append_only BEFORE UPDATE OR DELETE ON command_result
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_change();

-- Events written in the same transaction as the change they describe, then published in id order.
-- Only the publication time may change; the encoded payload never does.
CREATE TABLE outbox (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id           UUID NOT NULL UNIQUE,
    topic              TEXT NOT NULL CHECK (topic ~ '^mse\.[a-z]+\.v[0-9]+$'),
    event_key          TEXT NOT NULL,
    schema_fingerprint BIGINT NOT NULL,
    payload            BYTEA NOT NULL CHECK (octet_length(payload) > 0),
    command_sequence   BIGINT NOT NULL CHECK (command_sequence > 0),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at       TIMESTAMPTZ
);

CREATE INDEX outbox_pending ON outbox (id) WHERE published_at IS NULL;

-- Publication is one-way: a published row stays published.
CREATE FUNCTION outbox_publish_once() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.published_at IS NOT NULL THEN
        RAISE EXCEPTION 'outbox event % is already published', OLD.event_id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER outbox_published_once BEFORE UPDATE ON outbox
    FOR EACH ROW EXECUTE FUNCTION outbox_publish_once();
CREATE TRIGGER outbox_no_delete BEFORE DELETE ON outbox
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_change();

GRANT SELECT, INSERT ON command_result, outbox TO execution_app;
GRANT UPDATE (published_at) ON outbox TO execution_app;
