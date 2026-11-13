package com.mia_princz.mse.execution.trading.application;

import java.util.UUID;

/**
 * The outcome of editing an instrument. Refusals are committed and stored like any result, so a retry with the same
 * key answers the same way. The symbol is null when the market has no such instrument.
 */
public record InstrumentEdit(Status status, UUID instrumentId, String symbol, long referencePriceCents,
		long commandSequence) {

	public enum Status {

		UPDATED, NOT_IN_SETUP, UNKNOWN_INSTRUMENT

	}

}
