package com.mia_princz.mse.execution.trading.application;

import java.util.Objects;
import java.util.UUID;

/** An operator removes an instrument nothing uses yet, while the market is in SETUP. */
public record DeleteInstrument(UUID instrumentId, UUID actorUserId) {

	public DeleteInstrument {
		Objects.requireNonNull(instrumentId, "instrumentId");
		Objects.requireNonNull(actorUserId, "actorUserId");
	}

}
