-- One-minute candles from the analytics job, one row per computation epoch, instrument and minute. Each update is
-- the complete aggregate, so the newest version replaces the row: within an epoch, only a higher source trade
-- sequence wins. A replay into a new epoch builds alongside and never overwrites the old one.
CREATE TABLE candle_1m (
    computation_epoch         BIGINT NOT NULL CHECK (computation_epoch > 0),
    simulation_id             UUID NOT NULL,
    instrument_id             UUID NOT NULL,
    bucket_start              TIMESTAMPTZ NOT NULL CHECK (bucket_start = date_trunc('minute', bucket_start)),
    symbol                    TEXT NOT NULL CHECK (symbol ~ '^[A-Z]{1,8}$'),
    open_cents                BIGINT NOT NULL CHECK (open_cents > 0),
    high_cents                BIGINT NOT NULL,
    low_cents                 BIGINT NOT NULL CHECK (low_cents > 0),
    close_cents               BIGINT NOT NULL CHECK (close_cents > 0),
    volume                    BIGINT NOT NULL CHECK (volume > 0),
    trade_count               BIGINT NOT NULL CHECK (trade_count > 0 AND trade_count <= volume),
    -- Exact: VWAP is notional / volume, rounded only for display.
    notional_cents            NUMERIC(38, 0) NOT NULL CHECK (notional_cents > 0),
    source_max_trade_sequence BIGINT NOT NULL CHECK (source_max_trade_sequence > 0),
    generated_at              TIMESTAMPTZ NOT NULL,
    projected_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (computation_epoch, simulation_id, instrument_id, bucket_start),
    CHECK (low_cents <= open_cents AND open_cents <= high_cents),
    CHECK (low_cents <= close_cents AND close_cents <= high_cents),
    CHECK (notional_cents BETWEEN low_cents::NUMERIC * volume AND high_cents::NUMERIC * volume)
);

-- Freshness: how far each instrument's candles have got, per epoch.
CREATE INDEX candle_1m_progress ON candle_1m (computation_epoch, instrument_id, source_max_trade_sequence DESC);

GRANT SELECT, INSERT ON candle_1m TO market_data_app;
GRANT UPDATE (symbol, open_cents, high_cents, low_cents, close_cents, volume, trade_count, notional_cents,
              source_max_trade_sequence, generated_at, projected_at) ON candle_1m TO market_data_app;
