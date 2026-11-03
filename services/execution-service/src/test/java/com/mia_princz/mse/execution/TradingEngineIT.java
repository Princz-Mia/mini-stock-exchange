package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mia_princz.mse.contracts.events.AggressorSide;
import com.mia_princz.mse.contracts.events.BookLevel;
import com.mia_princz.mse.contracts.events.MarketSnapshot;
import com.mia_princz.mse.contracts.events.PublicTrade;
import com.mia_princz.mse.contracts.events.TradeExecuted;
import com.mia_princz.mse.contracts.events.VenueState;
import com.mia_princz.mse.contracts.events.WireEvents;
import com.mia_princz.mse.execution.trading.adapter.JdbcTradingStore;
import com.mia_princz.mse.execution.trading.adapter.SpringTransactionRunner;
import com.mia_princz.mse.execution.trading.application.CancelOrder;
import com.mia_princz.mse.execution.trading.application.CancelResult;
import com.mia_princz.mse.execution.trading.application.CommandKey;
import com.mia_princz.mse.execution.trading.application.CommandWriter;
import com.mia_princz.mse.execution.trading.application.CommandWriter.WriterHaltException;
import com.mia_princz.mse.execution.trading.application.ReconciliationException;
import com.mia_princz.mse.execution.trading.application.IdempotencyConflictException;
import com.mia_princz.mse.execution.trading.application.PlaceOrder;
import com.mia_princz.mse.execution.trading.application.PlacementResult;
import com.mia_princz.mse.execution.trading.application.PlacementResult.OrderState;
import com.mia_princz.mse.execution.trading.application.TradingEngine;
import com.mia_princz.mse.execution.trading.application.TradingStore;
import com.mia_princz.mse.execution.trading.domain.DepthLevel;
import com.mia_princz.mse.execution.trading.domain.OrderType;
import com.mia_princz.mse.execution.trading.domain.Price;
import com.mia_princz.mse.execution.trading.domain.Quantity;
import com.mia_princz.mse.execution.trading.domain.RejectionCode;
import com.mia_princz.mse.execution.trading.domain.Side;

/**
 * The engine against real PostgreSQL, committing for real. The property gives this class its own application context,
 * so its own containers and database, which the rolled-back schema tests never see. Each test uses its own instrument
 * and accounts so a fresh engine's empty books match the database.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "mse.test.database=trading-engine")
class TradingEngineIT {

	private static final long STARTING_CASH = 10_000_000;

	private static final long STARTING_SHARES = 1_000;

	private static final AtomicInteger SYMBOLS = new AtomicInteger();

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	DataSource dataSource;

	@Autowired
	PlatformTransactionManager transactionManager;

	/** The store method that throws, simulating a failure after every earlier write but before the commit. */
	private final AtomicReference<String> failAt = new AtomicReference<>();

	private CommandWriter writer;

	private TradingStore store;

	private TradingEngine engine;

	private UUID simulation;

	private UUID issuance;

	private UUID instrument;

	@BeforeEach
	void market() {
		simulation = jdbc.queryForList("SELECT id FROM simulation", UUID.class).stream().findFirst().orElseGet(() -> {
			UUID id = UUID.randomUUID();
			jdbc.update("INSERT INTO simulation (id, state, opened_at) VALUES (?, 'OPEN', now())", id);
			jdbc.update("INSERT INTO account (id, simulation_id, kind, display_name) VALUES (?, ?, 'ISSUANCE', 'Issuance')",
					UUID.randomUUID(), id);
			return id;
		});
		issuance = jdbc.queryForObject("SELECT id FROM account WHERE kind = 'ISSUANCE'", UUID.class);
		instrument = UUID.randomUUID();
		jdbc.update("INSERT INTO instrument (id, simulation_id, symbol, reference_price_cents) VALUES (?, ?, ?, 10000)",
				instrument, simulation, symbol(SYMBOLS.incrementAndGet()));

		store = new JdbcTradingStore(JdbcClient.create(dataSource), JsonMapper.builder().build());
		writer = new CommandWriter("test-writer", 1_000);
		engine = new TradingEngine(failingBeforeCommit(store), new SpringTransactionRunner(transactionManager), writer,
				Clock.systemUTC());
		engine.start();
	}

	@AfterEach
	void stop() throws InterruptedException {
		writer.close(Duration.ofSeconds(5));
	}

	/** Golden fixture, end to end: three sellers rest 3@100.00, 4@100.00, 5@101.00; a buyer takes 10@101.00. */
	@Test
	void goldenFixtureCommitsOrdersTradesLedgerBalancesAndEvents() throws Exception {
		UUID first = funded();
		UUID second = funded();
		UUID third = funded();
		UUID buyer = funded();
		long sequenceBefore = commandSequence();
		PlacementResult s1 = place(first, Side.SELL, OrderType.LIMIT, "100.00", 3);
		PlacementResult s2 = place(second, Side.SELL, OrderType.LIMIT, "100.00", 4);
		PlacementResult s3 = place(third, Side.SELL, OrderType.LIMIT, "101.00", 5);

		PlacementResult buy = place(buyer, Side.BUY, OrderType.LIMIT, "101.00", 10);

		assertThat(buy.state()).isEqualTo(OrderState.FILLED);
		assertThat(buy.filled()).isEqualTo(10);
		assertThat(buy.commandSequence()).isEqualTo(sequenceBefore + 4);
		assertThat(jdbc.queryForList("SELECT price_cents FROM trades WHERE instrument_id = ? ORDER BY trade_sequence",
				Long.class, instrument)).containsExactly(10_000L, 10_000L, 10_100L);
		assertThat(jdbc.queryForList("SELECT trade_sequence FROM trades WHERE instrument_id = ? ORDER BY trade_sequence",
				Long.class, instrument)).containsExactly(1L, 2L, 3L);

		assertThat(cash(buyer)).as("spent 100,300; holds nothing").containsExactly(STARTING_CASH - 100_300, 0L);
		assertThat(shares(buyer)).containsExactly(STARTING_SHARES + 10, 0L);
		assertThat(cash(first)).containsExactly(STARTING_CASH + 30_000, 0L);
		assertThat(shares(first)).containsExactly(STARTING_SHARES - 3, 0L);
		assertThat(cash(third)).containsExactly(STARTING_CASH + 30_300, 0L);
		assertThat(shares(third)).as("2 still offered").containsExactly(STARTING_SHARES - 3, 2L);

		assertThat(orderState(s1.orderId())).isEqualTo("FILLED");
		assertThat(orderState(s2.orderId())).isEqualTo("FILLED");
		assertThat(orderState(s3.orderId())).isEqualTo("PARTIALLY_FILLED");
		assertThat(engine.depth(instrument, Side.SELL).get())
			.containsExactly(new DepthLevel(Price.parse("101.00"), 2, 1));

		List<byte[]> events = jdbc.queryForList(
				"SELECT payload FROM outbox WHERE command_sequence = ? AND topic = 'mse.trades.v1' ORDER BY id",
				byte[].class, buy.commandSequence());
		assertThat(events).hasSize(3);
		TradeExecuted last = WireEvents.decodeTrade(events.get(2));
		assertThat(last.getInstrumentId()).isEqualTo(instrument);
		assertThat(last.getTradeSequence()).isEqualTo(3);
		assertThat(last.getPriceCents()).isEqualTo(10_100);
		assertThat(last.getQuantity()).isEqualTo(3);

		MarketSnapshot market = marketSnapshot(buy.commandSequence());
		assertThat(market.getAsks()).containsExactly(new BookLevel(10_100L, 2L, 1));
		assertThat(market.getBids()).isEmpty();
		assertThat(market.getRecentTrades()).extracting(PublicTrade::getTradeSequence, PublicTrade::getPriceCents,
				PublicTrade::getQuantity)
			.containsExactly(org.assertj.core.groups.Tuple.tuple(3L, 10_100L, 3L),
					org.assertj.core.groups.Tuple.tuple(2L, 10_000L, 4L),
					org.assertj.core.groups.Tuple.tuple(1L, 10_000L, 3L));
		assertThat(market.getLastTrade().getAggressorSide()).isEqualTo(AggressorSide.BUY);
		assertThat(market.getLastTradeSequence()).isEqualTo(3);
		assertThat(market.getSourceCommandSequence()).isEqualTo(buy.commandSequence());
		assertLedgerMatchesBalances();
	}

	/** A resting order changes the public book, so its command carries a snapshot; there are no trades yet. */
	@Test
	void restingOrderPublishesTheBookBeforeAnyTrade() throws Exception {
		UUID seller = funded();
		PlacementResult sell = place(seller, Side.SELL, OrderType.LIMIT, "100.50", 7);

		MarketSnapshot snapshot = marketSnapshot(sell.commandSequence());

		assertThat(snapshot.getAsks()).containsExactly(new BookLevel(10_050L, 7L, 1));
		assertThat(snapshot.getBids()).isEmpty();
		assertThat(snapshot.getLastTrade()).isNull();
		assertThat(snapshot.getLastTradeSequence()).isZero();
		assertThat(snapshot.getRecentTrades()).isEmpty();
		assertThat(snapshot.getVenueState()).isEqualTo(VenueState.OPEN);
		assertThat(snapshot.getRules().getReferencePriceCents()).isEqualTo(10_000);
		assertThat(snapshot.getInstrumentId()).isEqualTo(instrument);
		assertThat(jdbc.queryForObject("SELECT event_key FROM outbox WHERE event_id = ?", String.class,
				snapshot.getEventId())).isEqualTo(simulation + "|" + instrument);
	}

	@Test
	void cancelPublishesTheBookWithoutTheOrder() throws Exception {
		UUID seller = funded();
		PlacementResult first = place(seller, Side.SELL, OrderType.LIMIT, "100.00", 3);
		place(seller, Side.SELL, OrderType.LIMIT, "100.00", 4);

		CancelResult cancel = engine.cancel(key(), new CancelOrder(first.orderId(), seller)).get();

		assertThat(marketSnapshot(cancel.commandSequence()).getAsks()).containsExactly(new BookLevel(10_000L, 4L, 1));
	}

	/** Nothing public changes, so nothing is published: a rejection, an IOC that finds nothing, a no-op cancel. */
	@Test
	void commandsThatChangeNothingPublicWriteNoSnapshot() throws Exception {
		UUID buyer = funded();
		PlacementResult rejected = place(buyer, Side.BUY, OrderType.LIMIT, "100000.00", 1_000);
		PlacementResult unfilled = place(buyer, Side.BUY, OrderType.MARKET_PROTECTED, "100.00", 5);
		PlacementResult resting = place(buyer, Side.BUY, OrderType.LIMIT, "90.00", 1);
		engine.cancel(key(), new CancelOrder(resting.orderId(), buyer)).get();
		CancelResult again = engine.cancel(key(), new CancelOrder(resting.orderId(), buyer)).get();

		assertThat(rejected.state()).isEqualTo(OrderState.REJECTED);
		assertThat(unfilled.filled()).isZero();
		assertThat(again.status()).isEqualTo(CancelResult.Status.ALREADY_CANCELED);
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM outbox WHERE topic = 'mse.market.v1' AND command_sequence IN (?, ?, ?)""",
				Long.class, rejected.commandSequence(), unfilled.commandSequence(), again.commandSequence())).isZero();
	}

	/** At most 20 levels a side and the 50 newest trades, newest first; the sequence still counts every trade. */
	@Test
	void snapshotIsBoundedAndNewestFirst() throws Exception {
		// 80 resting orders, 16 per seller, under the 20 open orders an account may have.
		List<UUID> sellers = List.of(funded(), funded(), funded(), funded(), funded());
		for (int i = 0; i < 55; i++) {
			place(sellers.get(i % 5), Side.SELL, OrderType.LIMIT, "100.00", 1);
		}
		for (int level = 0; level < 25; level++) {
			place(sellers.get(level % 5), Side.SELL, OrderType.LIMIT, new Price(10_100 + level).format(), 1);
		}
		UUID buyer = funded();

		PlacementResult buy = place(buyer, Side.BUY, OrderType.LIMIT, "100.00", 55);

		MarketSnapshot snapshot = marketSnapshot(buy.commandSequence());
		assertThat(snapshot.getAsks()).hasSize(20);
		assertThat(snapshot.getAsks().getFirst()).isEqualTo(new BookLevel(10_100L, 1L, 1));
		assertThat(snapshot.getRecentTrades()).hasSize(50);
		assertThat(snapshot.getRecentTrades()).extracting(PublicTrade::getTradeSequence)
			.isSortedAccordingTo(Comparator.reverseOrder())
			.startsWith(55L);
		assertThat(snapshot.getLastTrade()).isEqualTo(snapshot.getRecentTrades().getFirst());
		assertThat(snapshot.getLastTradeSequence()).isEqualTo(55);
		assertThat(snapshot.getRecentTrades()).extracting(PublicTrade::getAggressorSide).containsOnly(AggressorSide.BUY);
	}

	/** A database failure just before commit: nothing in the database changes, the book is untouched, work goes on. */
	@Test
	void aFailureBeforeCommitLeavesNoTrace() throws Exception {
		UUID seller = funded();
		UUID buyer = funded();
		place(seller, Side.SELL, OrderType.LIMIT, "100.00", 5);
		Map<String, Object> before = snapshot();
		List<DepthLevel> bookBefore = engine.depth(instrument, Side.SELL).get();

		failAt.set("insertMarketSnapshot");
		CompletableFuture<PlacementResult> failed = engine.place(key(), order(buyer, Side.BUY, OrderType.LIMIT, "100.00", 3));

		assertThatThrownBy(failed::get).isInstanceOf(ExecutionException.class)
			.rootCause()
			.hasMessageContaining("injected");
		assertThat(snapshot()).isEqualTo(before);
		assertThat(engine.depth(instrument, Side.SELL).get()).isEqualTo(bookBefore);

		failAt.set(null);
		PlacementResult retried = place(buyer, Side.BUY, OrderType.LIMIT, "100.00", 3);
		assertThat(retried.state()).isEqualTo(OrderState.FILLED);
		assertThat(engine.depth(instrument, Side.SELL).get())
			.containsExactly(new DepthLevel(Price.parse("100.00"), 2, 1));
		assertLedgerMatchesBalances();
	}

	@Test
	void insufficientFundsAreADurableRejectionWithNoHold() throws Exception {
		UUID buyer = funded();
		Map<String, Object> before = snapshot();

		PlacementResult result = place(buyer, Side.BUY, OrderType.LIMIT, "1000000.00", 1_000);

		assertThat(result.rejection()).isEqualTo(RejectionCode.INSUFFICIENT_CASH);
		assertThat(orderState(result.orderId())).isEqualTo("REJECTED");
		assertThat(cash(buyer)).containsExactly(STARTING_CASH, 0L);
		Map<String, Object> after = snapshot();
		assertThat(after.get("ledger_entries")).isEqualTo(before.get("ledger_entries"));
		assertThat((long) after.get("command_sequence")).isEqualTo((long) before.get("command_sequence") + 1);
	}

	@Test
	void aHaltedMarketRejectsNewOrders() throws Exception {
		UUID trader = funded();
		jdbc.update("UPDATE simulation SET state = 'HALTED'");
		try {
			assertThat(place(trader, Side.SELL, OrderType.LIMIT, "10.00", 1).rejection())
				.isEqualTo(RejectionCode.MARKET_NOT_OPEN);
		}
		finally {
			jdbc.update("UPDATE simulation SET state = 'OPEN'");
		}
	}

	@Test
	void protectedMarketRemainderIsCancelledAndItsHoldReleased() throws Exception {
		UUID seller = funded();
		UUID buyer = funded();
		place(seller, Side.SELL, OrderType.LIMIT, "50.00", 2);

		PlacementResult result = place(buyer, Side.BUY, OrderType.MARKET_PROTECTED, "60.00", 5);

		assertThat(result.state()).isEqualTo(OrderState.CANCELED);
		assertThat(result.reason()).isEqualTo("IOC_REMAINDER_CANCELED");
		assertThat(result.filled()).isEqualTo(2);
		assertThat(result.canceled()).isEqualTo(3);
		assertThat(cash(buyer)).containsExactly(STARTING_CASH - 10_000, 0L);
		assertLedgerMatchesBalances();
	}

	/** Golden alternative: 6 fill, 4 rest holding 40,400 cents, and cancelling releases exactly that. */
	@Test
	void cancelReleasesExactlyTheRemainingHold() throws Exception {
		UUID seller = funded();
		UUID buyer = funded();
		place(seller, Side.SELL, OrderType.LIMIT, "100.00", 6);
		PlacementResult buy = place(buyer, Side.BUY, OrderType.LIMIT, "101.00", 10);
		assertThat(buy.state()).isEqualTo(OrderState.PARTIALLY_FILLED);
		assertThat(cash(buyer)).containsExactly(STARTING_CASH - 60_000, 40_400L);

		CancelResult cancel = engine.cancel(key(), new CancelOrder(buy.orderId(), buyer)).get();

		assertThat(cancel.status()).isEqualTo(CancelResult.Status.CANCELED);
		assertThat(cancel.canceledQuantity()).isEqualTo(4);
		assertThat(cash(buyer)).containsExactly(STARTING_CASH - 60_000, 0L);
		assertThat(jdbc.queryForMap(
				"SELECT state, filled_quantity, working_quantity, canceled_quantity FROM orders WHERE id = ?",
				buy.orderId()))
			.containsEntry("state", "CANCELED")
			.containsEntry("filled_quantity", 6L)
			.containsEntry("working_quantity", 0L)
			.containsEntry("canceled_quantity", 4L);
		assertThat(engine.depth(instrument, Side.BUY).get()).isEmpty();
		assertLedgerMatchesBalances();
	}

	@Test
	void cancellingTwiceIsASuccessfulNoOp() throws Exception {
		UUID seller = funded();
		PlacementResult sell = place(seller, Side.SELL, OrderType.LIMIT, "100.00", 5);
		engine.cancel(key(), new CancelOrder(sell.orderId(), seller)).get();
		Map<String, Object> before = snapshot();

		CancelResult again = engine.cancel(key(), new CancelOrder(sell.orderId(), seller)).get();

		assertThat(again.status()).isEqualTo(CancelResult.Status.ALREADY_CANCELED);
		Map<String, Object> after = snapshot();
		after.remove("command_sequence");
		before.remove("command_sequence");
		assertThat(after).isEqualTo(before);
	}

	@Test
	void filledRejectedUnknownAndOtherAccountsOrdersCannotBeCancelled() throws Exception {
		UUID seller = funded();
		UUID buyer = funded();
		PlacementResult filled = place(seller, Side.SELL, OrderType.LIMIT, "100.00", 2);
		place(buyer, Side.BUY, OrderType.LIMIT, "100.00", 2);
		PlacementResult rejected = place(buyer, Side.BUY, OrderType.LIMIT, "1000000.00", 1_000);
		PlacementResult resting = place(seller, Side.SELL, OrderType.LIMIT, "200.00", 1);

		assertThat(engine.cancel(key(), new CancelOrder(filled.orderId(), seller)).get().status())
			.isEqualTo(CancelResult.Status.NOT_CANCELABLE);
		assertThat(engine.cancel(key(), new CancelOrder(rejected.orderId(), buyer)).get().status())
			.isEqualTo(CancelResult.Status.NOT_CANCELABLE);
		assertThat(engine.cancel(key(), new CancelOrder(UUID.randomUUID(), buyer)).get().status())
			.isEqualTo(CancelResult.Status.NOT_FOUND);
		assertThat(engine.cancel(key(), new CancelOrder(resting.orderId(), buyer)).get().status())
			.as("someone else's order looks like no order at all")
			.isEqualTo(CancelResult.Status.NOT_FOUND);
		assertThat(orderState(resting.orderId())).isEqualTo("OPEN");
	}

	@Test
	void cancellingIsAllowedWhileTheMarketIsHalted() throws Exception {
		UUID seller = funded();
		PlacementResult sell = place(seller, Side.SELL, OrderType.LIMIT, "100.00", 5);
		jdbc.update("UPDATE simulation SET state = 'HALTED'");
		try {
			assertThat(engine.cancel(key(), new CancelOrder(sell.orderId(), seller)).get().status())
				.isEqualTo(CancelResult.Status.CANCELED);
			assertThat(shares(seller)).containsExactly(STARTING_SHARES, 0L);
		}
		finally {
			jdbc.update("UPDATE simulation SET state = 'OPEN'");
		}
	}

	@Test
	void aCancelFailingBeforeCommitLeavesTheOrderResting() throws Exception {
		UUID seller = funded();
		PlacementResult sell = place(seller, Side.SELL, OrderType.LIMIT, "100.00", 5);
		Map<String, Object> before = snapshot();

		failAt.set("touchAccounts");
		CompletableFuture<CancelResult> failed = engine.cancel(key(), new CancelOrder(sell.orderId(), seller));

		assertThatThrownBy(failed::get).isInstanceOf(ExecutionException.class)
			.rootCause()
			.hasMessageContaining("injected");
		assertThat(snapshot()).isEqualTo(before);
		assertThat(engine.depth(instrument, Side.SELL).get())
			.containsExactly(new DepthLevel(Price.parse("100.00"), 5, 1));

		failAt.set(null);
		assertThat(engine.cancel(key(), new CancelOrder(sell.orderId(), seller)).get().status())
			.isEqualTo(CancelResult.Status.CANCELED);
		assertThat(engine.depth(instrument, Side.SELL).get()).isEmpty();
		assertLedgerMatchesBalances();
	}

	/**
	 * Many concurrent clients. Afterwards the database must agree with itself and with the book: balances equal the
	 * ledger, every reservation equals the holds of open orders, nothing is created or lost, and the book equals the
	 * open orders in the database.
	 */
	@Test
	void concurrentOrdersKeepTheDatabaseAndBookConsistent() throws Exception {
		List<UUID> accounts = List.of(funded(), funded(), funded(), funded());
		List<CompletableFuture<PlacementResult>> results = new ArrayList<>();
		List<Thread> clients = new ArrayList<>();
		for (int c = 0; c < 4; c++) {
			long seed = c;
			clients.add(Thread.ofVirtual().start(() -> {
				Random random = new Random(seed);
				for (int i = 0; i < 50; i++) {
					Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
					boolean market = random.nextInt(5) == 0;
					long cents = 10_000 + random.nextLong(-10, 11) + (market ? (side == Side.BUY ? 15 : -15) : 0);
					synchronized (results) {
						results.add(engine.place(key(), new PlaceOrder(UUID.randomUUID(), accounts.get(random.nextInt(4)),
								instrument, side, market ? OrderType.MARKET_PROTECTED : OrderType.LIMIT, new Price(cents),
								new Quantity(random.nextLong(1, 31)))));
					}
				}
			}));
		}
		for (Thread client : clients) {
			client.join();
		}
		CompletableFuture.allOf(results.toArray(CompletableFuture[]::new)).get();

		assertThat(jdbc.queryForObject("SELECT count(*) FROM trades WHERE instrument_id = ?", Long.class, instrument))
			.as("the random orders actually traded")
			.isPositive();
		long cashTotal = 0;
		long sharesTotal = 0;
		for (UUID account : accounts) {
			cashTotal += cash(account).get(0);
			sharesTotal += shares(account).get(0);
			assertThat(cash(account).get(1)).as("cash hold of " + account)
				.isEqualTo(jdbc.queryForObject("""
						SELECT coalesce(sum(bound_cents * working_quantity), 0) FROM orders
						WHERE account_id = ? AND side = 'BUY' AND state IN ('OPEN', 'PARTIALLY_FILLED')""", Long.class,
						account));
			assertThat(shares(account).get(1)).as("share hold of " + account)
				.isEqualTo(jdbc.queryForObject("""
						SELECT coalesce(sum(working_quantity), 0) FROM orders
						WHERE account_id = ? AND side = 'SELL' AND state IN ('OPEN', 'PARTIALLY_FILLED')""", Long.class,
						account));
		}
		assertThat(cashTotal).isEqualTo(4 * STARTING_CASH);
		assertThat(sharesTotal).isEqualTo(4 * STARTING_SHARES);
		for (Side side : Side.values()) {
			assertThat(engine.depth(instrument, side).get()).as(side + " book equals the open orders")
				.isEqualTo(jdbc.query("""
						SELECT bound_cents, sum(working_quantity) AS quantity, count(*) AS orders FROM orders
						WHERE instrument_id = ? AND side = ? AND state IN ('OPEN', 'PARTIALLY_FILLED')
						GROUP BY bound_cents ORDER BY bound_cents""" + (side == Side.BUY ? " DESC" : " ASC"),
						(row, index) -> new DepthLevel(new Price(row.getLong("bound_cents")), row.getLong("quantity"),
								row.getInt("orders")),
						instrument, side.name()));
		}
		assertLedgerMatchesBalances();
	}

	/** Every customer balance equals the sum of its ledger postings, in total and in the reserved bucket. */
	private void assertLedgerMatchesBalances() {
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM cash_balance b
				WHERE b.total_cents <> (SELECT coalesce(sum(delta), 0) FROM ledger_entry e
				                        WHERE e.account_id = b.account_id AND e.instrument_id IS NULL)
				   OR b.reserved_cents <> (SELECT coalesce(sum(delta), 0) FROM ledger_entry e
				                           WHERE e.account_id = b.account_id AND e.instrument_id IS NULL
				                             AND e.bucket = 'RESERVED')""", Long.class))
			.as("cash balances that differ from the ledger")
			.isZero();
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM position p
				WHERE p.total_quantity <> (SELECT coalesce(sum(delta), 0) FROM ledger_entry e
				                           WHERE e.account_id = p.account_id AND e.instrument_id = p.instrument_id)
				   OR p.reserved_quantity <> (SELECT coalesce(sum(delta), 0) FROM ledger_entry e
				                              WHERE e.account_id = p.account_id AND e.instrument_id = p.instrument_id
				                                AND e.bucket = 'RESERVED')""", Long.class))
			.as("positions that differ from the ledger")
			.isZero();
	}

	private Map<String, Object> snapshot() {
		return jdbc.queryForMap("""
				SELECT (SELECT count(*) FROM orders) AS orders, (SELECT count(*) FROM trades) AS trades,
				    (SELECT count(*) FROM journal_batch) AS batches, (SELECT count(*) FROM ledger_entry) AS ledger_entries,
				    (SELECT count(*) FROM outbox) AS events,
				    (SELECT sum(total_cents) FROM cash_balance) AS cash, (SELECT sum(reserved_cents) FROM cash_balance) AS reserved_cash,
				    (SELECT sum(total_quantity) FROM position) AS shares, (SELECT sum(reserved_quantity) FROM position) AS reserved_shares,
				    (SELECT sum(version) FROM account) AS versions,
				    (SELECT last_command_sequence FROM simulation) AS command_sequence,
				    (SELECT sum(last_trade_sequence) FROM instrument) AS trade_sequences""");
	}

	/** A trader funded by a balanced genesis batch, in one transaction as the setup command will do. */
	private UUID funded() {
		return new TransactionTemplate(transactionManager).execute((status) -> fundInTransaction());
	}

	private UUID fundInTransaction() {
		UUID account = UUID.randomUUID();
		jdbc.update("INSERT INTO account (id, simulation_id, kind, display_name) VALUES (?, ?, 'BOT', 'Bot')", account,
				simulation);
		UUID batch = UUID.randomUUID();
		jdbc.update("INSERT INTO journal_batch (id, simulation_id, cause, command_sequence) VALUES (?, ?, 'GENESIS', 1)",
				batch, simulation);
		String post = "INSERT INTO ledger_entry (batch_id, account_id, instrument_id, bucket, delta) VALUES (?, ?, ?, 'AVAILABLE', ?)";
		jdbc.update(post, batch, issuance, null, -STARTING_CASH);
		jdbc.update(post, batch, account, null, STARTING_CASH);
		jdbc.update(post, batch, issuance, instrument, -STARTING_SHARES);
		jdbc.update(post, batch, account, instrument, STARTING_SHARES);
		jdbc.update("INSERT INTO cash_balance (account_id, account_kind, total_cents, reserved_cents) VALUES (?, 'BOT', ?, 0)",
				account, STARTING_CASH);
		jdbc.update("""
				INSERT INTO position (account_id, account_kind, instrument_id, total_quantity, reserved_quantity)
				VALUES (?, 'BOT', ?, ?, 0)""", account, instrument, STARTING_SHARES);
		return account;
	}

	/** The single public snapshot written by one command. */
	private MarketSnapshot marketSnapshot(long commandSequence) throws Exception {
		return WireEvents.decodeSnapshot(jdbc.queryForObject(
				"SELECT payload FROM outbox WHERE topic = 'mse.market.v1' AND command_sequence = ?", byte[].class,
				commandSequence));
	}

	private PlacementResult place(UUID account, Side side, OrderType type, String bound, long quantity)
			throws Exception {
		return engine.place(key(), order(account, side, type, bound, quantity)).get();
	}

	private PlaceOrder order(UUID account, Side side, OrderType type, String bound, long quantity) {
		return new PlaceOrder(UUID.randomUUID(), account, instrument, side, type, Price.parse(bound),
				new Quantity(quantity));
	}

	private List<Long> cash(UUID account) {
		return jdbc.queryForObject("SELECT total_cents, reserved_cents FROM cash_balance WHERE account_id = ?",
				(row, index) -> List.of(row.getLong(1), row.getLong(2)), account);
	}

	private List<Long> shares(UUID account) {
		return jdbc.queryForObject(
				"SELECT total_quantity, reserved_quantity FROM position WHERE account_id = ? AND instrument_id = ?",
				(row, index) -> List.of(row.getLong(1), row.getLong(2)), account, instrument);
	}

	private String orderState(UUID order) {
		return jdbc.queryForObject("SELECT state FROM orders WHERE id = ?", String.class, order);
	}

	private long commandSequence() {
		return jdbc.queryForObject("SELECT last_command_sequence FROM simulation", Long.class);
	}

	/** Fails the chosen store call; pick a command's last write to fail after everything else but before the commit. */
	private TradingStore failingBeforeCommit(TradingStore store) {
		return (TradingStore) Proxy.newProxyInstance(TradingStore.class.getClassLoader(),
				new Class<?>[] { TradingStore.class }, (proxy, method, args) -> {
					if (method.getName().equals(failAt.get())) {
						throw new IllegalStateException("injected failure before commit");
					}
					try {
						return method.invoke(store, args);
					}
					catch (InvocationTargetException ex) {
						throw ex.getCause();
					}
				});
	}

	/** A restart rebuilds every book exactly, including queue order, and claims a new writer epoch. */
	@Test
	void aRestartRebuildsTheBookExactly() throws Exception {
		UUID seller = funded();
		UUID buyer = funded();
		PlacementResult earliest = place(seller, Side.SELL, OrderType.LIMIT, "100.00", 3);
		place(seller, Side.SELL, OrderType.LIMIT, "100.00", 4);
		place(seller, Side.SELL, OrderType.LIMIT, "101.00", 5);
		place(buyer, Side.BUY, OrderType.LIMIT, "99.00", 2);
		place(buyer, Side.BUY, OrderType.LIMIT, "98.00", 1);
		List<DepthLevel> asks = engine.depth(instrument, Side.SELL).get();
		List<DepthLevel> bids = engine.depth(instrument, Side.BUY).get();
		long epoch = jdbc.queryForObject("SELECT writer_epoch FROM simulation", Long.class);

		restartEngine();

		assertThat(jdbc.queryForObject("SELECT writer_epoch FROM simulation", Long.class)).isEqualTo(epoch + 1);
		assertThat(engine.depth(instrument, Side.SELL).get()).isEqualTo(asks);
		assertThat(engine.depth(instrument, Side.BUY).get()).isEqualTo(bids);
		PlacementResult taker = place(buyer, Side.BUY, OrderType.LIMIT, "100.00", 3);
		assertThat(jdbc.queryForObject("SELECT maker_order_id FROM trades WHERE taker_order_id = ?", UUID.class,
				taker.orderId()))
			.as("the earliest order at the best price still has priority")
			.isEqualTo(earliest.orderId());
		assertLedgerMatchesBalances();
	}

	/** Once another writer claims the market, this one's next command is refused and it halts. */
	@Test
	void aStaleWriterIsFencedOff() throws Exception {
		UUID seller = funded();
		place(seller, Side.SELL, OrderType.LIMIT, "100.00", 3);
		jdbc.update("UPDATE simulation SET writer_epoch = writer_epoch + 1");
		Map<String, Object> before = snapshot();

		CompletableFuture<PlacementResult> fenced = engine.place(key(), order(seller, Side.SELL, OrderType.LIMIT, "100.00", 1));

		assertThatThrownBy(fenced::get).hasCauseInstanceOf(WriterHaltException.class);
		assertThat(writer.isAvailable()).isFalse();
		Map<String, Object> after = snapshot();
		assertThat(after).isEqualTo(before);
	}

	@Test
	void aBalanceThatDisagreesWithTheLedgerStopsStartup() throws Exception {
		UUID trader = funded();
		jdbc.update("UPDATE cash_balance SET total_cents = total_cents + 1 WHERE account_id = ?", trader);
		try {
			assertThatThrownBy(this::restartEngine).isInstanceOf(ReconciliationException.class)
				.hasMessageContaining(trader.toString())
				.hasMessageContaining("the ledger says");
		}
		finally {
			jdbc.update("UPDATE cash_balance SET total_cents = total_cents - 1 WHERE account_id = ?", trader);
		}
	}

	@Test
	void aReservationThatDisagreesWithOpenOrdersStopsStartup() throws Exception {
		UUID seller = funded();
		PlacementResult sell = place(seller, Side.SELL, OrderType.LIMIT, "100.00", 5);
		jdbc.update("UPDATE orders SET working_quantity = 4, canceled_quantity = 1 WHERE id = ?", sell.orderId());
		try {
			assertThatThrownBy(this::restartEngine).isInstanceOf(ReconciliationException.class)
				.hasMessageContaining("open orders hold 4");
		}
		finally {
			jdbc.update("UPDATE orders SET working_quantity = 5, canceled_quantity = 0 WHERE id = ?", sell.orderId());
		}
	}

	/** Simulates a process restart: the old writer stops, a new engine starts from the database. */
	private void restartEngine() throws InterruptedException {
		writer.close(Duration.ofSeconds(5));
		writer = new CommandWriter("test-writer", 1_000);
		engine = new TradingEngine(failingBeforeCommit(store), new SpringTransactionRunner(transactionManager), writer,
				Clock.systemUTC());
		engine.start();
	}

	/** A fresh idempotency key for a command whose repeat behaviour the test does not care about. */
	private static CommandKey key() {
		return new CommandKey(UUID.randomUUID(), UUID.randomUUID(), "test", new byte[32]);
	}

	private static CommandKey key(UUID principal, UUID idempotencyKey, String request) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256")
				.digest(request.getBytes(StandardCharsets.UTF_8));
			return new CommandKey(principal, idempotencyKey, "POST /api/v1/orders", hash);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	@Test
	void aRepeatedKeyReturnsTheFirstResultWithoutDoingAnythingAgain() throws Exception {
		UUID seller = funded();
		UUID buyer = funded();
		place(seller, Side.SELL, OrderType.LIMIT, "100.00", 10);
		CommandKey key = key(buyer, UUID.randomUUID(), "buy 3 at 100.00");
		PlaceOrder order = order(buyer, Side.BUY, OrderType.LIMIT, "100.00", 3);
		PlacementResult first = engine.place(key, order).get();
		Map<String, Object> before = snapshot();

		PlacementResult again = engine.place(key, order).get();

		assertThat(again).isEqualTo(first);
		assertThat(snapshot()).as("no new sequence, order, trade or ledger row").isEqualTo(before);
		assertThat(cash(buyer)).containsExactly(STARTING_CASH - 30_000, 0L);
	}

	@Test
	void concurrentRepeatsOfOneKeyProduceOneResult() throws Exception {
		UUID seller = funded();
		UUID buyer = funded();
		place(seller, Side.SELL, OrderType.LIMIT, "100.00", 100);
		CommandKey key = key(buyer, UUID.randomUUID(), "buy 1 at 100.00");
		PlaceOrder order = order(buyer, Side.BUY, OrderType.LIMIT, "100.00", 1);
		List<CompletableFuture<PlacementResult>> attempts = new ArrayList<>();
		List<Thread> clients = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			clients.add(Thread.ofVirtual().start(() -> {
				synchronized (attempts) {
					attempts.add(engine.place(key, order));
				}
			}));
		}
		for (Thread client : clients) {
			client.join();
		}

		List<PlacementResult> results = new ArrayList<>();
		for (CompletableFuture<PlacementResult> attempt : attempts) {
			results.add(attempt.get());
		}

		assertThat(results).hasSize(20).allSatisfy((result) -> assertThat(result).isEqualTo(results.get(0)));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE id = ?", Long.class, order.orderId()))
			.isEqualTo(1);
		assertThat(shares(buyer)).as("bought exactly once").containsExactly(STARTING_SHARES + 1, 0L);
	}

	@Test
	void aKeyReusedForADifferentRequestIsAConflictThatChangesNothing() throws Exception {
		UUID buyer = funded();
		UUID idempotencyKey = UUID.randomUUID();
		engine.place(key(buyer, idempotencyKey, "buy 1 at 10.00"), order(buyer, Side.BUY, OrderType.LIMIT, "10.00", 1))
			.get();
		Map<String, Object> before = snapshot();

		CompletableFuture<PlacementResult> conflict = engine.place(key(buyer, idempotencyKey, "buy 2 at 10.00"),
				order(buyer, Side.BUY, OrderType.LIMIT, "10.00", 2));

		assertThatThrownBy(conflict::get).hasCauseInstanceOf(IdempotencyConflictException.class);
		assertThat(snapshot()).isEqualTo(before);
	}

	@Test
	void theSameKeyFromAnotherPrincipalIsANewCommand() throws Exception {
		UUID first = funded();
		UUID second = funded();
		UUID shared = UUID.randomUUID();

		PlacementResult a = engine.place(key(first, shared, "sell 1"), order(first, Side.SELL, OrderType.LIMIT, "500.00", 1))
			.get();
		PlacementResult b = engine
			.place(key(second, shared, "sell 1"), order(second, Side.SELL, OrderType.LIMIT, "500.00", 1))
			.get();

		assertThat(b.orderId()).isNotEqualTo(a.orderId());
		assertThat(b.commandSequence()).isGreaterThan(a.commandSequence());
	}

	@Test
	void aFailedAttemptStoresNoResultSoTheSameKeyCanBeRetried() throws Exception {
		UUID seller = funded();
		UUID buyer = funded();
		place(seller, Side.SELL, OrderType.LIMIT, "100.00", 5);
		CommandKey key = key(buyer, UUID.randomUUID(), "buy 2 at 100.00");
		PlaceOrder order = order(buyer, Side.BUY, OrderType.LIMIT, "100.00", 2);

		failAt.set("insertTradeEvent");
		assertThatThrownBy(() -> engine.place(key, order).get()).hasRootCauseMessage("injected failure before commit");
		failAt.set(null);

		assertThat(engine.place(key, order).get().state()).isEqualTo(OrderState.FILLED);
		assertThat(shares(buyer)).containsExactly(STARTING_SHARES + 2, 0L);
	}

	@Test
	void cancelRepeatsAreIdempotentToo() throws Exception {
		UUID seller = funded();
		PlacementResult sell = place(seller, Side.SELL, OrderType.LIMIT, "100.00", 5);
		CommandKey key = new CommandKey(seller, UUID.randomUUID(), "POST /api/v1/orders/{id}/cancel", new byte[32]);

		CancelResult first = engine.cancel(key, new CancelOrder(sell.orderId(), seller)).get();
		CancelResult again = engine.cancel(key, new CancelOrder(sell.orderId(), seller)).get();

		assertThat(first.status()).isEqualTo(CancelResult.Status.CANCELED);
		assertThat(again).as("the original CANCELED, not ALREADY_CANCELED").isEqualTo(first);
	}

	private static String symbol(int number) {
		StringBuilder symbol = new StringBuilder("T");
		for (int n = number; n > 0; n /= 26) {
			symbol.append((char) ('A' + n % 26));
		}
		return symbol.toString();
	}

}
