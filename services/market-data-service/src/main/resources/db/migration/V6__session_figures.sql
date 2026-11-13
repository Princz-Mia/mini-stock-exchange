-- Session figures per instrument, from the public tape: kept with each new trade, in the same transaction, so they
-- work without the analytics job. Every change is order-free (sums, the highest and lowest price, the last trade by
-- sequence), so trades arriving in any order, or projected again after a rebuild, give the same figures. Like every
-- projection, these rows are derived: a rebuild empties them with the tape.
CREATE TABLE instrument_session (
    instrument_id       UUID PRIMARY KEY,
    simulation_id       UUID NOT NULL,
    trade_count         BIGINT NOT NULL CHECK (trade_count > 0),
    volume              BIGINT NOT NULL CHECK (volume > 0),
    notional_cents      NUMERIC(38, 0) NOT NULL CHECK (notional_cents > 0),
    high_cents          BIGINT NOT NULL CHECK (high_cents > 0),
    low_cents           BIGINT NOT NULL CHECK (low_cents > 0 AND low_cents <= high_cents),
    last_price_cents    BIGINT NOT NULL CHECK (last_price_cents > 0),
    last_trade_sequence BIGINT NOT NULL CHECK (last_trade_sequence > 0),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The last price in each minute that had a trade: the trend line beside each instrument.
CREATE TABLE session_minute (
    instrument_id       UUID NOT NULL,
    minute_start        TIMESTAMPTZ NOT NULL CHECK (minute_start = date_trunc('minute', minute_start)),
    last_price_cents    BIGINT NOT NULL CHECK (last_price_cents > 0),
    last_trade_sequence BIGINT NOT NULL CHECK (last_trade_sequence > 0),
    PRIMARY KEY (instrument_id, minute_start)
);

GRANT SELECT, INSERT, UPDATE ON instrument_session, session_minute TO market_data_app;
