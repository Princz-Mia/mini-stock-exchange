package com.mia_princz.mse.execution.trading.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Told when the exchange is to close the current simulation by itself, and when it no longer is: once a simulation
 * opens with a duration, once startup has loaded one that has a close time still to come, and with none once it has
 * closed or another simulation has started. Whoever is told calls {@link TradingEngine#closeOnSchedule} from that time
 * on. Called on the writer thread or the starting one, so it must not block.
 */
@FunctionalInterface
public interface CloseSchedule {

	CloseSchedule NONE = (simulationId, closesAt) -> {
	};

	/** {@code closesAt} is null when nothing is to be closed. Each call replaces the one before. */
	void arm(UUID simulationId, Instant closesAt);

}
