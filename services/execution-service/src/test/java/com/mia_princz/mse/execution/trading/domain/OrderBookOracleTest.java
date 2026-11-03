package com.mia_princz.mse.execution.trading.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Runs seeded random order streams through {@link OrderBook} and {@link ReferenceMatcher} side by side. After every
 * command both must agree on fills, outcome and resting orders, and every order's shares must add up.
 */
class OrderBookOracleTest {

	private static final int STREAMS = 200;

	private static final int COMMANDS_PER_STREAM = 300;

	private static final long MID_CENTS = 10_000;

	@Test
	void optimizedBookMatchesTheReferenceOnRandomStreams() {
		for (int stream = 0; stream < STREAMS; stream++) {
			runStream(stream);
		}
	}

	private static void runStream(long seed) {
		Random random = new Random(seed);
		int maxFills = seed % 4 == 0 ? 3 : 1_000;
		OrderBook book = new OrderBook(maxFills);
		ReferenceMatcher reference = new ReferenceMatcher(maxFills);
		List<UUID> accounts = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				UUID.randomUUID());
		Map<UUID, Shares> shares = new HashMap<>();
		long sequence = 0;

		for (int step = 0; step < COMMANDS_PER_STREAM; step++) {
			String where = "seed " + seed + ", step " + step;
			List<UUID> restingIds = reference.restingIds();
			if (!restingIds.isEmpty() && random.nextInt(10) < 3) {
				UUID orderId = restingIds.get(random.nextInt(restingIds.size()));
				BookOrder expected = reference.cancel(orderId).orElseThrow();
				assertThat(book.cancel(orderId)).as(where).contains(expected);
				shares.get(orderId).canceled += expected.remainingQuantity();
			}
			else {
				OrderRequest order = randomOrder(random, accounts, ++sequence);
				MatchPlan expected = reference.submit(order);
				MatchPlan actual = book.plan(order);
				assertThat(actual.fills()).as(where).isEqualTo(expected.fills());
				assertThat(actual.outcome()).as(where).isEqualTo(expected.outcome());
				book.apply(actual);
				record(shares, actual);
			}
			assertSameBook(book, reference, where);
			assertUncrossed(book, where);
			assertSharesAddUp(book, shares, where);
		}
	}

	private static OrderRequest randomOrder(Random random, List<UUID> accounts, long sequence) {
		Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
		boolean market = random.nextInt(5) == 0;
		// A narrow band of prices, so orders cross often and levels hold several orders.
		long cents = MID_CENTS + random.nextLong(-10, 11);
		if (market) {
			cents += side == Side.BUY ? 15 : -15;
		}
		return new OrderRequest(UUID.randomUUID(), accounts.get(random.nextInt(accounts.size())), side,
				market ? OrderType.MARKET_PROTECTED : OrderType.LIMIT, new Price(cents),
				new Quantity(random.nextLong(1, 31)), sequence);
	}

	private static void record(Map<UUID, Shares> shares, MatchPlan plan) {
		if (plan.outcome() == MatchPlan.Outcome.EXECUTION_LIMIT_EXCEEDED) {
			return;
		}
		Shares taker = new Shares(plan.taker().quantity().shares());
		shares.put(plan.taker().orderId(), taker);
		for (Fill fill : plan.fills()) {
			taker.filled += fill.quantity();
			shares.get(fill.makerOrderId()).filled += fill.quantity();
		}
		if (!plan.rests()) {
			taker.canceled += plan.remainingQuantity();
		}
	}

	private static void assertSameBook(OrderBook book, ReferenceMatcher reference, String where) {
		for (Side side : Side.values()) {
			assertThat(book.orders(side)).as(where + ", " + side + " orders").isEqualTo(reference.orders(side));
			assertThat(book.depth(side, Integer.MAX_VALUE)).as(where + ", " + side + " depth")
				.isEqualTo(reference.depth(side));
		}
		assertThat(book.size()).as(where).isEqualTo(reference.restingIds().size());
	}

	private static void assertUncrossed(OrderBook book, String where) {
		List<DepthLevel> bids = book.depth(Side.BUY, 1);
		List<DepthLevel> asks = book.depth(Side.SELL, 1);
		if (!bids.isEmpty() && !asks.isEmpty()) {
			assertThat(bids.get(0).price()).as(where + ", best bid below best ask").isLessThan(asks.get(0).price());
		}
	}

	/** Accepted quantity = filled + resting + cancelled, for every order ever accepted. */
	private static void assertSharesAddUp(OrderBook book, Map<UUID, Shares> shares, String where) {
		shares.forEach((orderId, order) -> {
			long resting = book.find(orderId).map(BookOrder::remainingQuantity).orElse(0L);
			assertThat(order.filled + resting + order.canceled).as(where + ", order " + orderId).isEqualTo(order.requested);
		});
	}

	private static final class Shares {

		private final long requested;

		private long filled;

		private long canceled;

		private Shares(long requested) {
			this.requested = requested;
		}

	}

}
