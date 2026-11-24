-- A simulation that ends says CLOSED in each instrument's last snapshot, and sends none after it. The state has to be
-- stored as it arrived: under any other name it would stay wrong, since no newer snapshot comes to replace the row.
-- No row holds a closed venue yet, so nothing stored changes.
ALTER TABLE market_snapshot DROP CONSTRAINT market_snapshot_venue_state_check;
ALTER TABLE market_snapshot ADD CONSTRAINT market_snapshot_venue_state_check
    CHECK (venue_state IN ('SETUP', 'OPEN', 'HALTED', 'UNKNOWN', 'CLOSED'));
