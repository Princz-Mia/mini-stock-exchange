package com.mia_princz.mse.execution.trading.adapter;

/**
 * The current simulation, in SQL: the one whose state is not CLOSED or, once every one is closed, the one created
 * last. Closed simulations stay in the database beside it with their own accounts, instruments, orders, runs and
 * stored answers, and a unique index allows at most one that is not closed, so none of these ever finds two.
 * <p>
 * Every read and every command means the current simulation. After a market has closed it is still the current one,
 * until another is set up: its traders read their final portfolios, orders and trades, the operator its accounts and
 * runs, and a command is answered by it, which is how an order learns that the market is closed. An older closed
 * simulation is out of reach of all of them.
 * <p>
 * Two things need more than being current, because they are work and not history: a bot token authenticates, and a
 * run is handed to a worker, only while their simulation is not closed ({@link #NOT_CLOSED_ID}).
 * <p>
 * The writer holds the same ID in memory ({@code TradingEngine.simulationId()}), because only it sets a market up or
 * closes one. A check made on every bot request compares the writer's, so that it never reads the simulation's row,
 * which the writer rewrites with every run.
 */
public final class CurrentSimulation {

	/** True of a simulation that has not ended: at most one row. */
	public static final String NOT_CLOSED = "state <> 'CLOSED'";

	/** Ends a query on {@code simulation} that returns the current one: the one not closed, or else the newest. */
	public static final String FIRST = "ORDER BY (state = 'CLOSED'), created_at DESC, id DESC LIMIT 1";

	/** Its ID as a scalar subquery; null before any market is set up, so a comparison with it matches no row. */
	public static final String ID = "(SELECT id FROM simulation " + FIRST + ")";

	/** The ID of the simulation that is not closed; null when there is none, so a comparison matches no row. */
	public static final String NOT_CLOSED_ID = "(SELECT id FROM simulation WHERE " + NOT_CLOSED + ")";

	private CurrentSimulation() {
	}

}
