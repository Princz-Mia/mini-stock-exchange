-- The bot population has no limit, and the market is built to run 10,000 bots. Each account may have 20 orders
-- resting, so the market as a whole may have 200,000: the market-wide limit no longer binds before the per-account one
-- at that size. A market still at the old default of 50,000 is raised too; nothing could set another value, and a
-- higher limit refuses nothing that was accepted.
ALTER TABLE simulation ALTER COLUMN max_open_orders_total SET DEFAULT 200000;

UPDATE simulation SET max_open_orders_total = 200000 WHERE max_open_orders_total = 50000;
