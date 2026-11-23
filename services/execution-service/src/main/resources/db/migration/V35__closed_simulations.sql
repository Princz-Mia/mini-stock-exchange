-- A simulation can end. CLOSED is its last state: it keeps its accounts, orders, trades and ledger, and stays in the
-- database beside the simulation that comes after it.
ALTER TABLE simulation DROP CONSTRAINT simulation_state_check;
ALTER TABLE simulation ADD CONSTRAINT simulation_state_check
    CHECK (state IN ('SETUP', 'OPEN', 'HALTED', 'CLOSED'));

-- When it closed. The rule on opened_at stays as it is and so also holds for CLOSED: only a simulation that was opened
-- can be closed. The two times are not compared, because one may come from the database's clock and the other from
-- the service's.
ALTER TABLE simulation ADD COLUMN closed_at TIMESTAMPTZ;
ALTER TABLE simulation ADD CONSTRAINT simulation_closed_at_matches_state
    CHECK ((state = 'CLOSED') = (closed_at IS NOT NULL));

-- Any number of closed simulations, and at most one that is not: the active one, which every read and command means.
-- Only the state is in the index's condition, so a command that moves the sequence still updates the row in place.
DROP INDEX simulation_single_row;
CREATE UNIQUE INDEX simulation_one_active ON simulation ((true)) WHERE state <> 'CLOSED';

GRANT UPDATE (closed_at) ON simulation TO execution_app;
