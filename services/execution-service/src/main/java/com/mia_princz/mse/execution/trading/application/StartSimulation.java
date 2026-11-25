package com.mia_princz.mse.execution.trading.application;

import java.util.Objects;
import java.util.UUID;

import com.mia_princz.mse.execution.trading.domain.SimulationName;

/**
 * An operator starts a new simulation, once the one before it has closed or while none exists. The ID is chosen
 * before the command runs.
 */
public record StartSimulation(UUID simulationId, SimulationName name, UUID actorUserId) {

	public StartSimulation {
		Objects.requireNonNull(simulationId, "simulationId");
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(actorUserId, "actorUserId");
	}

}
