package com.mia_princz.mse.execution.trading.application;

import java.time.Instant;

/**
 * The outcome of a market control command. {@code state} is the market's state once the command committed: the new
 * state when applied, the unchanged one when the transition was not allowed. A refusal is committed and stored like
 * any other result, so a retry with the same key gets the same answer even after the market has moved on.
 * {@code closesAt} is when the exchange closes the market itself, set by an opening with a duration and null for every
 * other answer; answers stored before there were durations read it as null.
 */
public record MarketTransition(Status status, String state, long commandSequence, Instant closesAt) {

	public enum Status {

		APPLIED, NOT_ALLOWED,
		/** No market exists yet. Nothing was committed, so the key can be retried. */
		NOT_SET_UP

	}

	public MarketTransition(Status status, String state, long commandSequence) {
		this(status, state, commandSequence, null);
	}

	static MarketTransition notSetUp() {
		return new MarketTransition(Status.NOT_SET_UP, null, 0);
	}

}
