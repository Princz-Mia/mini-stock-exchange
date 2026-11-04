-- A trader's own orders, newest first. Rejected orders have no accepted sequence, so the history pages by
-- creation time with the id as a tie-breaker.
CREATE INDEX orders_recent_by_account ON orders (account_id, created_at DESC, id DESC);
