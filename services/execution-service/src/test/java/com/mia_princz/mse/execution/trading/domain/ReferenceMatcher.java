package com.mia_princz.mse.execution.trading.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The oracle for {@link OrderBook}: the matching rules written as plainly as possible, sorting the whole book for every
 * order. Too slow for production on purpose; it must stay simple enough to check by reading.
 */
final class ReferenceMatcher {

	private final int maxFillsPerCommand;

	private final List<BookOrder> resting = new ArrayList<>();

	ReferenceMatcher(int maxFillsPerCommand) {
		this.maxFillsPerCommand = maxFillsPerCommand;
	}

	/** Returns the fills and outcome, and updates this book. */
	MatchPlan submit(OrderRequest taker) {
		List<BookOrder> candidates = orders(taker.side().opposite());
		List<Fill> fills = new ArrayList<>();
		long remaining = taker.quantity().shares();
		boolean selfTrade = false;
		for (BookOrder maker : candidates) {
			if (remaining == 0 || !taker.crosses(maker.price().cents())) {
				break;
			}
			if (maker.accountId().equals(taker.accountId())) {
				selfTrade = true;
				break;
			}
			if (fills.size() == maxFillsPerCommand) {
				return new MatchPlan(taker, 0, List.of(), MatchPlan.Outcome.EXECUTION_LIMIT_EXCEEDED);
			}
			long quantity = Math.min(remaining, maker.remainingQuantity());
			fills.add(new Fill(maker.orderId(), maker.accountId(), maker.price(), quantity, maker.remainingQuantity() - quantity));
			remaining -= quantity;
		}

		MatchPlan.Outcome outcome;
		if (remaining == 0) {
			outcome = MatchPlan.Outcome.FILLED;
		}
		else if (selfTrade) {
			outcome = MatchPlan.Outcome.SELF_TRADE_PREVENTED;
		}
		else if (taker.type() == OrderType.LIMIT) {
			outcome = MatchPlan.Outcome.RESTS;
		}
		else {
			outcome = MatchPlan.Outcome.IOC_REMAINDER_CANCELED;
		}

		for (Fill fill : fills) {
			BookOrder maker = find(fill.makerOrderId()).orElseThrow();
			resting.remove(maker);
			if (fill.makerRemainingAfter() > 0) {
				resting.add(new BookOrder(maker.orderId(), maker.accountId(), maker.side(), maker.price(),
						fill.makerRemainingAfter(), maker.acceptedSequence()));
			}
		}
		if (outcome == MatchPlan.Outcome.RESTS) {
			resting.add(new BookOrder(taker.orderId(), taker.accountId(), taker.side(), taker.bound(), remaining,
					taker.acceptedSequence()));
		}
		return new MatchPlan(taker, 0, fills, outcome);
	}

	Optional<BookOrder> cancel(UUID orderId) {
		Optional<BookOrder> order = find(orderId);
		order.ifPresent(resting::remove);
		return order;
	}

	/** One side in priority order: best price first, then lowest accepted sequence. */
	List<BookOrder> orders(Side side) {
		Comparator<BookOrder> byPrice = Comparator.comparingLong((BookOrder order) -> order.price().cents());
		if (side == Side.BUY) {
			byPrice = byPrice.reversed();
		}
		return resting.stream()
			.filter((order) -> order.side() == side)
			.sorted(byPrice.thenComparingLong(BookOrder::acceptedSequence))
			.toList();
	}

	List<DepthLevel> depth(Side side) {
		List<DepthLevel> depth = new ArrayList<>();
		for (BookOrder order : orders(side)) {
			DepthLevel last = depth.isEmpty() ? null : depth.get(depth.size() - 1);
			if (last != null && last.price().equals(order.price())) {
				depth.set(depth.size() - 1,
						new DepthLevel(last.price(), last.quantity() + order.remainingQuantity(), last.orderCount() + 1));
			}
			else {
				depth.add(new DepthLevel(order.price(), order.remainingQuantity(), 1));
			}
		}
		return depth;
	}

	List<UUID> restingIds() {
		return resting.stream().map(BookOrder::orderId).toList();
	}

	private Optional<BookOrder> find(UUID orderId) {
		return resting.stream().filter((order) -> order.orderId().equals(orderId)).findFirst();
	}

}
