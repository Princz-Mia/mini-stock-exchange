package com.mia_princz.mse.execution.trading.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class OrderBookTest {

	private static final UUID BUYER = UUID.randomUUID();

	private final OrderBook book = new OrderBook(1_000);

	private long sequence;

	/** Golden fixture: three sellers rest 3@100.00, 4@100.00 and 5@101.00; a buyer takes 10@101.00. */
	@Test
	void goldenFixtureFillsAcrossLevelsAtMakerPrices() {
		OrderRequest first = rest(UUID.randomUUID(), Side.SELL, "100.00", 3);
		OrderRequest second = rest(UUID.randomUUID(), Side.SELL, "100.00", 4);
		OrderRequest third = rest(UUID.randomUUID(), Side.SELL, "101.00", 5);

		MatchPlan plan = submit(limit(BUYER, Side.BUY, "101.00", 10));

		assertThat(plan.fills()).extracting(Fill::makerOrderId, Fill::quantity, (fill) -> fill.price().format())
			.containsExactly(org.assertj.core.groups.Tuple.tuple(first.orderId(), 3L, "100.00"),
					org.assertj.core.groups.Tuple.tuple(second.orderId(), 4L, "100.00"),
					org.assertj.core.groups.Tuple.tuple(third.orderId(), 3L, "101.00"));
		assertThat(plan.outcome()).isEqualTo(MatchPlan.Outcome.FILLED);
		assertThat(spend(plan)).isEqualTo(100_300);
		assertThat(book.depth(Side.SELL, 20)).containsExactly(new DepthLevel(Price.parse("101.00"), 2, 1));
		assertThat(book.depth(Side.BUY, 20)).isEmpty();
	}

	/** Golden alternative: only 6@100.00 are offered, so 6 fill and 4 rest at the buyer's limit. */
	@Test
	void goldenAlternativeRestsTheRemainderAtTheLimit() {
		rest(UUID.randomUUID(), Side.SELL, "100.00", 6);

		MatchPlan plan = submit(limit(BUYER, Side.BUY, "101.00", 10));

		assertThat(plan.filledQuantity()).isEqualTo(6);
		assertThat(spend(plan)).isEqualTo(60_000);
		assertThat(plan.outcome()).isEqualTo(MatchPlan.Outcome.RESTS);
		assertThat(book.depth(Side.BUY, 20)).containsExactly(new DepthLevel(Price.parse("101.00"), 4, 1));
		assertThat(book.depth(Side.SELL, 20)).isEmpty();
	}

	@Test
	void betterPriceWinsOverEarlierTime() {
		rest(UUID.randomUUID(), Side.BUY, "99.00", 5);
		OrderRequest better = rest(UUID.randomUUID(), Side.BUY, "99.50", 5);

		MatchPlan plan = submit(limit(UUID.randomUUID(), Side.SELL, "98.00", 5));

		assertThat(plan.fills()).singleElement().satisfies((fill) -> {
			assertThat(fill.makerOrderId()).isEqualTo(better.orderId());
			assertThat(fill.price().format()).as("maker price, not the seller's 98.00").isEqualTo("99.50");
		});
	}

	@Test
	void earlierOrderWinsAtTheSamePrice() {
		OrderRequest early = rest(UUID.randomUUID(), Side.SELL, "50.00", 5);
		rest(UUID.randomUUID(), Side.SELL, "50.00", 5);

		MatchPlan plan = submit(limit(BUYER, Side.BUY, "50.00", 3));

		assertThat(plan.fills()).singleElement().extracting(Fill::makerOrderId).isEqualTo(early.orderId());
		assertThat(book.find(early.orderId())).get().extracting(BookOrder::remainingQuantity).isEqualTo(2L);
	}

	@Test
	void nonCrossingLimitOrderRestsWithoutFills() {
		rest(UUID.randomUUID(), Side.SELL, "101.00", 5);

		MatchPlan plan = submit(limit(BUYER, Side.BUY, "100.99", 5));

		assertThat(plan.fills()).isEmpty();
		assertThat(plan.outcome()).isEqualTo(MatchPlan.Outcome.RESTS);
		assertThat(book.depth(Side.BUY, 1)).containsExactly(new DepthLevel(Price.parse("100.99"), 5, 1));
	}

	@Test
	void protectedMarketOrderStopsAtItsBoundAndNeverRests() {
		rest(UUID.randomUUID(), Side.SELL, "100.00", 2);
		rest(UUID.randomUUID(), Side.SELL, "105.00", 10);

		MatchPlan plan = submit(market(BUYER, Side.BUY, "102.00", 5));

		assertThat(plan.filledQuantity()).isEqualTo(2);
		assertThat(plan.remainingQuantity()).isEqualTo(3);
		assertThat(plan.outcome()).isEqualTo(MatchPlan.Outcome.IOC_REMAINDER_CANCELED);
		assertThat(book.depth(Side.BUY, 20)).isEmpty();
		assertThat(book.depth(Side.SELL, 20)).containsExactly(new DepthLevel(Price.parse("105.00"), 10, 1));
	}

	@Test
	void protectedMarketOrderWithNoLiquidityIsCancelled() {
		MatchPlan plan = submit(market(BUYER, Side.SELL, "1.00", 5));

		assertThat(plan.fills()).isEmpty();
		assertThat(plan.outcome()).isEqualTo(MatchPlan.Outcome.IOC_REMAINDER_CANCELED);
		assertThat(book.size()).isZero();
	}

	@Test
	void selfTradeCancelsTheRemainderButKeepsEarlierFills() {
		OrderRequest other = rest(UUID.randomUUID(), Side.SELL, "100.00", 2);
		OrderRequest own = rest(BUYER, Side.SELL, "100.00", 5);
		rest(UUID.randomUUID(), Side.SELL, "100.00", 5);

		MatchPlan plan = submit(limit(BUYER, Side.BUY, "100.00", 8));

		assertThat(plan.fills()).singleElement().extracting(Fill::makerOrderId).isEqualTo(other.orderId());
		assertThat(plan.outcome()).isEqualTo(MatchPlan.Outcome.SELF_TRADE_PREVENTED);
		assertThat(plan.remainingQuantity()).isEqualTo(6);
		assertThat(book.find(own.orderId())).as("the maker is not skipped or touched").get()
			.extracting(BookOrder::remainingQuantity).isEqualTo(5L);
		assertThat(book.depth(Side.BUY, 20)).as("the remainder does not rest").isEmpty();
	}

	@Test
	void tooManyFillsRejectsTheWholeOrderAtomically() {
		OrderBook small = new OrderBook(2);
		for (int i = 0; i < 3; i++) {
			OrderRequest seller = limit(UUID.randomUUID(), Side.SELL, "10.00", 1);
			small.apply(small.plan(seller));
		}
		List<DepthLevel> before = small.depth(Side.SELL, 20);

		MatchPlan plan = small.plan(limit(BUYER, Side.BUY, "10.00", 3));
		small.apply(plan);

		assertThat(plan.outcome()).isEqualTo(MatchPlan.Outcome.EXECUTION_LIMIT_EXCEEDED);
		assertThat(plan.fills()).isEmpty();
		assertThat(small.depth(Side.SELL, 20)).isEqualTo(before);
		assertThat(small.depth(Side.BUY, 20)).isEmpty();
	}

	@Test
	void exactlyTheFillLimitIsAllowed() {
		OrderBook small = new OrderBook(2);
		for (int i = 0; i < 2; i++) {
			small.apply(small.plan(limit(UUID.randomUUID(), Side.SELL, "10.00", 1)));
		}

		MatchPlan plan = small.plan(limit(BUYER, Side.BUY, "10.00", 2));

		assertThat(plan.outcome()).isEqualTo(MatchPlan.Outcome.FILLED);
		assertThat(plan.fills()).hasSize(2);
	}

	@Test
	void planningDoesNotChangeTheBook() {
		rest(UUID.randomUUID(), Side.SELL, "100.00", 5);
		long revision = book.revision();

		MatchPlan first = book.plan(limit(BUYER, Side.BUY, "100.00", 3));
		MatchPlan second = book.plan(first.taker());

		assertThat(second).isEqualTo(first);
		assertThat(book.revision()).isEqualTo(revision);
		assertThat(book.depth(Side.SELL, 1)).containsExactly(new DepthLevel(Price.parse("100.00"), 5, 1));
	}

	@Test
	void aPlanFromAnOlderBookIsRefused() {
		rest(UUID.randomUUID(), Side.SELL, "100.00", 5);
		MatchPlan stale = book.plan(limit(BUYER, Side.BUY, "100.00", 3));
		rest(UUID.randomUUID(), Side.SELL, "99.00", 5);

		assertThatThrownBy(() -> book.apply(stale)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void cancelRemovesTheOrderAndItsEmptyLevel() {
		OrderRequest only = rest(UUID.randomUUID(), Side.BUY, "20.00", 7);
		rest(UUID.randomUUID(), Side.BUY, "19.00", 1);

		assertThat(book.cancel(only.orderId())).get().extracting(BookOrder::remainingQuantity).isEqualTo(7L);
		assertThat(book.cancel(only.orderId())).as("already gone").isEmpty();
		assertThat(book.depth(Side.BUY, 20)).containsExactly(new DepthLevel(Price.parse("19.00"), 1, 1));
	}

	@Test
	void cancellingTheMiddleOfALevelKeepsTheQueueOrder() {
		OrderRequest first = rest(UUID.randomUUID(), Side.SELL, "30.00", 1);
		OrderRequest middle = rest(UUID.randomUUID(), Side.SELL, "30.00", 2);
		OrderRequest last = rest(UUID.randomUUID(), Side.SELL, "30.00", 3);

		book.cancel(middle.orderId());

		assertThat(book.orders(Side.SELL)).extracting(BookOrder::orderId).containsExactly(first.orderId(), last.orderId());
		assertThat(book.depth(Side.SELL, 1)).containsExactly(new DepthLevel(Price.parse("30.00"), 4, 2));
	}

	@Test
	void ordersMustArriveInSequenceAndOnlyOnce() {
		OrderRequest resting = rest(UUID.randomUUID(), Side.BUY, "10.00", 1);

		assertThatThrownBy(() -> book.plan(resting)).isInstanceOf(IllegalArgumentException.class);
		OrderRequest old = new OrderRequest(UUID.randomUUID(), BUYER, Side.BUY, OrderType.LIMIT, Price.parse("10.00"),
				new Quantity(1), resting.acceptedSequence());
		assertThatThrownBy(() -> book.plan(old)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void restoredOrdersKeepTheirQueuePositionAndMatchNormally() {
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		book.restore(new BookOrder(first, UUID.randomUUID(), Side.SELL, Price.parse("10.00"), 2, 5));
		book.restore(new BookOrder(second, UUID.randomUUID(), Side.SELL, Price.parse("10.00"), 3, 8));
		sequence = 8;

		MatchPlan plan = submit(limit(BUYER, Side.BUY, "10.00", 4));

		assertThat(plan.fills()).extracting(Fill::makerOrderId).containsExactly(first, second);
		assertThat(book.find(second)).get()
			.extracting(BookOrder::remainingQuantity)
			.isEqualTo(1L);
	}

	@Test
	void restoringOutOfOrderOrTwiceIsRefused() {
		BookOrder order = new BookOrder(UUID.randomUUID(), UUID.randomUUID(), Side.BUY, Price.parse("10.00"), 1, 5);
		book.restore(order);

		assertThatThrownBy(() -> book.restore(order)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> book.restore(
				new BookOrder(UUID.randomUUID(), UUID.randomUUID(), Side.BUY, Price.parse("10.00"), 1, 4)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void aRestoredBookThatCrossesIsDetected() {
		book.restore(new BookOrder(UUID.randomUUID(), UUID.randomUUID(), Side.BUY, Price.parse("10.00"), 1, 1));
		assertThat(book.isCrossed()).isFalse();
		book.restore(new BookOrder(UUID.randomUUID(), UUID.randomUUID(), Side.SELL, Price.parse("10.00"), 1, 2));
		assertThat(book.isCrossed()).isTrue();
	}

	/** Golden alternative, predicted: the level at 100.00 empties and 4 appear on the bid, before anything changes. */
	@Test
	void depthAfterAPlanIsPredictedWithoutChangingTheBook() {
		rest(UUID.randomUUID(), Side.SELL, "100.00", 6);
		rest(UUID.randomUUID(), Side.SELL, "102.00", 1);
		MatchPlan plan = book.plan(limit(BUYER, Side.BUY, "101.00", 10));

		BookDelta delta = BookDelta.of(plan);

		assertThat(book.depthAfter(delta, Side.SELL, 20)).containsExactly(new DepthLevel(Price.parse("102.00"), 1, 1));
		assertThat(book.depthAfter(delta, Side.BUY, 20)).containsExactly(new DepthLevel(Price.parse("101.00"), 4, 1));
		assertThat(book.depth(Side.SELL, 20)).as("book unchanged").hasSize(2);
		assertThat(book.depth(Side.BUY, 20)).as("book unchanged").isEmpty();
	}

	@Test
	void depthAfterACancelLeavesTheRestOfTheLevel() {
		OrderRequest first = rest(UUID.randomUUID(), Side.BUY, "99.00", 3);
		rest(UUID.randomUUID(), Side.BUY, "99.00", 4);

		BookDelta delta = BookDelta.cancel(book.find(first.orderId()).orElseThrow());

		assertThat(book.depthAfter(delta, Side.BUY, 20)).containsExactly(new DepthLevel(Price.parse("99.00"), 4, 1));
	}

	@Test
	void deltaThatDoesNotFitTheBookIsRefused() {
		OrderRequest resting = rest(UUID.randomUUID(), Side.BUY, "99.00", 3);
		BookDelta stale = BookDelta.cancel(book.find(resting.orderId()).orElseThrow());
		book.cancel(resting.orderId());

		assertThatThrownBy(() -> book.depthAfter(stale, Side.BUY, 20)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("does not fit");
	}

	private OrderRequest rest(UUID account, Side side, String price, long quantity) {
		OrderRequest order = limit(account, side, price, quantity);
		assertThat(submit(order).outcome()).isEqualTo(MatchPlan.Outcome.RESTS);
		return order;
	}

	private MatchPlan submit(OrderRequest order) {
		MatchPlan plan = book.plan(order);
		book.apply(plan);
		return plan;
	}

	private OrderRequest limit(UUID account, Side side, String price, long quantity) {
		return new OrderRequest(UUID.randomUUID(), account, side, OrderType.LIMIT, Price.parse(price), new Quantity(quantity),
				++sequence);
	}

	private OrderRequest market(UUID account, Side side, String bound, long quantity) {
		return new OrderRequest(UUID.randomUUID(), account, side, OrderType.MARKET_PROTECTED, Price.parse(bound),
				new Quantity(quantity), ++sequence);
	}

	private static long spend(MatchPlan plan) {
		Money spend = Money.ZERO;
		for (Fill fill : plan.fills()) {
			spend = spend.plus(fill.price().times(new Quantity(fill.quantity())));
		}
		return spend.cents();
	}

}
