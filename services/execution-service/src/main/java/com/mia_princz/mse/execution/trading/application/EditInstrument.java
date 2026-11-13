package com.mia_princz.mse.execution.trading.application;

import java.util.Objects;
import java.util.UUID;

import com.mia_princz.mse.execution.trading.domain.Price;

/** An operator corrects an instrument's reference price while the market is in SETUP. */
public record EditInstrument(UUID instrumentId, Price referencePrice, UUID actorUserId) {

	public EditInstrument {
		Objects.requireNonNull(instrumentId, "instrumentId");
		Objects.requireNonNull(referencePrice, "referencePrice");
		Objects.requireNonNull(actorUserId, "actorUserId");
	}

}
