-- A user may hold more than one role: the operator also trades. Roles move from app_user.role to their own rows,
-- added and never removed. Nothing in the database stops the runtime from adding an OPERATOR row, as nothing did
-- before; only code adds roles: configuration at startup, and adding a trader login (TRADER, for the user it
-- creates). Roles are global; accounts stay per simulation.
CREATE TABLE user_role (
    user_id UUID NOT NULL REFERENCES app_user (id),
    role    TEXT NOT NULL CHECK (role IN ('TRADER', 'OPERATOR')),
    PRIMARY KEY (user_id, role)
);

INSERT INTO user_role (user_id, role) SELECT id, role FROM app_user;
ALTER TABLE app_user DROP COLUMN role;

GRANT SELECT, INSERT ON user_role TO execution_app;
