package com.mia_princz.mse.execution.trading.application;

import java.util.UUID;

/**
 * The outcome of starting or stopping a bot run. Refusals are committed and stored like any result, so a retry with
 * the same key answers the same way; {@code NO_MARKET} is the one answer given before any market exists, so nothing
 * is stored for it.
 */
public record BotRunResult(Status status, UUID runId, int bots, long commandSequence) {

	public enum Status {

		STARTED, STOPPED, NO_MARKET, NOT_OPEN, RUN_ACTIVE, NOT_ENOUGH_BOTS, UNKNOWN_INSTRUMENT, NOT_FOUND, NOT_RUNNING

	}

	/** Committed refusals answer 409, except the ones about the request's own contents. */
	int httpStatus() {
		return switch (status) {
			case STARTED -> 201;
			case STOPPED -> 200;
			case UNKNOWN_INSTRUMENT, NOT_ENOUGH_BOTS -> 422;
			case NOT_FOUND -> 404;
			case NO_MARKET, NOT_OPEN, RUN_ACTIVE, NOT_RUNNING -> 409;
		};
	}

}
