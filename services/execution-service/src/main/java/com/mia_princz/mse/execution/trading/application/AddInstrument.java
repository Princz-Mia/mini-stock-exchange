package com.mia_princz.mse.execution.trading.application;

import java.util.Objects;
import java.util.UUID;

import com.mia_princz.mse.execution.trading.domain.Price;

/** An operator adds an instrument while the market is in SETUP. The ID is chosen before the command runs. */
public record AddInstrument(UUID instrumentId, String symbol, Price referencePrice, UUID actorUserId) {

	public AddInstrument {
		Objects.requireNonNull(instrumentId, "instrumentId");
		Objects.requireNonNull(symbol, "symbol");
		Objects.requireNonNull(referencePrice, "referencePrice");
		Objects.requireNonNull(actorUserId, "actorUserId");
	}

}
