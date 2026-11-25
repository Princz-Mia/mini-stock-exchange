-- A job creates any number of bots during SETUP, in parts made one after another off the writer, each part an
-- ordinary provisioning command with a key derived from the job's and the part's number. The row is the job's
-- progress, moved on by each part in the part's own transaction, so it counts exactly the parts that committed: every
-- operator's screen reads it, and a restart takes a running job up again from its next part.
CREATE TABLE bot_job (
    id                    UUID PRIMARY KEY,
    simulation_id         UUID NOT NULL REFERENCES simulation (id),
    started_by            UUID NOT NULL REFERENCES app_user (id),
    idempotency_key       UUID NOT NULL,
    state                 TEXT NOT NULL CHECK (state IN ('RUNNING', 'DONE', 'STOPPED', 'FAILED')),
    part_size             INT NOT NULL CHECK (part_size > 0),
    market_makers         INT NOT NULL CHECK (market_makers >= 0),
    noise                 INT NOT NULL CHECK (noise >= 0),
    momentum              INT NOT NULL CHECK (momentum >= 0),
    created_market_makers INT NOT NULL CHECK (created_market_makers BETWEEN 0 AND market_makers),
    created_noise         INT NOT NULL CHECK (created_noise BETWEEN 0 AND noise),
    created_momentum      INT NOT NULL CHECK (created_momentum BETWEEN 0 AND momentum),
    parts_done            INT NOT NULL CHECK (parts_done >= 0),
    cash_cents            BIGINT NOT NULL CHECK (cash_cents >= 0),
    holdings              JSONB NOT NULL CHECK (jsonb_typeof(holdings) = 'array'),
    started_at            TIMESTAMPTZ NOT NULL,
    -- When the process that has the job took it up, with how many bots were made then: the time left is estimated
    -- from the rate since.
    run_started_at        TIMESTAMPTZ NOT NULL,
    created_at_run_start  INT NOT NULL CHECK (created_at_run_start >= 0),
    last_part_at          TIMESTAMPTZ,
    finished_at           TIMESTAMPTZ,
    failure               TEXT,
    resumed               INT NOT NULL CHECK (resumed >= 0),
    -- Counted as bigint: three counts near the int maximum must not overflow the check itself.
    CHECK (market_makers::bigint + noise + momentum BETWEEN 1 AND 2147483647),
    CHECK ((state = 'RUNNING') = (finished_at IS NULL)),
    CHECK ((state = 'FAILED') = (failure IS NOT NULL)),
    CHECK (state <> 'DONE' OR (created_market_makers = market_makers AND created_noise = noise
                               AND created_momentum = momentum)),
    UNIQUE (simulation_id, started_by, idempotency_key)
);

-- At most one job runs in a simulation; the newest is the one the operator's screen shows.
CREATE UNIQUE INDEX bot_job_one_running ON bot_job (simulation_id) WHERE state = 'RUNNING';
CREATE INDEX bot_job_newest ON bot_job (simulation_id, started_at DESC, id DESC);

-- Starting, stopping and failing a job are audited; its parts are audited as BOT_PROVISION, as bots made by hand are.
ALTER TABLE audit_log DROP CONSTRAINT audit_log_action_check;
ALTER TABLE audit_log ADD CONSTRAINT audit_log_action_check
    CHECK (action IN ('MARKET_OPEN', 'MARKET_HALT', 'MARKET_RESUME', 'INSTRUMENT_ADD', 'ACCOUNT_PROVISION',
                      'BOT_PROVISION', 'BOT_RUN_START', 'BOT_RUN_STOP', 'BOT_TOKEN_REVOKE', 'INSTRUMENT_EDIT',
                      'INSTRUMENT_DELETE', 'TRADER_ADD', 'MARKET_CLOSE', 'MARKET_CLOSE_SWEEP', 'SIMULATION_START',
                      'MARKET_CLOSE_SCHEDULED', 'BOT_JOB_START', 'BOT_JOB_STOP', 'BOT_JOB_FAIL'));

-- What a job is asked for never changes; its state, progress and the run that has it do.
GRANT SELECT, INSERT ON bot_job TO execution_app;
GRANT UPDATE (state, created_market_makers, created_noise, created_momentum, parts_done, run_started_at,
              created_at_run_start, last_part_at, finished_at, failure, resumed) ON bot_job TO execution_app;
