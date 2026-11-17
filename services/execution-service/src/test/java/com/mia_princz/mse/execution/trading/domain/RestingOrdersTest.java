package com.mia_princz.mse.execution.trading.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class RestingOrdersTest {

	private static final UUID ALICE = UUID.randomUUID();

	private static final UUID BOB = UUID.randomUUID();

	private final RestingOrders resting = new RestingOrders();

	private final OrderBook alpha = new OrderBook(1_000, resting);

	private final OrderBook bravo = new OrderBook(1_000, resting);

	private long sequence;

	@Test
	void booksThatShareATallyCountEachAccountsRestingOrdersAcrossThem() {
		OrderRequest alphaSell = submit(alpha, ALICE, Side.SELL, "101.00", 5);
		submit(bravo, ALICE, Side.SELL, "50.00", 2);
		submit(bravo, BOB, Side.BUY, "49.00", 1);

		assertThat(resting.counts()).isEqualTo(Map.of(ALICE, 2, BOB, 1));

		submit(alpha, BOB, Side.BUY, "101.00", 3);
		assertThat(resting.of(ALICE)).as("a partly filled order still rests").isEqualTo(2);
		assertThat(resting.of(BOB)).as("a taker that filled does not rest").isEqualTo(1);

		submit(alpha, BOB, Side.BUY, "101.00", 2);
		assertThat(resting.of(ALICE)).as("a fully filled maker leaves").isEqualTo(1);
		assertThat(alpha.find(alphaSell.orderId())).isEmpty();

		submit(bravo, BOB, Side.BUY, "50.00", 3);
		assertThat(resting.counts()).as("the rest of Bob's buy rests after filling Alice's sell")
			.isEqualTo(Map.of(BOB, 2));
	}

	@Test
	void cancelsAndRestoresAreCountedToo() {
		OrderRequest sell = submit(alpha, ALICE, Side.SELL, "101.00", 5);
		alpha.cancel(sell.orderId());
		assertThat(resting.of(ALICE)).isZero();
		assertThat(resting.counts()).as("an account with none is forgotten").isEmpty();

		bravo.restore(new BookOrder(UUID.randomUUID(), BOB, Side.BUY, Price.parse("99.00"), 4, ++sequence));
		assertThat(resting.counts()).isEqualTo(Map.of(BOB, 1));

		resting.clear();
		assertThat(resting.counts()).isEmpty();
	}

	@Test
	void removingAnOrderThatWasNeverCountedIsRefused() {
		assertThatThrownBy(() -> resting.removed(ALICE)).isInstanceOf(IllegalStateException.class);
	}

	private OrderRequest submit(OrderBook book, UUID account, Side side, String price, long quantity) {
		OrderRequest order = new OrderRequest(UUID.randomUUID(), account, side, OrderType.LIMIT, Price.parse(price),
				new Quantity(quantity), ++sequence);
		book.apply(book.plan(order));
		return order;
	}

}
