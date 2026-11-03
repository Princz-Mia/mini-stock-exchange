-- Accepted orders satisfy requested = filled + working + canceled. A rejected order keeps its
-- requested terms for reporting but never accepted any quantity or hold.
CREATE TABLE orders (
    id                 UUID PRIMARY KEY,
    simulation_id      UUID NOT NULL,
    account_id         UUID NOT NULL,
    instrument_id      UUID NOT NULL,
    side               TEXT NOT NULL CHECK (side IN ('BUY', 'SELL')),
    type               TEXT NOT NULL CHECK (type IN ('LIMIT', 'MARKET_PROTECTED')),
    time_in_force      TEXT NOT NULL CHECK (time_in_force IN ('GTC', 'IOC')),
    bound_cents        BIGINT NOT NULL CHECK (bound_cents BETWEEN 1 AND 100000000),
    requested_quantity BIGINT NOT NULL CHECK (requested_quantity BETWEEN 1 AND 10000),
    filled_quantity    BIGINT NOT NULL DEFAULT 0 CHECK (filled_quantity >= 0),
    working_quantity   BIGINT NOT NULL DEFAULT 0 CHECK (working_quantity >= 0),
    canceled_quantity  BIGINT NOT NULL DEFAULT 0 CHECK (canceled_quantity >= 0),
    state              TEXT NOT NULL CHECK (state IN ('OPEN', 'PARTIALLY_FILLED', 'FILLED', 'CANCELED', 'REJECTED')),
    reason             TEXT,
    accepted_sequence  BIGINT CHECK (accepted_sequence > 0),
    version            BIGINT NOT NULL DEFAULT 1 CHECK (version >= 1),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (simulation_id, account_id) REFERENCES account (simulation_id, id),
    FOREIGN KEY (simulation_id, instrument_id) REFERENCES instrument (simulation_id, id),
    UNIQUE (simulation_id, accepted_sequence),
    CONSTRAINT orders_type_matches_time_in_force CHECK (
        (type = 'LIMIT' AND time_in_force = 'GTC') OR (type = 'MARKET_PROTECTED' AND time_in_force = 'IOC')),
    CONSTRAINT orders_market_never_rests CHECK (type = 'LIMIT' OR working_quantity = 0),
    CONSTRAINT orders_rejected_accepts_nothing CHECK (
        (state = 'REJECTED') = (accepted_sequence IS NULL)
        AND (state <> 'REJECTED'
             OR (filled_quantity = 0 AND working_quantity = 0 AND canceled_quantity = 0 AND reason IS NOT NULL))),
    CONSTRAINT orders_quantities_add_up CHECK (
        state = 'REJECTED' OR requested_quantity = filled_quantity + working_quantity + canceled_quantity),
    CONSTRAINT orders_state_matches_quantities CHECK (
        (state = 'OPEN' AND filled_quantity = 0 AND working_quantity > 0)
        OR (state = 'PARTIALLY_FILLED' AND filled_quantity > 0 AND working_quantity > 0)
        OR (state = 'FILLED' AND working_quantity = 0 AND canceled_quantity = 0)
        OR (state = 'CANCELED' AND working_quantity = 0 AND canceled_quantity > 0)
        OR state = 'REJECTED')
);

-- Rebuilding the book reads active orders by price and acceptance; traders list their own orders.
CREATE INDEX orders_active_book ON orders (simulation_id, instrument_id, side, bound_cents, accepted_sequence)
    WHERE state IN ('OPEN', 'PARTIALLY_FILLED');
CREATE INDEX orders_by_account ON orders (account_id, accepted_sequence DESC, id);

CREATE TABLE trades (
    id                UUID PRIMARY KEY,
    simulation_id     UUID NOT NULL,
    instrument_id     UUID NOT NULL,
    trade_sequence    BIGINT NOT NULL CHECK (trade_sequence > 0),
    maker_order_id    UUID NOT NULL REFERENCES orders (id),
    taker_order_id    UUID NOT NULL REFERENCES orders (id),
    buyer_account_id  UUID NOT NULL REFERENCES account (id),
    seller_account_id UUID NOT NULL REFERENCES account (id),
    price_cents       BIGINT NOT NULL CHECK (price_cents BETWEEN 1 AND 100000000),
    quantity          BIGINT NOT NULL CHECK (quantity BETWEEN 1 AND 10000),
    executed_at       TIMESTAMPTZ NOT NULL,
    command_sequence  BIGINT NOT NULL CHECK (command_sequence > 0),
    FOREIGN KEY (simulation_id, instrument_id) REFERENCES instrument (simulation_id, id),
    UNIQUE (simulation_id, instrument_id, trade_sequence),
    CHECK (maker_order_id <> taker_order_id),
    CHECK (buyer_account_id <> seller_account_id)
);

CREATE INDEX trades_recent ON trades (simulation_id, instrument_id, trade_sequence DESC);
CREATE INDEX trades_by_buyer ON trades (buyer_account_id, command_sequence DESC);
CREATE INDEX trades_by_seller ON trades (seller_account_id, command_sequence DESC);

CREATE TRIGGER trades_append_only BEFORE UPDATE OR DELETE ON trades
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_change();

-- A trade's journal batch now points at the trade, and only trade batches do.
ALTER TABLE journal_batch ADD CONSTRAINT journal_batch_trade FOREIGN KEY (trade_id) REFERENCES trades (id);
ALTER TABLE journal_batch ADD CONSTRAINT journal_batch_trade_cause CHECK ((cause = 'TRADE') = (trade_id IS NOT NULL));

GRANT SELECT, INSERT ON orders, trades TO execution_app;
GRANT UPDATE (filled_quantity, working_quantity, canceled_quantity, state, reason, version, updated_at)
    ON orders TO execution_app;
