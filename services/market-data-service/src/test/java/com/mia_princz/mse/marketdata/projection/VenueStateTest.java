package com.mia_princz.mse.marketdata.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.mia_princz.mse.contracts.events.VenueState;

/** Every venue state the event can carry, as stored and served. */
class VenueStateTest {

	@Test
	void eachStateIsStoredAsTheApiNamesIt() {
		Map<VenueState, String> stored = new EnumMap<>(VenueState.class);
		for (VenueState state : VenueState.values()) {
			stored.put(state, SnapshotProjection.venueState(state));
		}

		assertThat(stored).containsExactlyInAnyOrderEntriesOf(Map.of(VenueState.SETUP, "SETUP", VenueState.OPEN, "OPEN",
				VenueState.HALTED, "HALTED", VenueState.UNKNOWN, "UNKNOWN", VenueState.CLOSED, "UNKNOWN"));
	}

}
