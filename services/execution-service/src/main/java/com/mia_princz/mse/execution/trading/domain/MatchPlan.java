package com.mia_princz.mse.execution.trading.domain;

import java.util.List;

/**
 * Everything one incoming order would do to the book, computed without changing it. The plan is applied only after
 * the database commit; {@code bookRevision} makes sure it is applied to exactly the book it was computed from.
 */
public record MatchPlan(OrderRequest taker, long bookRevision, List<Fill> fills, Outcome outcome) {

	public enum Outcome {

		/** Fully executed. */
		FILLED,

		/** A limit order whose remainder now rests in the book. */
		RESTS,

		/** A protected market order whose remainder was cancelled, possibly with no fills at all. */
		IOC_REMAINDER_CANCELED,

		/** The next eligible maker belonged to the same account; the remainder was cancelled after earlier fills. */
		SELF_TRADE_PREVENTED,

		/** Executing would need more fills than one command may make. Nothing happens. */
		EXECUTION_LIMIT_EXCEEDED

	}

	public MatchPlan {
		fills = List.copyOf(fills);
	}

	public long filledQuantity() {
		long filled = 0;
		for (Fill fill : fills) {
			filled = Math.addExact(filled, fill.quantity());
		}
		return filled;
	}

	public long remainingQuantity() {
		return outcome == Outcome.EXECUTION_LIMIT_EXCEEDED ? taker.quantity().shares()
				: Math.subtractExact(taker.quantity().shares(), filledQuantity());
	}

	public boolean rests() {
		return outcome == Outcome.RESTS;
	}

}
