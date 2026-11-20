-- Each finished bot run's figures, computed once from its window (V30) and never changed, so a comparison and an
-- export read the same numbers. Amounts are in cents; notional and values can pass 64 bits, so they are NUMERIC.
CREATE TABLE bot_run_figures (
    run_id      UUID PRIMARY KEY REFERENCES bot_run (id),
    computed_at TIMESTAMPTZ NOT NULL
);

-- Per instrument the run traded: every trade in its window, by anyone (people included), and the marks at its edges:
-- the price of the newest trade at or before each edge, or the reference price before any trade.
CREATE TABLE bot_run_instrument_figures (
    run_id            UUID NOT NULL REFERENCES bot_run_figures (run_id),
    instrument_id     UUID NOT NULL,
    trades            BIGINT NOT NULL CHECK (trades >= 0),
    volume            BIGINT NOT NULL CHECK (volume >= 0),
    notional_cents    NUMERIC(38, 0) NOT NULL CHECK (notional_cents >= 0),
    start_mark_cents  BIGINT NOT NULL CHECK (start_mark_cents > 0),
    start_mark_source TEXT NOT NULL CHECK (start_mark_source IN ('LAST_TRADE', 'REFERENCE')),
    end_mark_cents    BIGINT NOT NULL CHECK (end_mark_cents > 0),
    end_mark_source   TEXT NOT NULL CHECK (end_mark_source IN ('LAST_TRADE', 'REFERENCE')),
    PRIMARY KEY (run_id, instrument_id)
);

-- Per strategy, over the bots the run assigned to it:
--   placements and cancels answered in the window (refusals included, counted again by reason below);
--   placed_quantity: what the orders accepted in the window in the run's instruments asked for; filled_quantity: what
--     of it filled in the window;
--   trades, volume and notional: the strategy's side of each trade in the window (a trade between two of its bots
--     counts twice);
--   estimated P&L: the cash the window's trades moved, plus the run's instruments held at the end at the end marks,
--     minus those held at the start at the start marks. Reserved amounts count once; holdings of other instruments,
--     which the run never traded, are left out.
CREATE TABLE bot_run_strategy_figures (
    run_id               UUID NOT NULL REFERENCES bot_run_figures (run_id),
    strategy             TEXT NOT NULL CHECK (strategy IN ('NOISE', 'MOMENTUM', 'MARKET_MAKER')),
    bots                 INT NOT NULL CHECK (bots > 0),
    placements           BIGINT NOT NULL CHECK (placements >= 0),
    cancels              BIGINT NOT NULL CHECK (cancels >= 0),
    placed_quantity      BIGINT NOT NULL CHECK (placed_quantity >= 0),
    filled_quantity      BIGINT NOT NULL CHECK (filled_quantity >= 0 AND filled_quantity <= placed_quantity),
    trades               BIGINT NOT NULL CHECK (trades >= 0),
    volume               BIGINT NOT NULL CHECK (volume >= 0),
    notional_cents       NUMERIC(38, 0) NOT NULL CHECK (notional_cents >= 0),
    cash_change_cents    NUMERIC(38, 0) NOT NULL,
    holdings_start_cents NUMERIC(38, 0) NOT NULL CHECK (holdings_start_cents >= 0),
    holdings_end_cents   NUMERIC(38, 0) NOT NULL CHECK (holdings_end_cents >= 0),
    pnl_cents            NUMERIC(38, 0) NOT NULL
        CHECK (pnl_cents = cash_change_cents + holdings_end_cents - holdings_start_cents),
    PRIMARY KEY (run_id, strategy)
);

-- Refused commands per strategy, by the code their answer carried: a placement's rejection (INSUFFICIENT_CASH and the
-- like), or a cancel's ORDER_NOT_CANCELABLE or ORDER_NOT_FOUND. Refusals before the writer (rate limits, a busy
-- exchange) leave no record.
CREATE TABLE bot_run_refusals (
    run_id   UUID NOT NULL,
    strategy TEXT NOT NULL,
    code     TEXT NOT NULL CHECK (length(code) BETWEEN 1 AND 40),
    commands BIGINT NOT NULL CHECK (commands > 0),
    PRIMARY KEY (run_id, strategy, code),
    FOREIGN KEY (run_id, strategy) REFERENCES bot_run_strategy_figures (run_id, strategy)
);

GRANT SELECT, INSERT ON bot_run_figures, bot_run_instrument_figures, bot_run_strategy_figures, bot_run_refusals
    TO execution_app;
