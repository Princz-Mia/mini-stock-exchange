-- The operator's account list pages through the current simulation's accounts in creation order. Without an index in
-- that order, each page scanned every simulation's accounts and sorted them, and during a bot job it is read every
-- second while thousands of accounts are added.
CREATE INDEX account_by_simulation ON account (simulation_id, created_at, id);
