-- A bot run may ask for up to 2,000 trading commands a second, ten times the old 200, so the load test can offer
-- enough to find how far the exchange goes. NUMERIC(6, 2) already holds 2,000.00, so the column stays as it is.
ALTER TABLE bot_run DROP CONSTRAINT bot_run_commands_per_second_check;
ALTER TABLE bot_run ADD CONSTRAINT bot_run_commands_per_second_check
    CHECK (commands_per_second > 0 AND commands_per_second <= 2000);
