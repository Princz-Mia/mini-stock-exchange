-- A signed-in trader may change their own password. Each change raises the login's password version; a session
-- remembers the version it signed in with, so one started with an old password ends on its next request. The
-- runtime role may update the hash and the version, and nothing else of a login: usernames and roles stay as they
-- were added, and no login is removed.
ALTER TABLE app_user ADD COLUMN password_version INT NOT NULL DEFAULT 0 CHECK (password_version >= 0);

GRANT UPDATE (password_hash, password_version) ON app_user TO execution_app;
