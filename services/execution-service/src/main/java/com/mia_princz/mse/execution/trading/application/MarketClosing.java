package com.mia_princz.mse.execution.trading.application;

/**
 * The outcome of closing the market. {@code state} is the market's state once the command committed: CLOSED when it
 * closed, the unchanged one when closing was not allowed from it. {@code canceledOrders} is how many orders rested
 * when the close ran; it cancels them all, the first of them in its own transaction and the rest in the ones that
 * follow it. {@code endedRuns} is how many bot runs it ended. A refusal is committed and stored like any other result,
 * so a retry with the same key gets the same answer.
 */
public record MarketClosing(Status status, String state, long commandSequence, int canceledOrders, int endedRuns) {

	public enum Status {

		CLOSED, NOT_ALLOWED,
		/** No market exists yet. Nothing was committed, so the key can be retried. */
		NOT_SET_UP

	}

	static MarketClosing notSetUp() {
		return new MarketClosing(Status.NOT_SET_UP, null, 0, 0, 0);
	}

}
