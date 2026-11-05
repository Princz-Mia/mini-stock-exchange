-- Bot credentials. Only the SHA-256 hash of a token is stored; the token itself leaves the service once,
-- when it is issued. Each token belongs to one BOT account and carries one scope.
CREATE TABLE api_token (
    id           UUID PRIMARY KEY,
    account_id   UUID NOT NULL,
    account_kind TEXT NOT NULL CHECK (account_kind = 'BOT'),
    token_hash   BYTEA NOT NULL UNIQUE CHECK (octet_length(token_hash) = 32),
    scope        TEXT NOT NULL CHECK (scope = 'TRADE'),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at   TIMESTAMPTZ,
    FOREIGN KEY (account_id, account_kind) REFERENCES account (id, kind)
);

CREATE INDEX api_token_account ON api_token (account_id);

-- Revocation is one-way: a revoked token stays revoked.
CREATE FUNCTION api_token_revoke_once() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.revoked_at IS NOT NULL THEN
        RAISE EXCEPTION 'api token % is already revoked', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER api_token_revoked_once BEFORE UPDATE ON api_token
    FOR EACH ROW EXECUTE FUNCTION api_token_revoke_once();

GRANT SELECT, INSERT ON api_token TO execution_app;
GRANT UPDATE (revoked_at) ON api_token TO execution_app;
