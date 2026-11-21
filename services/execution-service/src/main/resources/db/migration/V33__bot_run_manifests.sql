-- What the worker reported about itself with a run: its version (the package and the commit it was built from), the
-- runtime its decisions depend on, and the settings its strategies ran with. Two runs on different code or defaults
-- then look different. Values the run itself sets (order size, position limit) are in bot_run already.
--
-- The run's manifest is the first one reported, and it never changes: the runtime role may only insert. A worker
-- that takes the run over with different code or settings, after a rebuild say, adds its own beside it, so the
-- change shows; the same manifest again adds nothing. At most eight per run, checked under the run's row lock.
CREATE TABLE bot_run_manifest (
    run_id      UUID NOT NULL REFERENCES bot_run (id),
    manifest    JSONB NOT NULL CHECK (jsonb_typeof(manifest) = 'object' AND octet_length(manifest::text) <= 8192),
    -- jsonb's text is canonical, so the same manifest always has the same digest, whatever its key order.
    digest      TEXT NOT NULL GENERATED ALWAYS AS (md5(manifest::text)) STORED,
    version     TEXT NOT NULL GENERATED ALWAYS AS (manifest ->> 'version') STORED
        CHECK (version ~ '^[0-9A-Za-z.+_-]{1,64}$'),
    worker_id   TEXT NOT NULL CHECK (length(worker_id) BETWEEN 1 AND 100),
    reported_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (run_id, digest)
);

GRANT SELECT, INSERT ON bot_run_manifest TO execution_app;
