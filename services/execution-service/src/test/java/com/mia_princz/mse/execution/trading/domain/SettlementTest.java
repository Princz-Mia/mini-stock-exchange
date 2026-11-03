package com.mia_princz.mse.execution.trading.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.mia_princz.mse.execution.trading.domain.Posting.Bucket;
import com.mia_princz.mse.execution.trading.domain.SettlementPlan.BalanceChange;
import com.mia_princz.mse.execution.trading.domain.SettlementPlan.BalanceKey;
import com.mia_princz.mse.execution.trading.domain.SettlementPlan.Cause;

class SettlementTest {

	private static final UUID NOVA = UUID.randomUUID();

	private static final UUID BUYER = UUID.randomUUID();

	private long sequence;

	/** Golden fixture: 10@101.00 against 3 and 4 at 100.00 and 3 at 101.00. */
	@Test
	void goldenFixturePostingsAndBalances() {
		OrderBook book = new OrderBook(1_000);
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		UUID third = UUID.randomUUID();
		rest(book, first, Side.SELL, "100.00", 3);
		rest(book, second, Side.SELL, "100.00", 4);
		rest(book, third, Side.SELL, "101.00", 5);

		MatchPlan match = book.plan(order(BUYER, Side.BUY, OrderType.LIMIT, "101.00", 10));
		SettlementPlan settlement = Settlement.of(NOVA, match);

		assertThat(settlement.batches()).extracting(SettlementPlan.Batch::cause)
			.containsExactly(Cause.RESERVE, Cause.TRADE, Cause.TRADE, Cause.TRADE);
		assertThat(settlement.batches().get(0).postings()).containsExactly(
				new Posting(BUYER, null, Bucket.AVAILABLE, -101_000), new Posting(BUYER, null, Bucket.RESERVED, 101_000));
		assertThat(settlement.batches().get(1).postings()).containsExactly(
				new Posting(BUYER, null, Bucket.RESERVED, -30_300), new Posting(BUYER, null, Bucket.AVAILABLE, 300),
				new Posting(first, null, Bucket.AVAILABLE, 30_000), new Posting(first, NOVA, Bucket.RESERVED, -3),
				new Posting(BUYER, NOVA, Bucket.AVAILABLE, 3));
		assertThat(settlement.batches().get(3).postings()).as("no price improvement at 101.00 is omitted")
			.containsExactly(new Posting(BUYER, null, Bucket.RESERVED, -30_300),
					new Posting(third, null, Bucket.AVAILABLE, 30_300), new Posting(third, NOVA, Bucket.RESERVED, -3),
					new Posting(BUYER, NOVA, Bucket.AVAILABLE, 3));

		Map<BalanceKey, BalanceChange> changes = settlement.balanceChanges();
		assertThat(changes.get(new BalanceKey(BUYER, null))).as("spends 100,300, holds nothing")
			.isEqualTo(new BalanceChange(-100_300, 0));
		assertThat(changes.get(new BalanceKey(BUYER, NOVA))).isEqualTo(new BalanceChange(10, 0));
		assertThat(changes.get(new BalanceKey(third, NOVA))).isEqualTo(new BalanceChange(-3, -3));
	}

	/** Golden alternative: 6 fill at 100.00, 4 rest, and the hold left is 40,400 until a cancel releases it. */
	@Test
	void goldenAlternativeHoldAndCancelRelease() {
		OrderBook book = new OrderBook(1_000);
		rest(book, UUID.randomUUID(), Side.SELL, "100.00", 6);
		OrderRequest buy = order(BUYER, Side.BUY, OrderType.LIMIT, "101.00", 10);
		MatchPlan match = book.plan(buy);
		book.apply(match);

		BalanceChange afterFill = Settlement.of(NOVA, match).balanceChanges().get(new BalanceKey(BUYER, null));
		assertThat(afterFill).as("spent 60,000; 600 improvement released; 40,400 held")
			.isEqualTo(new BalanceChange(-60_000, 40_400));

		BookOrder resting = book.find(buy.orderId()).orElseThrow();
		assertThat(Settlement.cancel(NOVA, resting).balanceChanges().get(new BalanceKey(BUYER, null)))
			.isEqualTo(new BalanceChange(0, -40_400));
	}

	@Test
	void protectedMarketRemainderIsReleased() {
		OrderBook book = new OrderBook(1_000);
		rest(book, UUID.randomUUID(), Side.BUY, "50.00", 2);

		MatchPlan match = book.plan(order(BUYER, Side.SELL, OrderType.MARKET_PROTECTED, "49.00", 5));
		SettlementPlan settlement = Settlement.of(NOVA, match);

		assertThat(settlement.batches()).extracting(SettlementPlan.Batch::cause)
			.containsExactly(Cause.RESERVE, Cause.TRADE, Cause.RELEASE);
		assertThat(settlement.balanceChanges().get(new BalanceKey(BUYER, NOVA))).isEqualTo(new BalanceChange(-2, 0));
		assertThat(settlement.balanceChanges().get(new BalanceKey(BUYER, null))).as("sold 2 at the resting 50.00")
			.isEqualTo(new BalanceChange(10_000, 0));
	}

	@Test
	void rejectedOrdersSettleNothing() {
		OrderBook book = new OrderBook(1);
		rest(book, UUID.randomUUID(), Side.SELL, "10.00", 1);
		rest(book, UUID.randomUUID(), Side.SELL, "10.00", 1);

		MatchPlan match = book.plan(order(BUYER, Side.BUY, OrderType.LIMIT, "10.00", 2));

		assertThat(match.outcome()).isEqualTo(MatchPlan.Outcome.EXECUTION_LIMIT_EXCEEDED);
		assertThat(Settlement.of(NOVA, match).batches()).isEmpty();
	}

	/**
	 * Random streams through the book with settlement applied to balances. After every command: each batch sums to zero
	 * per asset, all cash and all shares are conserved, nobody goes negative, and every reserved amount equals exactly
	 * the holds of that account's resting orders.
	 */
	@Test
	void randomStreamsKeepBalancesAndHoldsExact() {
		for (long seed = 0; seed < 100; seed++) {
			Random random = new Random(seed);
			OrderBook book = new OrderBook(seed % 4 == 0 ? 3 : 1_000);
			List<UUID> accounts = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
			Map<BalanceKey, long[]> balances = new HashMap<>();
			for (UUID account : accounts) {
				balances.put(new BalanceKey(account, null), new long[] { 1_000_000_000L, 0 });
				balances.put(new BalanceKey(account, NOVA), new long[] { 10_000, 0 });
			}
			for (int step = 0; step < 300; step++) {
				String where = "seed " + seed + ", step " + step;
				SettlementPlan settlement;
				List<BookOrder> resting = new ArrayList<>(book.orders(Side.BUY));
				resting.addAll(book.orders(Side.SELL));
				if (!resting.isEmpty() && random.nextInt(10) < 3) {
					BookOrder victim = resting.get(random.nextInt(resting.size()));
					settlement = Settlement.cancel(NOVA, book.cancel(victim.orderId()).orElseThrow());
				}
				else {
					Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
					boolean market = random.nextInt(5) == 0;
					long cents = 10_000 + random.nextLong(-10, 11) + (market ? (side == Side.BUY ? 15 : -15) : 0);
					MatchPlan match = book.plan(new OrderRequest(UUID.randomUUID(),
							accounts.get(random.nextInt(accounts.size())), side,
							market ? OrderType.MARKET_PROTECTED : OrderType.LIMIT, new Price(cents),
							new Quantity(random.nextLong(1, 31)), ++sequence));
					book.apply(match);
					settlement = Settlement.of(NOVA, match);
				}
				for (SettlementPlan.Batch batch : settlement.batches()) {
					assertThat(sum(batch.postings(), true)).as(where + " cash balances").isZero();
					assertThat(sum(batch.postings(), false)).as(where + " shares balance").isZero();
				}
				settlement.balanceChanges().forEach((key, change) -> {
					long[] balance = balances.get(key);
					balance[0] += change.totalDelta();
					balance[1] += change.reservedDelta();
				});
				assertBalances(balances, accounts, book, where);
			}
		}
	}

	private static void assertBalances(Map<BalanceKey, long[]> balances, List<UUID> accounts, OrderBook book,
			String where) {
		long cash = 0;
		long shares = 0;
		for (UUID account : accounts) {
			long[] money = balances.get(new BalanceKey(account, null));
			long[] stock = balances.get(new BalanceKey(account, NOVA));
			cash += money[0];
			shares += stock[0];
			assertThat(money[1]).as(where).isBetween(0L, money[0]);
			assertThat(stock[1]).as(where).isBetween(0L, stock[0]);
			long cashHold = book.orders(Side.BUY)
				.stream()
				.filter((order) -> order.accountId().equals(account))
				.mapToLong((order) -> Settlement.holdAmount(Side.BUY, order.price(), order.remainingQuantity()))
				.sum();
			long shareHold = book.orders(Side.SELL)
				.stream()
				.filter((order) -> order.accountId().equals(account))
				.mapToLong(BookOrder::remainingQuantity)
				.sum();
			assertThat(money[1]).as(where + " cash hold of " + account).isEqualTo(cashHold);
			assertThat(stock[1]).as(where + " share hold of " + account).isEqualTo(shareHold);
		}
		assertThat(cash).as(where + " cash is conserved").isEqualTo(4 * 1_000_000_000L);
		assertThat(shares).as(where + " shares are conserved").isEqualTo(4 * 10_000L);
	}

	private static long sum(List<Posting> postings, boolean cash) {
		return postings.stream().filter((posting) -> posting.isCash() == cash).mapToLong(Posting::delta).sum();
	}

	private void rest(OrderBook book, UUID account, Side side, String price, long quantity) {
		MatchPlan plan = book.plan(order(account, side, OrderType.LIMIT, price, quantity));
		book.apply(plan);
	}

	private OrderRequest order(UUID account, Side side, OrderType type, String bound, long quantity) {
		return new OrderRequest(UUID.randomUUID(), account, side, type, Price.parse(bound), new Quantity(quantity),
				++sequence);
	}

}
