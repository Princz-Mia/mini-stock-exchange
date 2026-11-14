package com.mia_princz.mse.execution.trading.application;

import java.util.Objects;
import java.util.UUID;

import com.mia_princz.mse.execution.trading.domain.DisplayName;
import com.mia_princz.mse.execution.trading.domain.Price;

/**
 * An operator sets an instrument's editable fields while the market is in SETUP: all of them each time, so a null display
 * name removes the name.
 */
public record EditInstrument(UUID instrumentId, Price referencePrice, DisplayName displayName, UUID actorUserId) {

	public EditInstrument {
		Objects.requireNonNull(instrumentId, "instrumentId");
		Objects.requireNonNull(referencePrice, "referencePrice");
		Objects.requireNonNull(actorUserId, "actorUserId");
	}

}
