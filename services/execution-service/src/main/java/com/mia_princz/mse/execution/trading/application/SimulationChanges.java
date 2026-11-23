package com.mia_princz.mse.execution.trading.application;

import java.util.List;
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

	/**
	 * All of {@code listeners} as one. Each is told on its own, so that one failing does not keep the rest from
	 * forgetting; the first failure is thrown once every one has been told, with any later ones attached.
	 */
	static SimulationChanges all(List<SimulationChanges> listeners) {
		List<SimulationChanges> each = List.copyOf(listeners);
		return (previousSimulationId, simulationId) -> {
			RuntimeException failed = null;
			for (SimulationChanges listener : each) {
				try {
					listener.started(previousSimulationId, simulationId);
				}
				catch (RuntimeException ex) {
					if (failed == null) {
						failed = ex;
					}
					else {
						failed.addSuppressed(ex);
					}
				}
			}
			if (failed != null) {
				throw failed;
			}
		};
	}

	/** {@code previousSimulationId} is null when no simulation existed before this one. */
	void started(UUID previousSimulationId, UUID simulationId);

}
