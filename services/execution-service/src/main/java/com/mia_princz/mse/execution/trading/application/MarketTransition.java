package com.mia_princz.mse.execution.trading.application;

import java.time.Instant;

/**
 * The outcome of a market control command. {@code state} is the market's state once the command committed: the new
 * state when applied, the unchanged one when the transition was not allowed. A refusal is committed and stored like
 * any other result, so a retry with the same key gets the same answer even after the market has moved on.
 * {@code closesAt} is when the exchange closes the market itself, set by an opening with a duration and null for every
 * other answer; answers stored before there were durations read it as null. {@code botJob} is the job that was still
 * creating bots when an opening was refused for it, and null for every other answer, including those stored before.
 */
public record MarketTransition(Status status, String state, long commandSequence, Instant closesAt, BotJob botJob) {

	public enum Status {

		APPLIED, NOT_ALLOWED,
		/** No market exists yet. Nothing was committed, so the key can be retried. */
		NOT_SET_UP,
		/** An opening while a bot job was still creating bots; the market stays in SETUP. */
		BOT_JOB_RUNNING

	}

	public MarketTransition(Status status, String state, long commandSequence, Instant closesAt) {
		this(status, state, commandSequence, closesAt, null);
	}

	public MarketTransition(Status status, String state, long commandSequence) {
		this(status, state, commandSequence, null, null);
	}

	static MarketTransition notSetUp() {
		return new MarketTransition(Status.NOT_SET_UP, null, 0);
	}

}
