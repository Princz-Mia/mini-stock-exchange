-- Projections rebuilt from Kafka. Nothing here is a source of truth: dropping every row and consuming the
-- retained topics again must give the same result.

-- One row per instrument: the catalogue entry from its latest snapshot.
CREATE TABLE instrument_projection (
    instrument_id           UUID PRIMARY KEY,
    simulation_id           UUID NOT NULL,
    symbol                  TEXT NOT NULL CHECK (symbol ~ '^[A-Z]{1,8}$'),
    rule_version            INT NOT NULL CHECK (rule_version >= 1),
    reference_price_cents   BIGINT NOT NULL CHECK (reference_price_cents > 0),
    tick_cents              BIGINT NOT NULL CHECK (tick_cents > 0),
    lot_size                BIGINT NOT NULL CHECK (lot_size > 0),
    min_quantity            BIGINT NOT NULL CHECK (min_quantity > 0),
    max_quantity            BIGINT NOT NULL CHECK (max_quantity >= min_quantity),
    max_price_cents         BIGINT NOT NULL CHECK (max_price_cents > 0),
    source_command_sequence BIGINT NOT NULL CHECK (source_command_sequence > 0),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per instrument: the complete public market from its latest snapshot. A newer snapshot replaces
-- the row; depth and tape are stored whole, never patched.
CREATE TABLE market_snapshot (
    instrument_id           UUID PRIMARY KEY REFERENCES instrument_projection (instrument_id),
    simulation_id           UUID NOT NULL,
    venue_state             TEXT NOT NULL CHECK (venue_state IN ('SETUP', 'OPEN', 'HALTED', 'UNKNOWN')),
    bids                    JSONB NOT NULL CHECK (jsonb_typeof(bids) = 'array'),
    asks                    JSONB NOT NULL CHECK (jsonb_typeof(asks) = 'array'),
    recent_trades           JSONB NOT NULL CHECK (jsonb_typeof(recent_trades) = 'array'),
    last_price_cents        BIGINT CHECK (last_price_cents > 0),
    last_trade_sequence     BIGINT NOT NULL CHECK (last_trade_sequence >= 0),
    source_command_sequence BIGINT NOT NULL CHECK (source_command_sequence > 0),
    generated_at            TIMESTAMPTZ NOT NULL,
    projected_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Event ids already applied, written in the same transaction as their effect.
CREATE TABLE processed_event (
    event_id     UUID PRIMARY KEY,
    topic        TEXT NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The next Kafka offset to read per partition, committed with the projection changes it covers. This,
-- not a consumer group, is the record of what has been consumed.
CREATE TABLE projection_checkpoint (
    topic       TEXT NOT NULL,
    partition   INT NOT NULL CHECK (partition >= 0),
    next_offset BIGINT NOT NULL CHECK (next_offset >= 0),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (topic, partition)
);

GRANT SELECT, INSERT, UPDATE ON instrument_projection, market_snapshot, projection_checkpoint TO market_data_app;
GRANT SELECT, INSERT ON processed_event TO market_data_app;
