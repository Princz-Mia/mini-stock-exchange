package com.mia_princz.mse.execution.trading.application;

import java.util.UUID;

/**
 * The outcome of starting a simulation. Started, it is the new simulation's first command: {@code simulationId} is
 * the new simulation, {@code state} is SETUP and {@code commandSequence} is 1. Refused, it is a command of the
 * simulation that was current, committed and stored there like any refusal: {@code simulationId} is null,
 * {@code state} is that simulation's state and {@code commandSequence} its sequence.
 * <p>
 * Either way it is the key's answer for good. A start looks its key up in every simulation, not only the current one,
 * so a key sent again after later starts still gets this answer and never starts anything else.
 */
public record SimulationStart(Status status, UUID simulationId, String name, String state, long commandSequence) {

	public enum Status {

		STARTED,
		/** A simulation that has not closed exists: it is set up, opened or closed first. */
		NOT_CLOSED,
		/** Another simulation has this name, whatever its case. */
		NAME_TAKEN

	}

}
