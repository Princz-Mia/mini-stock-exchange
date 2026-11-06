-- A bot run: what the operator asked the simulator to do, and what its worker reports back. At most one run per
-- simulation is running; finished runs stay as history.
CREATE TABLE bot_run (
    id                  UUID PRIMARY KEY,
    simulation_id       UUID NOT NULL REFERENCES simulation (id),
    desired_state       TEXT NOT NULL CHECK (desired_state IN ('RUNNING', 'STOPPED')),
    seed                BIGINT NOT NULL CHECK (seed >= 0),
    -- Trading commands per second across all of the run's bots.
    commands_per_second NUMERIC(6, 2) NOT NULL CHECK (commands_per_second > 0 AND commands_per_second <= 200),
    max_order_quantity  INT NOT NULL CHECK (max_order_quantity BETWEEN 1 AND 10000),
    -- The most shares of one instrument a bot may hold beyond what it started with, or short of it.
    max_position        INT NOT NULL CHECK (max_position BETWEEN 1 AND 1000000),
    started_by          UUID NOT NULL REFERENCES app_user (id),
    started_at          TIMESTAMPTZ NOT NULL,
    start_sequence      BIGINT NOT NULL CHECK (start_sequence > 0),
    -- Null runs until stopped.
    ends_at             TIMESTAMPTZ CHECK (ends_at > started_at),
    stop_requested_at   TIMESTAMPTZ,
    -- What the worker reports: its lease and heartbeat, and running totals.
    worker_id           TEXT CHECK (length(worker_id) BETWEEN 1 AND 100),
    lease_expires_at    TIMESTAMPTZ,
    heartbeat_at        TIMESTAMPTZ,
    placed              BIGINT NOT NULL DEFAULT 0 CHECK (placed >= 0),
    canceled            BIGINT NOT NULL DEFAULT 0 CHECK (canceled >= 0),
    rejected            BIGINT NOT NULL DEFAULT 0 CHECK (rejected >= 0),
    errors              BIGINT NOT NULL DEFAULT 0 CHECK (errors >= 0),
    in_flight           INT NOT NULL DEFAULT 0 CHECK (in_flight >= 0),
    stop_failures       INT NOT NULL DEFAULT 0 CHECK (stop_failures >= 0),
    stopped_at          TIMESTAMPTZ,
    CHECK ((desired_state = 'STOPPED') = (stop_requested_at IS NOT NULL)),
    CHECK (stopped_at IS NULL OR stop_requested_at IS NOT NULL),
    UNIQUE (simulation_id, id)
);

CREATE UNIQUE INDEX bot_run_one_running ON bot_run (simulation_id) WHERE desired_state = 'RUNNING';
CREATE INDEX bot_run_recent ON bot_run (simulation_id, started_at DESC, id DESC);

-- The instruments a run's bots trade.
CREATE TABLE bot_run_instrument (
    run_id        UUID NOT NULL,
    simulation_id UUID NOT NULL,
    instrument_id UUID NOT NULL,
    PRIMARY KEY (run_id, instrument_id),
    FOREIGN KEY (simulation_id, run_id) REFERENCES bot_run (simulation_id, id),
    FOREIGN KEY (simulation_id, instrument_id) REFERENCES instrument (simulation_id, id)
);

-- Which bot account runs which strategy in a run.
CREATE TABLE bot_assignment (
    run_id       UUID NOT NULL REFERENCES bot_run (id),
    account_id   UUID NOT NULL,
    account_kind TEXT NOT NULL CHECK (account_kind = 'BOT'),
    strategy     TEXT NOT NULL CHECK (strategy IN ('NOISE', 'MOMENTUM', 'MARKET_MAKER')),
    PRIMARY KEY (run_id, account_id),
    FOREIGN KEY (account_id, account_kind) REFERENCES account (id, kind)
);

-- The runtime role records runs and their reports; the configuration and assignments never change.
GRANT SELECT, INSERT ON bot_run, bot_run_instrument, bot_assignment TO execution_app;
GRANT UPDATE (desired_state, stop_requested_at, worker_id, lease_expires_at, heartbeat_at, placed, canceled, rejected,
              errors, in_flight, stop_failures, stopped_at) ON bot_run TO execution_app;

-- Starting and stopping a run are audited operator actions.
ALTER TABLE audit_log DROP CONSTRAINT audit_log_action_check;
ALTER TABLE audit_log ADD CONSTRAINT audit_log_action_check
    CHECK (action IN ('MARKET_OPEN', 'MARKET_HALT', 'MARKET_RESUME', 'INSTRUMENT_ADD', 'ACCOUNT_PROVISION',
                      'BOT_PROVISION', 'BOT_RUN_START', 'BOT_RUN_STOP'));
