-- Small per-user settings that follow a user across browsers, unlike the display preferences a browser keeps. The
-- first is the newest version of the first-login tour the user has seen; a new version shows once more. No DELETE:
-- a preference is only ever raised.
CREATE TABLE user_preference (
    user_id           UUID PRIMARY KEY REFERENCES app_user (id),
    tour_version_seen INTEGER NOT NULL CHECK (tour_version_seen >= 0),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

GRANT SELECT, INSERT, UPDATE ON user_preference TO execution_app;
