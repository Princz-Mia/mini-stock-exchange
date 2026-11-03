-- One simulation per deployment. The writer epoch fences a stale writer process; the command
-- sequence orders every trading mutation.
CREATE TABLE simulation (
    id                          UUID PRIMARY KEY,
    state                       TEXT NOT NULL CHECK (state IN ('SETUP', 'OPEN', 'HALTED')),
    writer_epoch                BIGINT NOT NULL DEFAULT 0 CHECK (writer_epoch >= 0),
    last_command_sequence       BIGINT NOT NULL DEFAULT 0 CHECK (last_command_sequence >= 0),
    max_open_orders_per_account INT NOT NULL DEFAULT 20 CHECK (max_open_orders_per_account > 0),
    max_open_orders_total       INT NOT NULL DEFAULT 50000 CHECK (max_open_orders_total > 0),
    max_fills_per_command       INT NOT NULL DEFAULT 1000 CHECK (max_fills_per_command > 0),
    opened_at                   TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((state = 'SETUP') = (opened_at IS NULL))
);

CREATE UNIQUE INDEX simulation_single_row ON simulation ((true));

-- TRADER accounts belong to a person; BOT accounts are driven by the simulator; the single
-- ISSUANCE account is the source of all initial cash and shares and is the only account
-- allowed to go negative, so it never gets a balance row.
CREATE TABLE account (
    id            UUID PRIMARY KEY,
    simulation_id UUID NOT NULL REFERENCES simulation (id),
    kind          TEXT NOT NULL CHECK (kind IN ('TRADER', 'BOT', 'ISSUANCE')),
    owner_user_id UUID REFERENCES app_user (id),
    display_name  TEXT NOT NULL CHECK (length(display_name) BETWEEN 1 AND 100),
    version       BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, kind),
    UNIQUE (simulation_id, id),
    CHECK ((kind = 'TRADER') = (owner_user_id IS NOT NULL))
);

CREATE UNIQUE INDEX account_one_issuance ON account (simulation_id) WHERE kind = 'ISSUANCE';
CREATE UNIQUE INDEX account_one_per_user ON account (simulation_id, owner_user_id) WHERE owner_user_id IS NOT NULL;

-- Identity is the UUID; the symbol is an immutable alias. Tick and lot are fixed at one cent and
-- one share, but stored with a rule version so a later change is explicit.
CREATE TABLE instrument (
    id                    UUID PRIMARY KEY,
    simulation_id         UUID NOT NULL REFERENCES simulation (id),
    symbol                TEXT NOT NULL CHECK (symbol ~ '^[A-Z]{1,8}$'),
    reference_price_cents BIGINT NOT NULL CHECK (reference_price_cents BETWEEN 1 AND 100000000),
    tick_cents            BIGINT NOT NULL DEFAULT 1 CHECK (tick_cents = 1),
    lot_size              BIGINT NOT NULL DEFAULT 1 CHECK (lot_size = 1),
    rule_version          INT NOT NULL DEFAULT 1 CHECK (rule_version >= 1),
    last_trade_sequence   BIGINT NOT NULL DEFAULT 0 CHECK (last_trade_sequence >= 0),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (simulation_id, symbol),
    UNIQUE (simulation_id, id)
);

-- Column-level UPDATE grants make everything else immutable for the runtime role.
GRANT SELECT, INSERT ON simulation, account, instrument TO execution_app;
GRANT UPDATE (state, writer_epoch, last_command_sequence, opened_at) ON simulation TO execution_app;
GRANT UPDATE (version) ON account TO execution_app;
GRANT UPDATE (last_trade_sequence) ON instrument TO execution_app;
