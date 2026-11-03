package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mia_princz.mse.contracts.events.TradeExecuted;
import com.mia_princz.mse.contracts.events.WireEvents;
import com.mia_princz.mse.execution.trading.adapter.JdbcTradingStore;
import com.mia_princz.mse.execution.trading.adapter.SpringTransactionRunner;
import com.mia_princz.mse.execution.trading.application.CancelOrder;
import com.mia_princz.mse.execution.trading.application.CancelResult;
import com.mia_princz.mse.execution.trading.application.CommandWriter;
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

		TradingStore store = new JdbcTradingStore(JdbcClient.create(dataSource));
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
				"SELECT payload FROM outbox WHERE command_sequence = ? ORDER BY id", byte[].class, buy.commandSequence());
		assertThat(events).hasSize(3);
		TradeExecuted last = WireEvents.decodeTrade(events.get(2));
		assertThat(last.getInstrumentId()).isEqualTo(instrument);
		assertThat(last.getTradeSequence()).isEqualTo(3);
		assertThat(last.getPriceCents()).isEqualTo(10_100);
		assertThat(last.getQuantity()).isEqualTo(3);
		assertLedgerMatchesBalances();
	}

	/** A database failure just before commit: nothing in the database changes, the book is untouched, work goes on. */
	@Test
	void aFailureBeforeCommitLeavesNoTrace() throws Exception {
		UUID seller = funded();
		UUID buyer = funded();
		place(seller, Side.SELL, OrderType.LIMIT, "100.00", 5);
		Map<String, Object> before = snapshot();
		List<DepthLevel> bookBefore = engine.depth(instrument, Side.SELL).get();

		failAt.set("insertTradeEvent");
		CompletableFuture<PlacementResult> failed = engine.place(order(buyer, Side.BUY, OrderType.LIMIT, "100.00", 3));

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

		CancelResult cancel = engine.cancel(new CancelOrder(buy.orderId(), buyer)).get();

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
		engine.cancel(new CancelOrder(sell.orderId(), seller)).get();
		Map<String, Object> before = snapshot();

		CancelResult again = engine.cancel(new CancelOrder(sell.orderId(), seller)).get();

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

		assertThat(engine.cancel(new CancelOrder(filled.orderId(), seller)).get().status())
			.isEqualTo(CancelResult.Status.NOT_CANCELABLE);
		assertThat(engine.cancel(new CancelOrder(rejected.orderId(), buyer)).get().status())
			.isEqualTo(CancelResult.Status.NOT_CANCELABLE);
		assertThat(engine.cancel(new CancelOrder(UUID.randomUUID(), buyer)).get().status())
			.isEqualTo(CancelResult.Status.NOT_FOUND);
		assertThat(engine.cancel(new CancelOrder(resting.orderId(), buyer)).get().status())
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
			assertThat(engine.cancel(new CancelOrder(sell.orderId(), seller)).get().status())
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
		CompletableFuture<CancelResult> failed = engine.cancel(new CancelOrder(sell.orderId(), seller));

		assertThatThrownBy(failed::get).isInstanceOf(ExecutionException.class)
			.rootCause()
			.hasMessageContaining("injected");
		assertThat(snapshot()).isEqualTo(before);
		assertThat(engine.depth(instrument, Side.SELL).get())
			.containsExactly(new DepthLevel(Price.parse("100.00"), 5, 1));

		failAt.set(null);
		assertThat(engine.cancel(new CancelOrder(sell.orderId(), seller)).get().status())
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
						results.add(engine.place(new PlaceOrder(UUID.randomUUID(), accounts.get(random.nextInt(4)),
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

	private PlacementResult place(UUID account, Side side, OrderType type, String bound, long quantity)
			throws Exception {
		return engine.place(order(account, side, type, bound, quantity)).get();
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

	private static String symbol(int number) {
		StringBuilder symbol = new StringBuilder("T");
		for (int n = number; n > 0; n /= 26) {
			symbol.append((char) ('A' + n % 26));
		}
		return symbol.toString();
	}

}
