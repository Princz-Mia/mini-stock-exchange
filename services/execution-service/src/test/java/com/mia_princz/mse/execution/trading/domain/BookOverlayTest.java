package com.mia_princz.mse.execution.trading.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** The cases a run of commands must get right before any of them commits. */
class BookOverlayTest {

	private static final UUID ALICE = UUID.randomUUID();

	private static final UUID BOB = UUID.randomUUID();

	private static final UUID CAROL = UUID.randomUUID();

	private final RestingOrders counts = new RestingOrders();

	private final OrderBook book = new OrderBook(1_000, counts);

	private long sequence;

	/** A maker an earlier command of the run filled is not filled again. */
	@Test
	void aMakerFilledEarlierInTheRunIsGone() {
		OrderRequest ask = limit(ALICE, Side.SELL, 100, 5);
		book.apply(book.plan(ask));
		BookOverlay overlay = book.overlay();

		MatchPlan first = overlay.plan(limit(BOB, Side.BUY, 100, 5));
		overlay.record(first);
		MatchPlan second = overlay.plan(limit(CAROL, Side.BUY, 100, 5));

		assertThat(first.fills()).hasSize(1);
		assertThat(second.fills()).as("the ask is gone").isEmpty();
		assertThat(second.outcome()).isEqualTo(MatchPlan.Outcome.RESTS);
		assertThat(second.bookRevision()).isEqualTo(book.revision() + 1);
		assertThat(overlay.find(ask.orderId())).isEmpty();
	}

	/** A maker an earlier command partly filled has only what is left. */
	@Test
	void aMakerPartlyFilledEarlierInTheRunHasWhatIsLeft() {
		OrderRequest ask = limit(ALICE, Side.SELL, 100, 5);
		book.apply(book.plan(ask));
		BookOverlay overlay = book.overlay();

		overlay.record(overlay.plan(limit(BOB, Side.BUY, 100, 2)));
		MatchPlan second = overlay.plan(limit(CAROL, Side.BUY, 100, 5));

		assertThat(second.fills()).singleElement().satisfies((fill) -> {
			assertThat(fill.quantity()).isEqualTo(3);
			assertThat(fill.makerRemainingAfter()).isZero();
		});
		assertThat(overlay.find(ask.orderId())).hasValueSatisfying((order) -> assertThat(order.remainingQuantity())
			.isEqualTo(3));
	}

	/** An order rested earlier in the run waits behind the book's own orders at its price. */
	@Test
	void anOrderRestedInTheRunWaitsBehindTheBooksOwnAtItsPrice() {
		OrderRequest committed = limit(ALICE, Side.SELL, 100, 5);
		book.apply(book.plan(committed));
		BookOverlay overlay = book.overlay();
		OrderRequest rested = limit(BOB, Side.SELL, 100, 5);
		overlay.record(overlay.plan(rested));

		MatchPlan buy = overlay.plan(limit(CAROL, Side.BUY, 100, 7));

		assertThat(buy.fills()).extracting(Fill::makerOrderId).containsExactly(committed.orderId(), rested.orderId());
		assertThat(buy.fills().get(1).quantity()).isEqualTo(2);
	}

	/** An order rested in the run at a better price than the book's is taken first. */
	@Test
	void anOrderRestedInTheRunAtABetterPriceIsTakenFirst() {
		book.apply(book.plan(limit(ALICE, Side.SELL, 101, 5)));
		BookOverlay overlay = book.overlay();
		OrderRequest better = limit(BOB, Side.SELL, 100, 5);
		overlay.record(overlay.plan(better));

		MatchPlan buy = overlay.plan(limit(CAROL, Side.BUY, 101, 6));

		assertThat(buy.fills()).extracting(Fill::makerOrderId).first().isEqualTo(better.orderId());
		assertThat(buy.fills()).extracting((fill) -> fill.price().cents()).containsExactly(100L, 101L);
	}

	/** A cancel finds an order rested earlier in the same run, and the order is gone for the commands after it. */
	@Test
	void aCancelFindsAnOrderRestedEarlierInTheRun() {
		BookOverlay overlay = book.overlay();
		OrderRequest rested = limit(ALICE, Side.SELL, 100, 5);
		overlay.record(overlay.plan(rested));

		assertThat(overlay.cancel(rested.orderId())).hasValueSatisfying((order) -> assertThat(order.remainingQuantity())
			.isEqualTo(5));

		assertThat(overlay.find(rested.orderId())).isEmpty();
		assertThat(overlay.plan(limit(BOB, Side.BUY, 100, 5)).fills()).isEmpty();
		assertThat(overlay.cancel(rested.orderId())).as("only once").isEmpty();
		assertThat(overlay.restingChange(ALICE)).isZero();
		assertThat(overlay.changes()).isEqualTo(2);
	}

	/** The self-trade check sees the run's orders, and skips makers the run removed. */
	@Test
	void selfTradePreventionSeesTheRunsOrdersAndNotItsRemovedOnes() {
		OrderRequest bobs = limit(BOB, Side.SELL, 100, 5);
		book.apply(book.plan(bobs));
		BookOverlay overlay = book.overlay();
		overlay.cancel(bobs.orderId());
		overlay.record(overlay.plan(limit(ALICE, Side.SELL, 100, 5)));

		MatchPlan alice = overlay.plan(limit(ALICE, Side.BUY, 100, 5));
		MatchPlan bob = overlay.plan(limit(BOB, Side.BUY, 100, 5));

		assertThat(alice.outcome()).isEqualTo(MatchPlan.Outcome.SELF_TRADE_PREVENTED);
		assertThat(bob.fills()).as("Bob's own cancelled ask is not in his way").hasSize(1);
	}

	/** Applying the run's plans and cancels in order after it commits leaves the book as planned. */
	@Test
	void theRunAppliedInOrderLeavesTheBookAsPlanned() {
		OrderRequest committed = limit(ALICE, Side.SELL, 100, 5);
		book.apply(book.plan(committed));
		BookOverlay overlay = book.overlay();
		MatchPlan rest = overlay.plan(limit(BOB, Side.SELL, 101, 4));
		overlay.record(rest);
		MatchPlan take = overlay.plan(limit(CAROL, Side.BUY, 101, 7));
		overlay.record(take);
		List<DepthLevel> predicted = overlay.depthAfter(BookDelta.none(), Side.SELL, 10);

		book.apply(rest);
		book.apply(take);

		assertThat(book.depth(Side.SELL, 10)).isEqualTo(predicted);
		assertThat(book.orders(Side.SELL)).singleElement().satisfies((order) -> {
			assertThat(order.accountId()).isEqualTo(BOB);
			assertThat(order.remainingQuantity()).isEqualTo(2);
		});
		assertThat(counts.of(ALICE)).isZero();
		assertThat(counts.of(BOB)).isEqualTo(1);
	}

	/** An overlay is refused once its book has changed, and a plan from a stale overlay cannot be recorded. */
	@Test
	void anOverlayIsRefusedOnceItsBookChanged() {
		BookOverlay overlay = book.overlay();
		MatchPlan planned = overlay.plan(limit(ALICE, Side.SELL, 100, 5));
		overlay.record(planned);

		assertThatThrownBy(() -> overlay.record(planned)).as("recorded twice").isInstanceOf(IllegalStateException.class);
		book.apply(book.plan(limit(BOB, Side.BUY, 90, 1)));
		assertThatThrownBy(() -> overlay.plan(limit(CAROL, Side.BUY, 100, 1)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("changed under its overlay");
		assertThatThrownBy(() -> book.plan(limit(CAROL, Side.BUY, 100, 1), new OrderBook(1).overlay()))
			.isInstanceOf(IllegalArgumentException.class);
	}

	/** An order rested in the run cannot be placed again, and sequences keep rising across the run. */
	@Test
	void theRunsOrdersAndSequencesCountAsTheBooks() {
		BookOverlay overlay = book.overlay();
		OrderRequest rested = limit(ALICE, Side.SELL, 100, 5);
		overlay.record(overlay.plan(rested));

		assertThatThrownBy(() -> overlay.plan(rested)).isInstanceOf(IllegalArgumentException.class);
		OrderRequest stale = new OrderRequest(UUID.randomUUID(), BOB, Side.BUY, OrderType.LIMIT, new Price(90),
				new Quantity(1), rested.acceptedSequence());
		assertThatThrownBy(() -> overlay.plan(stale)).isInstanceOf(IllegalArgumentException.class);
	}

	private OrderRequest limit(UUID account, Side side, long cents, long shares) {
		return new OrderRequest(UUID.randomUUID(), account, side, OrderType.LIMIT, new Price(cents),
				new Quantity(shares), ++sequence);
	}

}
