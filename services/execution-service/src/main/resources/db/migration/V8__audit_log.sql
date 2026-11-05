-- Operator control actions, written in the same transaction as the command they record, including a
-- refused one. The correlation ID is the command's idempotency key, which also finds its stored result.
-- No credentials or request bodies are kept here.
CREATE TABLE audit_log (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    simulation_id    UUID NOT NULL REFERENCES simulation (id),
    actor_user_id    UUID NOT NULL REFERENCES app_user (id),
    action           TEXT NOT NULL CHECK (action IN ('MARKET_OPEN', 'MARKET_HALT', 'MARKET_RESUME')),
    outcome          TEXT NOT NULL CHECK (outcome IN ('APPLIED', 'NOT_ALLOWED')),
    correlation_id   UUID NOT NULL,
    command_sequence BIGINT NOT NULL CHECK (command_sequence > 0),
    occurred_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (simulation_id, command_sequence)
);

CREATE TRIGGER audit_log_append_only BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_change();

GRANT SELECT, INSERT ON audit_log TO execution_app;
