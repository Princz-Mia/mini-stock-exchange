package com.mia_princz.mse.execution.trading.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Several listeners told as one. Forgetting is each one's own business: a listener that fails must not leave the ones
 * after it holding the accounts of a simulation that is over.
 */
class AllSimulationChangesTest {

	private final UUID previous = UUID.randomUUID();

	private final UUID simulation = UUID.randomUUID();

	private final List<String> told = new ArrayList<>();

	@Test
	void everyListenerIsToldInOrderWithBothSimulations() {
		SimulationChanges all = SimulationChanges.all(List.of(heard("first"), heard("second")));

		all.started(previous, simulation);
		all.started(null, previous);

		assertThat(told).containsExactly("first " + previous + " " + simulation, "second " + previous + " " + simulation,
				"first null " + previous, "second null " + previous);
	}

	@Test
	void aListenerThatFailsDoesNotKeepTheOnesAfterItFromBeingTold() {
		IllegalStateException first = new IllegalStateException("the first cannot forget");
		IllegalArgumentException third = new IllegalArgumentException("nor can the third");
		SimulationChanges all = SimulationChanges.all(List.of(failing(first), heard("second"), failing(third),
				heard("fourth")));

		assertThatThrownBy(() -> all.started(previous, simulation)).as("the first failure, once all were told")
			.isSameAs(first)
			.satisfies((thrown) -> assertThat(thrown.getSuppressed()).containsExactly(third));

		assertThat(told).containsExactly("second " + previous + " " + simulation, "fourth " + previous + " " + simulation);
	}

	@Test
	void withNoListenerNothingHappens() {
		SimulationChanges.all(List.of()).started(previous, simulation);

		assertThat(told).isEmpty();
	}

	private SimulationChanges heard(String name) {
		return (before, now) -> told.add(name + " " + before + " " + now);
	}

	private static SimulationChanges failing(RuntimeException failure) {
		return (before, now) -> {
			throw failure;
		};
	}

}
