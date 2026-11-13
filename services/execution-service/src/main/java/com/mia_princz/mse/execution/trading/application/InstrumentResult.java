package com.mia_princz.mse.execution.trading.application;

import java.util.UUID;

/**
 * The outcome of adding an instrument. Refusals are committed and stored like any result, so a retry with the same
 * key answers the same way. A created instrument's ID, symbol, display name and price are those of the first request.
 * The display name is null for none, and in answers stored before instruments had one.
 */
public record InstrumentResult(Status status, UUID instrumentId, String symbol, String displayName,
		long referencePriceCents, long commandSequence) {

	public enum Status {

		CREATED, NOT_IN_SETUP, SYMBOL_TAKEN

	}

}
