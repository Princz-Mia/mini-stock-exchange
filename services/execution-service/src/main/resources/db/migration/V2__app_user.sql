CREATE TABLE app_user (
    id            UUID PRIMARY KEY,
    username      TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    role          TEXT NOT NULL CHECK (role IN ('TRADER', 'OPERATOR')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- No UPDATE or DELETE: the runtime role can neither change a password nor remove a user.
GRANT SELECT, INSERT ON app_user TO execution_app;
