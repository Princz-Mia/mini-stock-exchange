package com.mia_princz.mse.execution.trading.application;

import java.util.UUID;

/**
 * The outcome of starting or stopping a bot run. Refusals are committed and stored like any result, so a retry with
 * the same key answers the same way; {@code NO_MARKET} is the one answer given before any market exists, so nothing
 * is stored for it.
 *
 * @param shortfall for {@code NOT_ENOUGH_BOTS}, the first strategy, in assignment order, with fewer bots tagged than
 * the run asked for; null otherwise, and in answers stored before runs assigned bots by tag
 */
public record BotRunResult(Status status, UUID runId, int bots, long commandSequence, Shortfall shortfall) {

	public BotRunResult(Status status, UUID runId, int bots, long commandSequence) {
		this(status, runId, bots, commandSequence, null);
	}

	/** A strategy the run asked for more bots of than are tagged with it. */
	public record Shortfall(String strategy, int requested, int available) {
	}

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
