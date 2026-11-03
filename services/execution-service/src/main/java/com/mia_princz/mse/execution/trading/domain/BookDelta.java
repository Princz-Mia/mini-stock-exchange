package com.mia_princz.mse.execution.trading.domain;

import java.util.Collections;
import java.util.Comparator;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * What a command would change in the aggregate depth of one book, per side and price: a quantity change and an
 * order-count change. It describes a change without making it, so public depth after a command can be computed
 * inside the command's transaction while the book itself changes only after the commit.
 */
public final class BookDelta {

	/** Changes at one price; both values are added to the level, and are usually negative for removals. */
	record Change(long quantity, int orders) {
	}

	private final NavigableMap<Long, Change> bids = new TreeMap<>(Comparator.reverseOrder());

	private final NavigableMap<Long, Change> asks = new TreeMap<>();

	private BookDelta() {
	}

	/** The fills take from the makers' side; a resting remainder joins the taker's side at its limit. */
	public static BookDelta of(MatchPlan plan) {
		BookDelta delta = new BookDelta();
		Side makers = plan.taker().side().opposite();
		for (Fill fill : plan.fills()) {
			delta.add(makers, fill.price().cents(), -fill.quantity(), fill.makerRemainingAfter() == 0 ? -1 : 0);
		}
		if (plan.rests()) {
			delta.add(plan.taker().side(), plan.taker().bound().cents(), plan.remainingQuantity(), 1);
		}
		return delta;
	}

	/** A cancelled resting order leaves its level. */
	public static BookDelta cancel(BookOrder order) {
		BookDelta delta = new BookDelta();
		delta.add(order.side(), order.price().cents(), -order.remainingQuantity(), -1);
		return delta;
	}

	public boolean isEmpty() {
		return bids.isEmpty() && asks.isEmpty();
	}

	/** Sorted best first, like the book's own side. */
	NavigableMap<Long, Change> changes(Side side) {
		return Collections.unmodifiableNavigableMap(side == Side.BUY ? bids : asks);
	}

	private void add(Side side, long priceCents, long quantity, int orders) {
		(side == Side.BUY ? bids : asks).merge(priceCents, new Change(quantity, orders),
				(a, b) -> new Change(Math.addExact(a.quantity(), b.quantity()), a.orders() + b.orders()));
	}

}
