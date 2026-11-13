-- An instrument deleted during setup leaves the projections: its last snapshot says it was removed, and the runtime
-- role deletes its market and then its catalogue entry. Nothing else refers to an instrument still in setup, as it
-- has no trades, candles or session figures yet, so no other table may be deleted from.
GRANT DELETE ON instrument_projection, market_snapshot TO market_data_app;
