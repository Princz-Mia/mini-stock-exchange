-- A trader's history filtered by instrument, or orders by state, in the same keyset order as the unfiltered
-- history, so a filtered page is one index range scan read in either direction. Filtering by side needs no index of
-- its own: executions take one side's index, and orders are read in the account's index with the side checked.
CREATE INDEX orders_recent_by_account_instrument ON orders (account_id, instrument_id, created_at DESC, id DESC);
CREATE INDEX orders_recent_by_account_state ON orders (account_id, state, created_at DESC, id DESC);
CREATE INDEX trades_by_buyer_instrument ON trades (buyer_account_id, instrument_id, command_sequence DESC, trade_sequence DESC);
CREATE INDEX trades_by_seller_instrument ON trades (seller_account_id, instrument_id, command_sequence DESC, trade_sequence DESC);
