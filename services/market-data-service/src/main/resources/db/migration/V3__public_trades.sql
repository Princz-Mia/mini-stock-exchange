-- The public trade tape, one row per execution, deduplicated by trade id. No foreign key to
-- instrument_projection: topics share no order, so a trade can arrive before its instrument's first snapshot.
CREATE TABLE public_trade (
    trade_id         UUID PRIMARY KEY,
    simulation_id    UUID NOT NULL,
    instrument_id    UUID NOT NULL,
    symbol           TEXT NOT NULL CHECK (symbol ~ '^[A-Z]{1,8}$'),
    trade_sequence   BIGINT NOT NULL CHECK (trade_sequence > 0),
    command_sequence BIGINT NOT NULL CHECK (command_sequence > 0),
    price_cents      BIGINT NOT NULL CHECK (price_cents > 0),
    quantity         BIGINT NOT NULL CHECK (quantity > 0),
    executed_at      TIMESTAMPTZ NOT NULL,
    projected_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (instrument_id, trade_sequence)
);

-- Newest first per instrument, for keyset pages of the tape.
CREATE INDEX public_trade_tape ON public_trade (instrument_id, trade_sequence DESC);

GRANT SELECT, INSERT ON public_trade TO market_data_app;
