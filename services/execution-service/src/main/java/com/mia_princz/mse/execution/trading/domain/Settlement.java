package com.mia_princz.mse.execution.trading.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mia_princz.mse.execution.trading.domain.Posting.Bucket;
import com.mia_princz.mse.execution.trading.domain.SettlementPlan.Batch;
import com.mia_princz.mse.execution.trading.domain.SettlementPlan.Cause;

/**
 * Turns matching results into ledger postings. A buy reserves its bound times its quantity; a sell reserves its
 * shares. For an execution of {@code q} at maker price {@code p} against buyer bound {@code b}:
 *
 * <pre>
 * buyer cash RESERVED    -b*q        seller shares RESERVED  -q
 * buyer cash AVAILABLE   +(b-p)*q    buyer shares AVAILABLE  +q
 * seller cash AVAILABLE  +p*q
 * </pre>
 *
 * When an order stops working, its remaining hold returns to AVAILABLE. Zero postings are omitted.
 */
public final class Settlement {

	private Settlement() {
	}

	public static SettlementPlan of(UUID instrumentId, MatchPlan match) {
		if (match.outcome() == MatchPlan.Outcome.EXECUTION_LIMIT_EXCEEDED) {
			return SettlementPlan.none();
		}
		OrderRequest taker = match.taker();
		List<Batch> batches = new ArrayList<>();
		batches.add(new Batch(Cause.RESERVE, -1, hold(taker.accountId(), instrumentId, taker.side(), taker.bound(),
				taker.quantity().shares(), true)));
		for (int i = 0; i < match.fills().size(); i++) {
			batches.add(new Batch(Cause.TRADE, i, trade(instrumentId, taker, match.fills().get(i))));
		}
		if (!match.rests() && match.remainingQuantity() > 0) {
			batches.add(new Batch(Cause.RELEASE, -1, hold(taker.accountId(), instrumentId, taker.side(), taker.bound(),
					match.remainingQuantity(), false)));
		}
		return new SettlementPlan(batches);
	}

	/** Releases the whole remaining hold of a resting order that is cancelled. */
	public static SettlementPlan cancel(UUID instrumentId, BookOrder order) {
		return new SettlementPlan(List.of(new Batch(Cause.RELEASE, -1, hold(order.accountId(), instrumentId,
				order.side(), order.price(), order.remainingQuantity(), false))));
	}

	/** The amount a working order of this side holds: bound times quantity in cents, or the shares themselves. */
	public static long holdAmount(Side side, Price bound, long quantity) {
		return side == Side.BUY ? Math.multiplyExact(bound.cents(), quantity) : quantity;
	}

	private static List<Posting> hold(UUID account, UUID instrumentId, Side side, Price bound, long quantity,
			boolean reserve) {
		UUID asset = side == Side.BUY ? null : instrumentId;
		long amount = holdAmount(side, bound, quantity);
		long sign = reserve ? 1 : -1;
		return List.of(new Posting(account, asset, Bucket.AVAILABLE, -sign * amount),
				new Posting(account, asset, Bucket.RESERVED, sign * amount));
	}

	private static List<Posting> trade(UUID instrumentId, OrderRequest taker, Fill fill) {
		boolean takerBuys = taker.side() == Side.BUY;
		UUID buyer = takerBuys ? taker.accountId() : fill.makerAccountId();
		UUID seller = takerBuys ? fill.makerAccountId() : taker.accountId();
		// A resting buy executes at its own limit, so only a buying taker can see price improvement.
		long buyerBound = takerBuys ? taker.bound().cents() : fill.price().cents();
		long price = fill.price().cents();
		long quantity = fill.quantity();
		long improvement = Math.multiplyExact(Math.subtractExact(buyerBound, price), quantity);

		List<Posting> postings = new ArrayList<>();
		postings.add(new Posting(buyer, null, Bucket.RESERVED, -Math.multiplyExact(buyerBound, quantity)));
		if (improvement != 0) {
			postings.add(new Posting(buyer, null, Bucket.AVAILABLE, improvement));
		}
		postings.add(new Posting(seller, null, Bucket.AVAILABLE, Math.multiplyExact(price, quantity)));
		postings.add(new Posting(seller, instrumentId, Bucket.RESERVED, -quantity));
		postings.add(new Posting(buyer, instrumentId, Bucket.AVAILABLE, quantity));
		return postings;
	}

}
