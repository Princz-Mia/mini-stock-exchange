package com.mia_princz.mse.execution.trading.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Seeded random streams cut into runs of 1 to 32 commands. One book plans each command of a run against an overlay
 * of the run's earlier commands and applies the run only at its end; its twin plans and applies every command at
 * once. Each overlaid plan must equal the twin's plan, revision included, and so must every cancel, every lookup, the
 * depth and the resting counts; once a run is applied, both books must be the same.
 */
class BookOverlayOracleTest {

	private static final int STREAMS = 200;

	private static final int COMMANDS_PER_STREAM = 400;

	private static final long MID_CENTS = 10_000;

	@Test
	void overlaidPlansEqualPlanningAfterEachApply() {
		for (int stream = 0; stream < STREAMS; stream++) {
			runStream(stream);
		}
	}

	/** What a run will apply to the book after it commits, in order. */
	private sealed interface Change {

		record Apply(MatchPlan plan) implements Change {
		}

		record Cancel(UUID orderId) implements Change {
		}

	}

	private static void runStream(long seed) {
		Random random = new Random(1_000 + seed);
		int maxFills = seed % 4 == 0 ? 3 : 1_000;
		RestingOrders batchedCounts = new RestingOrders();
		RestingOrders twinCounts = new RestingOrders();
		OrderBook batched = new OrderBook(maxFills, batchedCounts);
		OrderBook twin = new OrderBook(maxFills, twinCounts);
		List<UUID> accounts = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				UUID.randomUUID());
		List<UUID> known = new ArrayList<>();
		long sequence = 0;
		int step = 0;
		while (step < COMMANDS_PER_STREAM) {
			int run = 1 + random.nextInt(32);
			BookOverlay overlay = batched.overlay();
			List<Change> changes = new ArrayList<>();
			for (int k = 0; k < run && step < COMMANDS_PER_STREAM; k++, step++) {
				String where = "seed " + seed + ", step " + step + ", command " + k + " of the run";
				int roll = random.nextInt(10);
				if (!known.isEmpty() && roll < 3) {
					// Any order ever seen: resting in the book, rested earlier in the run, filled, or cancelled.
					UUID orderId = known.get(random.nextInt(known.size()));
					Optional<BookOrder> expected = twin.find(orderId);
					assertThat(overlay.find(orderId)).as(where + " find").isEqualTo(expected);
					List<DepthLevel> predicted = expected.map((order) -> twin.depthAfter(BookDelta.cancel(order),
							order.side(), 20)).orElse(List.of());
					List<DepthLevel> overlaid = expected.map((order) -> overlay.depthAfter(BookDelta.cancel(order),
							order.side(), 20)).orElse(List.of());
					assertThat(overlaid).as(where + " depth after the cancel").isEqualTo(predicted);
					Optional<BookOrder> canceled = overlay.cancel(orderId);
					assertThat(canceled).as(where + " cancel").isEqualTo(twin.cancel(orderId));
					canceled.ifPresent((order) -> changes.add(new Change.Cancel(orderId)));
				}
				else {
					OrderRequest order = randomOrder(random, accounts, ++sequence);
					known.add(order.orderId());
					MatchPlan expected = twin.plan(order);
					MatchPlan actual = overlay.plan(order);
					assertThat(actual).as(where).isEqualTo(expected);
					assertThat(overlay.plan(order)).as(where + ", planned again").isEqualTo(actual);
					// The engine plans, then rejects some orders without applying them: every plan over the fill
					// limit, and an order that would rest past an open-order limit. Half of the former here, and
					// a tenth of the rest.
					boolean limit = actual.outcome() == MatchPlan.Outcome.EXECUTION_LIMIT_EXCEEDED;
					if (limit ? random.nextBoolean() : random.nextInt(10) == 0) {
						continue;
					}
					for (Side side : Side.values()) {
						assertThat(overlay.depthAfter(BookDelta.of(actual), side, 20)).as(where + " " + side + " depth")
							.isEqualTo(twin.depthAfter(BookDelta.of(expected), side, 20));
					}
					overlay.record(actual);
					twin.apply(expected);
					changes.add(new Change.Apply(actual));
				}
				assertThat(overlay.revision()).as(where + " revision").isEqualTo(twin.revision());
				assertThat(batched.size() + overlay.sizeChange()).as(where + " size").isEqualTo(twin.size());
				for (UUID account : accounts) {
					assertThat(batchedCounts.of(account) + overlay.restingChange(account)).as(where + " resting")
						.isEqualTo(twinCounts.of(account));
				}
			}
			for (Change change : changes) {
				switch (change) {
					case Change.Apply apply -> batched.apply(apply.plan());
					case Change.Cancel cancel -> assertThat(batched.cancel(cancel.orderId())).isPresent();
				}
			}
			String where = "seed " + seed + " after the run ending at step " + step;
			assertThat(batched.revision()).as(where).isEqualTo(twin.revision());
			for (Side side : Side.values()) {
				assertThat(batched.orders(side)).as(where + " " + side).isEqualTo(twin.orders(side));
			}
			assertThat(batchedCounts.counts()).as(where + " counts").isEqualTo(twinCounts.counts());
			assertThat(batched.isCrossed()).as(where).isFalse();
		}
	}

	private static OrderRequest randomOrder(Random random, List<UUID> accounts, long sequence) {
		Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
		boolean market = random.nextInt(5) == 0;
		// A narrow band of prices, so orders cross often, levels hold several orders, and a run's orders meet.
		long cents = MID_CENTS + random.nextLong(-6, 7);
		if (market) {
			cents += side == Side.BUY ? 15 : -15;
		}
		return new OrderRequest(UUID.randomUUID(), accounts.get(random.nextInt(accounts.size())), side,
				market ? OrderType.MARKET_PROTECTED : OrderType.LIMIT, new Price(cents),
				new Quantity(random.nextLong(1, 31)), sequence);
	}

}
