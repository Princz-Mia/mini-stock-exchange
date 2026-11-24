-- Where a run's worker is with it, as its heartbeat says: LOADING while it reads the bots' token files and builds the
-- bots, so many of so many, then TRADING. Null from a worker that says nothing about it.
ALTER TABLE bot_run ADD COLUMN phase TEXT CHECK (phase IN ('LOADING', 'TRADING'));
ALTER TABLE bot_run ADD COLUMN bots_loaded INT CHECK (bots_loaded >= 0);
ALTER TABLE bot_run ADD COLUMN bots_total INT CHECK (bots_total >= 0);
ALTER TABLE bot_run ADD CHECK ((phase IS NULL) = (bots_loaded IS NULL) AND (phase IS NULL) = (bots_total IS NULL));
ALTER TABLE bot_run ADD CHECK (bots_loaded <= bots_total);

-- The bots' steps that could place nothing new, by why: market data too old or not live, the market not open, or
-- anything else. Cumulative for the run, like its totals.
ALTER TABLE bot_run ADD COLUMN paused_stale_data BIGINT NOT NULL DEFAULT 0 CHECK (paused_stale_data >= 0);
ALTER TABLE bot_run ADD COLUMN paused_market_not_open BIGINT NOT NULL DEFAULT 0 CHECK (paused_market_not_open >= 0);
ALTER TABLE bot_run ADD COLUMN paused_other BIGINT NOT NULL DEFAULT 0 CHECK (paused_other >= 0);

GRANT UPDATE (phase, bots_loaded, bots_total, paused_stale_data, paused_market_not_open, paused_other) ON bot_run
    TO execution_app;
