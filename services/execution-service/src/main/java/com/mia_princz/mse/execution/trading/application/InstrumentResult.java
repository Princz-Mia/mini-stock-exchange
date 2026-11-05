package com.mia_princz.mse.execution.trading.application;

import java.util.UUID;

/**
 * The outcome of adding an instrument. Refusals are committed and stored like any result, so a retry with the same
 * key answers the same way. A created instrument's ID, symbol and price are those of the first request.
 */
public record InstrumentResult(Status status, UUID instrumentId, String symbol, long referencePriceCents,
		long commandSequence) {

	public enum Status {

		CREATED, NOT_IN_SETUP, SYMBOL_TAKEN

	}

}
