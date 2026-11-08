package com.mia_princz.mse.execution.trading.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The benchmark's workload: identical every time, valid against the book, and a realistic mix. */
class MatcherWorkloadTest {

	@Test
	void theSameSeedRecordsTheSameWorkloadAndReplaysTheSameWork() {
		MatcherWorkload first = MatcherWorkload.record(7, 500, 2_000);
		MatcherWorkload second = MatcherWorkload.record(7, 500, 2_000);

		assertThat(second.commands()).isEqualTo(first.commands());
		OrderBook a = first.book();
		OrderBook b = second.book();
		assertThat(first.replay(a)).isEqualTo(second.replay(b));
		assertThat(a.orders(Side.BUY)).isEqualTo(b.orders(Side.BUY));
		assertThat(a.orders(Side.SELL)).isEqualTo(b.orders(Side.SELL));
	}

	@Test
	void replayingFromAFreshBookIsRepeatable() {
		MatcherWorkload workload = MatcherWorkload.record(11, 5_000, 5_000);

		assertThat(workload.replay(workload.book())).isEqualTo(workload.replay(workload.book()));
	}

	@Test
	void aDifferentSeedRecordsDifferentWork() {
		assertThat(MatcherWorkload.record(1, 200, 1_000).commands())
			.isNotEqualTo(MatcherWorkload.record(2, 200, 1_000).commands());
	}

	@Test
	void theWorkloadMixesPlacementsCancelsFillsAndMarketOrders() {
		MatcherWorkload workload = MatcherWorkload.record(MatcherBenchmark.SEED, 5_000, MatcherBenchmark.COMMANDS);
		OrderBook book = workload.book();
		long cancels = 0;
		long market = 0;
		long filling = 0;
		for (MatcherWorkload.Command command : workload.commands()) {
			if (command instanceof MatcherWorkload.Cancel cancel) {
				cancels++;
				book.cancel(cancel.orderId()).orElseThrow();
				continue;
			}
			OrderRequest request = ((MatcherWorkload.Place) command).request();
			market += request.type() == OrderType.MARKET_PROTECTED ? 1 : 0;
			MatchPlan plan = book.plan(request);
			filling += plan.fills().isEmpty() ? 0 : 1;
			book.apply(plan);
			assertThat(book.isCrossed()).isFalse();
		}

		int size = MatcherBenchmark.COMMANDS;
		assertThat(cancels).as("cancels").isBetween(size / 5L, size * 2L / 5);
		assertThat(market).as("protected market orders").isBetween(size / 20L, size / 5L);
		assertThat(filling).as("orders that fill").isGreaterThan(size / 10L);
		assertThat(book.size()).as("the book neither empties nor runs away").isBetween(1_000, 20_000);
	}

}
