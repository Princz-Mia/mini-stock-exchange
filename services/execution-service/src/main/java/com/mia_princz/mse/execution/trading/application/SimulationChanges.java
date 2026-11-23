package com.mia_princz.mse.execution.trading.application;

import java.util.UUID;

/**
 * Told that the writer now writes for another simulation, once that has committed and the writer has dropped the
 * books, accounts and counts of the one before: the moment to forget whatever is kept per account, instrument or
 * run, since none of the earlier simulation's ever comes back. Called on the writer thread, so it must not block.
 */
@FunctionalInterface
public interface SimulationChanges {

	SimulationChanges NONE = (previousSimulationId, simulationId) -> {
	};

	/** {@code previousSimulationId} is null when no simulation existed before this one. */
	void started(UUID previousSimulationId, UUID simulationId);

}
