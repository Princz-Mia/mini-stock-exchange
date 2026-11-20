-- Each instrument's book minute by minute, so what the market looked like over a window of command sequences can be
-- measured afterwards: the spread, and how much the last price moved. Fed by every new market snapshot and keyed by
-- the minute of its writer-clock time. Per minute: the first and last command sequence seen, and the state at the
-- last one: best bid and best ask (null while that side is empty) and the last trade's price (null before the first).
-- Every change is order-free: the lowest first, the highest last, and the state of the highest sequence, so
-- redeliveries and late arrivals change nothing and the rows end the same in any order. Minutes without a snapshot
-- have no row; readers carry the previous minute forward. Nothing deletes rows: one an instrument a minute at most.
--
-- Unlike the other projections, these rows are not all rebuilt from Kafka. mse.market.v1 is compacted, so a rebuild
-- from the topic sees only the snapshots compaction has kept, at least each instrument's newest, and the history
-- before them is gone. The same holds for snapshots compacted while the feed was paused or the service down. A restore
-- republishes every snapshot from Execution's outbox, so it rebuilds all of it.
--
-- btree_gist lets one GiST index hold the instrument and each minute's sequence range, so a window finds exactly the
-- minutes whose ranges overlap it, however long the history. It is a trusted extension: the database owner installs
-- it.
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE spread_minute (
    instrument_id    UUID NOT NULL,
    minute_start     TIMESTAMPTZ NOT NULL CHECK (minute_start = date_trunc('minute', minute_start)),
    first_sequence   BIGINT NOT NULL CHECK (first_sequence > 0),
    last_sequence    BIGINT NOT NULL,
    best_bid_cents   BIGINT CHECK (best_bid_cents > 0),
    best_ask_cents   BIGINT CHECK (best_ask_cents > 0),
    last_price_cents BIGINT CHECK (last_price_cents > 0),
    PRIMARY KEY (instrument_id, minute_start),
    CHECK (last_sequence >= first_sequence),
    -- A book never stays crossed: the higher bid would have traded.
    CHECK (best_bid_cents < best_ask_cents)
);

-- A window's minutes: those whose sequence range overlaps it.
CREATE INDEX spread_minute_window ON spread_minute
    USING gist (instrument_id, int8range(first_sequence, last_sequence, '[]'));

GRANT SELECT, INSERT, UPDATE ON spread_minute TO market_data_app;
