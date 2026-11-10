-- A trader's finished orders, the most recently ended first: what the terminal shows as recent orders. An order's
-- updated_at is its end once it is filled, canceled or rejected, and never changes after. Partial indexes hold only
-- finished orders, so the newest ones are the head of one range, overall or for one instrument.
CREATE INDEX orders_finished_by_account ON orders (account_id, updated_at DESC, id DESC)
    WHERE state IN ('FILLED', 'CANCELED', 'REJECTED');
CREATE INDEX orders_finished_by_account_instrument ON orders (account_id, instrument_id, updated_at DESC, id DESC)
    WHERE state IN ('FILLED', 'CANCELED', 'REJECTED');
