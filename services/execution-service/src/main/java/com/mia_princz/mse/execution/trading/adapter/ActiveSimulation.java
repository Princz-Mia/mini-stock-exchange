package com.mia_princz.mse.execution.trading.adapter;

/**
 * The active simulation, in SQL: the one whose state is not CLOSED. Closed simulations stay in the database beside it
 * with their own accounts, instruments, orders, runs and stored answers, and a unique index allows at most one that is
 * not closed, so none of these ever finds two.
 * <p>
 * Every read and every command means the active simulation. While every simulation is closed there is none: a read
 * scoped with {@link #ID} then finds nothing, as it does before any market is set up; a session or a bot token has no
 * account to act for; and the writer starts with no market, as it does on an empty database. Only the market's status
 * falls back to the newest simulation ({@link #OR_ELSE_NEWEST}), so that a closed market reads as closed, not as one
 * never set up.
 * <p>
 * The writer holds the same ID in memory ({@code TradingEngine.simulationId()}), because only it sets a market up. A
 * check made on every bot request compares that one, so that it never reads the simulation's row, which the writer
 * rewrites with every run.
 */
public final class ActiveSimulation {

	/** True of the active simulation's row. */
	public static final String CONDITION = "state <> 'CLOSED'";

	/** Its ID as a scalar subquery; null when there is none, so a comparison with it matches no row. */
	public static final String ID = "(SELECT id FROM simulation WHERE " + CONDITION + ")";

	/** Ends a query on {@code simulation} that returns the active one or, if every one is closed, the newest. */
	public static final String OR_ELSE_NEWEST = "ORDER BY (state = 'CLOSED'), created_at DESC, id DESC LIMIT 1";

	private ActiveSimulation() {
	}

}
