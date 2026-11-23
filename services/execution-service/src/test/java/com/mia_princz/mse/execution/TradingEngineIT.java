package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.testcontainers.postgresql.PostgreSQLContainer;
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
import com.mia_princz.mse.execution.trading.application.AddInstrument;
import com.mia_princz.mse.execution.trading.application.EditInstrument;
import com.mia_princz.mse.execution.trading.application.MarketControl;
import com.mia_princz.mse.execution.trading.application.MarketTransition;
import com.mia_princz.mse.execution.trading.application.InstrumentResult;
import com.mia_princz.mse.execution.trading.application.WriteSet;
import com.mia_princz.mse.execution.trading.application.WriterTimings;
import com.mia_princz.mse.execution.trading.application.AccountChanges;
import com.mia_princz.mse.execution.trading.application.CancelOrder;
import com.mia_princz.mse.execution.trading.application.CancelResult;
import com.mia_princz.mse.execution.trading.application.CommandKey;
import com.mia_princz.mse.execution.trading.application.CommandWriter;
import com.mia_princz.mse.execution.trading.application.CommandWriter.WriterHaltException;
import com.mia_princz.mse.execution.trading.application.CommandWriter.WriterStoppedException;
import com.mia_princz.mse.execution.trading.application.ReconciliationException;
import com.mia_princz.mse.execution.trading.application.IdempotencyConflictException;
import com.mia_princz.mse.execution.trading.application.PlaceOrder;
import com.mia_princz.mse.execution.trading.application.PlacementResult;
import com.mia_princz.mse.execution.trading.application.PlacementResult.OrderState;
import com.mia_princz.mse.execution.trading.application.TradingEngine;
import com.mia_princz.mse.execution.trading.application.TradingStore;
import com.mia_princz.mse.execution.trading.application.TransactionRunner.CommitOutcomeUnknownException;
import com.mia_princz.mse.execution.trading.application.TransactionRunner;
import com.mia_princz.mse.execution.trading.domain.DepthLevel;
import com.mia_princz.mse.execution.trading.domain.DisplayName;
import com.mia_princz.mse.execution.trading.domain.OrderType;
import com.mia_princz.mse.execution.trading.domain.Price;
import com.mia_princz.mse.execution.trading.domain.Quantity;
import com.mia_princz.mse.execution.trading.domain.RejectionCode;
import com.mia_princz.mse.execution.trading.domain.RestingOrders;
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

	@Autowired
	PostgreSQLContainer postgres;

	/** What the writer timed, as it recorded it. */
	private final List<String> timed = java.util.Collections.synchronizedList(new ArrayList<>());

	private final WriterTimings timings = new WriterTimings() {

		@Override
		public void record(Phase phase, String command, long nanos) {
			timed.add(phase + " " + command + " " + nanos);
		}

		@Override
		public void runFellBack(int commands) {
			timed.add("FALLBACK run " + commands);
		}

	};

	/** When set, the next transaction commits and its outcome is then lost, as if the process died right after. */
	private final AtomicBoolean loseAnswer = new AtomicBoolean();

	/** The store method that throws, simulating a failure after every earlier write but before the commit. */
	private final AtomicReference<String> failAt = new AtomicReference<>();

	/**
	 * When set, the books' count of resting orders goes wrong on this call: "added" or "removed" throw an Error, and
	 * "miscount" counts nothing.
	 */
	private final AtomicReference<String> failBookAt = new AtomicReference<>();

	/** The books' count of each account's resting orders, as the current engine keeps it. */
	private FailingRestingOrders resting;

	private CommandWriter writer;

	private TradingStore store;

	private TradingEngine engine;

	private UUID simulation;

	private UUID issuance;

	private UUID instrument;

	/** The writer's run limit for this class's engines; a subclass runs every scenario with runs of up to 32. */
	int maxRun() {
		return 1;
	}

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
		writer = new CommandWriter("test-writer", 1_000, maxRun());
		resting = new FailingRestingOrders();
		engine = new TradingEngine(failingBeforeCommit(store), losingAnswers(new SpringTransactionRunner(transactionManager)),
				writer,
				Clock.systemUTC(), (account) -> {
					throw new UnsupportedOperationException("no bots in these tests");
				}, AccountChanges.NONE, timings, System::nanoTime, resting);
		engine.start();
	}

	/**
	 * A connection lost during COMMIT leaves the command's outcome unknown: it may have committed. The writer must halt,
	 * not answer it as refused, or the book would miss a command the database may hold. Here a test-only deferred
	 * trigger holds the commit for an order of 7,777 shares while an administrator ends the connection.
	 */
	@Test
	void aConnectionLostDuringCommitHaltsTheWriter() throws Exception {
		UUID buyer = funded();
		ddl("""
				CREATE FUNCTION test_slow_commit() RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN
				    IF NEW.requested_quantity = 7777 THEN PERFORM pg_sleep(30); END IF;
				    RETURN NULL;
				END $$;
				CREATE CONSTRAINT TRIGGER test_slow_commit AFTER INSERT ON orders DEFERRABLE INITIALLY DEFERRED
				    FOR EACH ROW EXECUTE FUNCTION test_slow_commit();""");
		try {
			PlaceOrder order = order(buyer, Side.BUY, OrderType.LIMIT, "1.00", 7_777);
			CompletableFuture<PlacementResult> lost = engine.place(key(), order);
			assertThat(terminateTheSleepingCommit()).as("the commit was found waiting and its connection ended").isTrue();

			assertThatThrownBy(lost::get).hasCauseInstanceOf(WriterHaltException.class);
			assertThat(writer.isAvailable()).as("the writer stops").isFalse();
		}
		finally {
			ddl("DROP TRIGGER test_slow_commit ON orders; DROP FUNCTION test_slow_commit();");
			restartEngine();
		}
	}

	/**
	 * A deferred constraint that fails at COMMIT is a definite rollback, not an unknown outcome: the command fails, and
	 * the writer goes on. The ledger's balance check works this way; here a test-only trigger refuses an order of 6,666
	 * shares at commit with the same error class.
	 */
	@Test
	void aConstraintFailingAtCommitRollsBackOnlyThatCommand() throws Exception {
		UUID buyer = funded();
		ddl("""
				CREATE FUNCTION test_refused_commit() RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN
				    IF NEW.requested_quantity = 6666 THEN
				        RAISE EXCEPTION 'refused at commit' USING ERRCODE = 'check_violation';
				    END IF;
				    RETURN NULL;
				END $$;
				CREATE CONSTRAINT TRIGGER test_refused_commit AFTER INSERT ON orders DEFERRABLE INITIALLY DEFERRED
				    FOR EACH ROW EXECUTE FUNCTION test_refused_commit();""");
		try {
			PlaceOrder refused = order(buyer, Side.BUY, OrderType.LIMIT, "1.00", 6_666);
			CompletableFuture<PlacementResult> answer = engine.place(key(), refused);

			assertThatThrownBy(answer::get).cause().isNotInstanceOf(WriterHaltException.class);
			assertThat(writer.isAvailable()).as("the writer goes on").isTrue();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE id = ?", Long.class, refused.orderId()))
				.as("nothing committed")
				.isZero();
			assertThat(place(buyer, Side.BUY, OrderType.LIMIT, "1.00", 5).rejection()).isNull();
		}
		finally {
			ddl("DROP TRIGGER test_refused_commit ON orders; DROP FUNCTION test_refused_commit();");
		}
	}

	/**
	 * The writer times each part of a command, so a command's time can be split: the queue, opening the transaction,
	 * its statements, its commit and applying it. A test-only trigger holds the commit of an order of 5,555 shares for
	 * 300 ms, which must show as commit time and not as statements; a commit the database refuses records no commit.
	 */
	@Test
	void theWriterTimesEachPartOfACommand() throws Exception {
		UUID buyer = funded();
		ddl("""
				CREATE FUNCTION test_timed_commit() RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN
				    IF NEW.requested_quantity = 5555 THEN PERFORM pg_sleep(0.3); END IF;
				    IF NEW.requested_quantity = 4444 THEN
				        RAISE EXCEPTION 'refused at commit' USING ERRCODE = 'check_violation';
				    END IF;
				    RETURN NULL;
				END $$;
				CREATE CONSTRAINT TRIGGER test_timed_commit AFTER INSERT ON orders DEFERRABLE INITIALLY DEFERRED
				    FOR EACH ROW EXECUTE FUNCTION test_timed_commit();""");
		try {
			timed.clear();
			PlacementResult resting = place(buyer, Side.BUY, OrderType.LIMIT, "1.00", 5_555);
			assertThat(phases("place")).containsExactly("QUEUE_WAIT", "BEGIN", "WORK", "COMMIT", "APPLY");
			assertThat(nanos("COMMIT place")).as("the held commit").isGreaterThanOrEqualTo(300_000_000L);
			assertThat(nanos("WORK place")).as("the statements, without the commit").isLessThan(300_000_000L);

			timed.clear();
			engine.cancel(key(), new CancelOrder(resting.orderId(), buyer)).get();
			assertThat(phases("cancel")).containsExactly("QUEUE_WAIT", "BEGIN", "WORK", "COMMIT", "APPLY");

			timed.clear();
			assertThatThrownBy(() -> place(buyer, Side.BUY, OrderType.LIMIT, "1.00", 4_444)).isNotNull();
			assertThat(phases("place")).as("a transaction refused at commit records none of its parts")
				.containsExactly("QUEUE_WAIT");
		}
		finally {
			ddl("DROP TRIGGER test_timed_commit ON orders; DROP FUNCTION test_timed_commit();");
		}
	}

	/**
	 * The statements each outcome sends, pinned: the round trips are most of a command's time (see the benchmarks), so a
	 * change in these counts is a change in throughput, made on purpose or found here. Counts are statement executions
	 * and commits on the writer's connection.
	 */
	@Test
	void eachOutcomeSendsAKnownNumberOfStatements() throws Exception {
		UUID seller = funded();
		UUID buyer = funded();
		Statements statements = new Statements(dataSource);
		int perAccount = jdbc.queryForObject("SELECT max_open_orders_per_account FROM simulation", Integer.class);
		countingEngine(statements);
		try {
			Map<String, String> counts = new java.util.LinkedHashMap<>();
			PlacementResult resting = statements.count(counts, "resting order",
					() -> place(seller, Side.SELL, OrderType.LIMIT, "100.00", 3));
			statements.count(counts, "one fill", () -> place(buyer, Side.BUY, OrderType.LIMIT, "100.00", 1));
			statements.count(counts, "cancel", () -> engine.cancel(key(), new CancelOrder(resting.orderId(), seller)).get());
			// With the resting order canceled, these three asks are the best and the buy sweeps them.
			for (String price : List.of("100.10", "100.20", "100.30")) {
				place(seller, Side.SELL, OrderType.LIMIT, price, 1);
			}
			statements.count(counts, "three fills", () -> place(buyer, Side.BUY, OrderType.LIMIT, "100.30", 3));
			statements.count(counts, "cancel of an unknown order",
					() -> engine.cancel(key(), new CancelOrder(UUID.randomUUID(), seller)).get());
			statements.count(counts, "insufficient cash",
					() -> place(buyer, Side.BUY, OrderType.LIMIT, "1000000.00", 1_000));
			CommandKey again = key();
			PlaceOrder repeated = order(seller, Side.SELL, OrderType.LIMIT, "200.00", 1);
			engine.place(again, repeated).get();
			statements.count(counts, "a repeated key", () -> engine.place(again, repeated).get());
			byte[] otherRequest = new byte[32];
			otherRequest[0] = 1;
			CommandKey conflicting = new CommandKey(again.principalId(), again.idempotencyKey(), again.route(),
					otherRequest);
			statements.count(counts, "a conflicting key", () -> {
				assertThatThrownBy(() -> engine.place(conflicting, order(seller, Side.SELL, OrderType.LIMIT, "200.00", 2))
					.get()).hasCauseInstanceOf(IdempotencyConflictException.class);
				return null;
			});
			asOwner("UPDATE simulation SET max_open_orders_per_account = 1");
			try {
				statements.count(counts, "order limit reached",
						() -> place(seller, Side.SELL, OrderType.LIMIT, "201.00", 1));
			}
			finally {
				asOwner("UPDATE simulation SET max_open_orders_per_account = ?", perAccount);
			}
			jdbc.update("UPDATE simulation SET state = 'HALTED'");
			try {
				statements.count(counts, "market not open", () -> place(seller, Side.SELL, OrderType.LIMIT, "10.00", 1));
			}
			finally {
				jdbc.update("UPDATE simulation SET state = 'OPEN'");
			}
			// Each command opens with one statement: the lock, the epoch check, the key's lookup and its sequence. An
			// order reads cash and shares in one more, and the writer counts each account's resting orders itself. A
			// snapshot reads nothing, and trades are numbered in memory, the counter written once per command. The
			// rest of a command's writes go in one statement per kind of row, whatever the number of fills. A cancel of
			// an order resting in the book updates it without reading it first. A repeated or conflicting key commits
			// its opening, which took no sequence.
			assertThat(counts).containsExactly(Map.entry("resting order", "8 statements, 1 commit"),
					Map.entry("one fill", "11 statements, 1 commit"), Map.entry("cancel", "7 statements, 1 commit"),
					Map.entry("three fills", "11 statements, 1 commit"),
					Map.entry("cancel of an unknown order", "3 statements, 1 commit"),
					Map.entry("insufficient cash", "4 statements, 1 commit"),
					Map.entry("a repeated key", "1 statements, 1 commit"),
					Map.entry("a conflicting key", "1 statements, 1 commit"),
					Map.entry("order limit reached", "4 statements, 1 commit"),
					Map.entry("market not open", "3 statements, 1 commit"));
		}
		finally {
			restartEngine();
		}
	}

	/**
	 * A run's statements do not grow with its commands: eight queued together (six orders that fill against one
	 * resting order, one that rests, and a cancel of what is left) send each kind of row once, as one order with a
	 * fill does, plus the cancel's row.
	 */
	@Test
	void aRunSendsEachKindOfRowOnce() throws Exception {
		UUID seller = funded();
		UUID buyer = funded();
		Statements statements = new Statements(dataSource);
		countingEngine(statements, 8);
		try {
			PlacementResult resting = place(seller, Side.SELL, OrderType.LIMIT, "100.00", 10);
			Map<String, String> counts = new java.util.LinkedHashMap<>();
			List<CompletableFuture<?>> answers = new ArrayList<>();
			java.util.concurrent.CountDownLatch release = hold();
			statements.count(counts, "a run of eight", () -> {
				for (int i = 0; i < 6; i++) {
					answers.add(engine.place(key(), order(buyer, Side.BUY, OrderType.LIMIT, "100.00", 1)));
				}
				answers.add(engine.place(key(), order(seller, Side.SELL, OrderType.LIMIT, "101.00", 1)));
				answers.add(engine.cancel(key(), new CancelOrder(resting.orderId(), seller)));
				release.countDown();
				for (CompletableFuture<?> answer : answers) {
					answer.get();
				}
				return null;
			});
			assertThat(((CancelResult) answers.getLast().get()).canceledQuantity()).isEqualTo(4);
			// The opening, the balances read once, the trade counter, then one statement each for orders, fills,
			// cancels, trades, journal, balances, account versions, outbox and answers.
			assertThat(counts).containsExactly(Map.entry("a run of eight", "12 statements, 1 commit"));
		}
		finally {
			restartEngine();
		}
	}

	/**
	 * The writer reads its market's accounts when it starts, so an account made after that (every account of a market
	 * set up since the last restart) is not among them. Its first order looks it up, one statement outside the
	 * transaction; it is then remembered, and its next order sends what any resting order sends.
	 */
	@Test
	void anAccountMadeAfterStartupIsLookedUpOnce() throws Exception {
		Statements statements = new Statements(dataSource);
		countingEngine(statements);
		try {
			UUID late = funded();
			Map<String, String> counts = new java.util.LinkedHashMap<>();
			statements.count(counts, "its first order", () -> place(late, Side.SELL, OrderType.LIMIT, "100.00", 1));
			statements.count(counts, "its second order", () -> place(late, Side.SELL, OrderType.LIMIT, "100.10", 1));
			assertThat(counts).containsExactly(Map.entry("its first order", "9 statements, 1 commit"),
					Map.entry("its second order", "8 statements, 1 commit"));
		}
		finally {
			restartEngine();
		}
	}

	/** Replaces the engine with one whose connections count what they send. */
	private void countingEngine(Statements statements) throws InterruptedException {
		countingEngine(statements, 1);
	}

	private void countingEngine(Statements statements, int maxRun) throws InterruptedException {
		writer.close(Duration.ofSeconds(5));
		writer = new CommandWriter("counted-writer", 1_000, maxRun);
		DataSource counted = statements.dataSource();
		engine = new TradingEngine(new JdbcTradingStore(JdbcClient.create(counted), JsonMapper.builder().build()),
				new SpringTransactionRunner(new org.springframework.jdbc.support.JdbcTransactionManager(counted)), writer,
				Clock.systemUTC(), (account) -> {
					throw new UnsupportedOperationException("no bots in these tests");
				});
		engine.start();
	}

	/** Counts statement executions and commits on connections from a data source. */
	private static final class Statements {

		private final DataSource target;

		private final java.util.concurrent.atomic.AtomicInteger executed = new java.util.concurrent.atomic.AtomicInteger();

		private final java.util.concurrent.atomic.AtomicInteger commits = new java.util.concurrent.atomic.AtomicInteger();

		Statements(DataSource target) {
			this.target = target;
		}

		<T> T count(Map<String, String> counts, String outcome, java.util.concurrent.Callable<T> command) throws Exception {
			executed.set(0);
			commits.set(0);
			T result = command.call();
			counts.put(outcome, executed.get() + " statements, " + commits.get() + " commit");
			return result;
		}

		DataSource dataSource() {
			return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { DataSource.class },
					(proxy, method, args) -> {
						switch (method.getName()) {
							case "getConnection":
								return connection((Connection) invoke(method, target, args));
							case "hashCode":
								return System.identityHashCode(proxy);
							case "equals":
								return proxy == args[0];
							default:
								return invoke(method, target, args);
						}
					});
		}

		private Connection connection(Connection real) {
			return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Connection.class },
					(proxy, method, args) -> {
						if (method.getName().equals("commit")) {
							commits.incrementAndGet();
						}
						Object answer = invoke(method, real, args);
						if (answer instanceof java.sql.Statement statement
								&& method.getName().matches("prepareStatement|createStatement|prepareCall")) {
							return statement(statement);
						}
						return answer;
					});
		}

		private Object statement(java.sql.Statement real) {
			Class<?> type = real instanceof java.sql.CallableStatement ? java.sql.CallableStatement.class
					: real instanceof java.sql.PreparedStatement ? java.sql.PreparedStatement.class
							: java.sql.Statement.class;
			return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
				if (method.getName().startsWith("execute")) {
					executed.incrementAndGet();
				}
				return invoke(method, real, args);
			});
		}

		private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
			try {
				return method.invoke(target, args);
			}
			catch (java.lang.reflect.InvocationTargetException ex) {
				throw ex.getCause();
			}
		}

	}

	private List<String> phases(String command) {
		synchronized (timed) {
			return timed.stream()
				.map((entry) -> entry.split(" "))
				.filter((parts) -> parts[1].equals(command))
				.map((parts) -> parts[0])
				.toList();
		}
	}

	private long nanos(String phaseAndCommand) {
		synchronized (timed) {
			return timed.stream()
				.filter((entry) -> entry.startsWith(phaseAndCommand + " "))
				.mapToLong((entry) -> Long.parseLong(entry.substring(entry.lastIndexOf(' ') + 1)))
				.findFirst()
				.orElseThrow();
		}
	}

	/** Schema changes for one test, as the owner. */
	private void ddl(String sql) throws Exception {
		String url = TestcontainersConfiguration.jdbcUrl(postgres, "execution_db");
		try (Connection owner = DriverManager.getConnection(url, "execution_owner",
				TestcontainersConfiguration.EXECUTION_OWNER_PASSWORD)) {
			owner.createStatement().execute(sql);
		}
	}

	/** Ends the connection whose commit is held in {@code pg_sleep}, as an administrator; false if none appears. */
	private boolean terminateTheSleepingCommit() throws Exception {
		try (Connection admin = DriverManager.getConnection(TestcontainersConfiguration.jdbcUrl(postgres, "postgres"),
				postgres.getUsername(), postgres.getPassword())) {
			for (int attempt = 0; attempt < 200; attempt++) {
				var found = admin.createStatement().executeQuery("""
						SELECT pg_terminate_backend(pid) FROM pg_stat_activity
						WHERE datname = 'execution_db' AND wait_event = 'PgSleep'""");
				if (found.next() && found.getBoolean(1)) {
					return true;
				}
				Thread.sleep(25);
			}
			return false;
		}
	}

	/** The market's limits are the owner's to change; the runtime role may not. */
	private void asOwner(String sql, Object... parameters) throws Exception {
		String url = TestcontainersConfiguration.jdbcUrl(postgres, "execution_db");
		try (Connection owner = DriverManager.getConnection(url, "execution_owner",
				TestcontainersConfiguration.EXECUTION_OWNER_PASSWORD);
				PreparedStatement statement = owner.prepareStatement(sql)) {
			for (int i = 0; i < parameters.length; i++) {
				statement.setObject(i + 1, parameters[i]);
			}
			assertThat(statement.executeUpdate()).isEqualTo(1);
		}
	}

	@AfterEach
	void stop() throws InterruptedException {
		writer.close(Duration.ofSeconds(5));
	}

	/**
	 * Trading commands queued together reach the engine as one run, and each gets the result it would get alone, in
	 * admission order: an order rests, another fills against it, a cancel takes what is left, and a fourth rests. A
	 * command of the run that fails fails alone.
	 */
	@Test
	void tradingCommandsQueuedTogetherRunAsOneRunWithTheirOwnResults() throws Exception {
		writer.close(Duration.ofSeconds(5));
		List<Integer> runs = new java.util.concurrent.CopyOnWriteArrayList<>();
		writer = new CommandWriter("test-writer", 1_000, 8, runs::add);
		resting = new FailingRestingOrders();
		engine = new TradingEngine(store, new SpringTransactionRunner(transactionManager), writer, Clock.systemUTC(),
				(account) -> {
					throw new UnsupportedOperationException("no bots in these tests");
				}, AccountChanges.NONE, timings, System::nanoTime, resting);
		engine.start();
		UUID buyer = funded();
		UUID seller = funded();
		long before = commandSequence();
		java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
		writer.submit(() -> release.await(5, java.util.concurrent.TimeUnit.SECONDS));
		PlaceOrder bid = order(buyer, Side.BUY, OrderType.LIMIT, "100.00", 5);
		CompletableFuture<PlacementResult> rests = engine.place(key(), bid);
		CompletableFuture<PlacementResult> fills = engine.place(key(), order(seller, Side.SELL, OrderType.LIMIT, "100.00", 3));
		CompletableFuture<CancelResult> cancels = engine.cancel(key(), new CancelOrder(bid.orderId(), buyer));
		CompletableFuture<PlacementResult> unknown = engine.place(key(), new PlaceOrder(UUID.randomUUID(), seller,
				UUID.randomUUID(), Side.SELL, OrderType.LIMIT, Price.parse("100.00"), new Quantity(1)));
		CompletableFuture<PlacementResult> later = engine.place(key(), order(seller, Side.SELL, OrderType.LIMIT, "101.00", 1));

		release.countDown();

		assertThat(rests.get().state()).isEqualTo(PlacementResult.OrderState.OPEN);
		assertThat(fills.get().state()).isEqualTo(PlacementResult.OrderState.FILLED);
		assertThat(cancels.get().status()).isEqualTo(CancelResult.Status.CANCELED);
		assertThatThrownBy(unknown::get).hasCauseInstanceOf(TradingEngine.UnknownInstrumentException.class);
		assertThat(later.get().state()).isEqualTo(PlacementResult.OrderState.OPEN);
		assertThat(List.of(rests.get().commandSequence(), fills.get().commandSequence(),
				cancels.get().commandSequence(), later.get().commandSequence()))
			.as("in admission order")
			.containsExactly(before + 1, before + 2, before + 3, before + 4);
		assertThat(runs).as("the five queued together, one run").containsExactly(5);
		assertThat(phases("run")).as("one transaction").containsExactly("BEGIN", "WORK", "COMMIT", "APPLY");
		assertThat(engine.depth(instrument, Side.BUY).get()).isEmpty();
		assertThat(engine.depth(instrument, Side.SELL).get()).singleElement()
			.satisfies((level) -> assertThat(level.quantity()).isEqualTo(1));
		assertLedgerMatchesBalances();
	}

	/**
	 * The books fail as they take a command of a run that has committed: the command before it was applied and keeps
	 * its result, it fails with the halt, and the one after it, committed but not in the books, fails as stopped. Its
	 * key gives its committed result once a restart has rebuilt the books.
	 */
	@Test
	void aHaltApplyingARunAnswersTheCommandsBeforeItAndLeavesTheRestToTheirKeys() throws Exception {
		List<Integer> runs = runningEngine(8);
		UUID buyer = funded();
		long before = commandSequence();
		java.util.concurrent.CountDownLatch release = hold();
		// Nothing to buy, so the remainder is cancelled and nothing rests; the next one rests, and the books fail as
		// they take it.
		CompletableFuture<PlacementResult> unfilled = engine.place(key(),
				order(buyer, Side.BUY, OrderType.MARKET_PROTECTED, "101.00", 1));
		PlaceOrder halting = order(buyer, Side.BUY, OrderType.LIMIT, "99.00", 1);
		CompletableFuture<PlacementResult> halts = engine.place(key(), halting);
		CommandKey afterKey = key();
		PlaceOrder after = order(buyer, Side.BUY, OrderType.LIMIT, "98.00", 1);
		CompletableFuture<PlacementResult> committedAfter = engine.place(afterKey, after);
		failBookAt.set("added");

		try {
			release.countDown();

			assertThat(unfilled.get().state()).isEqualTo(OrderState.CANCELED);
			assertThat(unfilled.get().commandSequence()).isEqualTo(before + 1);
			assertThatThrownBy(halts::get).hasCauseInstanceOf(CommandWriter.WriterHaltException.class);
			assertThatThrownBy(committedAfter::get).hasCauseInstanceOf(CommandWriter.WriterStoppedException.class)
				.hasMessageContaining("look it up by its key");
			assertThat(runs).containsExactly(3);
			assertThat(writer.isAvailable()).isFalse();
			assertThat(orderState(halting.orderId())).as("committed with its run").isEqualTo("OPEN");
			assertThat(orderState(after.orderId())).as("committed with its run").isEqualTo("OPEN");
		}
		finally {
			failBookAt.set(null);
		}
		restartEngine();
		PlacementResult answer = engine.place(afterKey, after).get();
		assertThat(answer.state()).isEqualTo(OrderState.OPEN);
		assertThat(answer.commandSequence()).isEqualTo(before + 3);
		assertThat(engine.depth(instrument, Side.BUY).get()).extracting(DepthLevel::quantity).containsExactly(1L, 1L);
	}

	/**
	 * A command of a run that fails without halting, in its statements or when the database refuses the run's commit,
	 * rolls the whole run back; its commands then run one by one, so the failing one fails alone and the others commit
	 * with the sequences they would have had without it. Test-only triggers refuse an order of 6,667 shares as it is
	 * written and one of 6,666 at commit.
	 */
	@Test
	void aCommandFailingInARunFailsAloneAndTheOthersCommitInOrder() throws Exception {
		List<Integer> runs = runningEngine(8);
		UUID buyer = funded();
		ddl("""
				CREATE FUNCTION test_refused_order() RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN
				    IF NEW.requested_quantity IN (6666, 6667) THEN
				        RAISE EXCEPTION 'refused' USING ERRCODE = 'check_violation';
				    END IF;
				    RETURN NULL;
				END $$;
				CREATE TRIGGER test_refused_now AFTER INSERT ON orders
				    FOR EACH ROW WHEN (NEW.requested_quantity = 6667) EXECUTE FUNCTION test_refused_order();
				CREATE CONSTRAINT TRIGGER test_refused_at_commit AFTER INSERT ON orders DEFERRABLE INITIALLY DEFERRED
				    FOR EACH ROW WHEN (NEW.requested_quantity = 6666) EXECUTE FUNCTION test_refused_order();""");
		try {
			for (long refused : List.of(6_667L, 6_666L)) {
				long before = commandSequence();
				java.util.concurrent.CountDownLatch release = hold();
				CompletableFuture<PlacementResult> first = engine.place(key(),
						order(buyer, Side.BUY, OrderType.LIMIT, "1.00", 1));
				PlaceOrder failing = order(buyer, Side.BUY, OrderType.LIMIT, "1.00", refused);
				CompletableFuture<PlacementResult> fails = engine.place(key(), failing);
				CompletableFuture<PlacementResult> third = engine.place(key(),
						order(buyer, Side.BUY, OrderType.LIMIT, "1.00", 2));

				release.countDown();

				assertThat(first.get().commandSequence()).as(refused + ": first").isEqualTo(before + 1);
				assertThatThrownBy(fails::get).as(refused + ": fails alone")
					.cause()
					.isNotInstanceOf(WriterHaltException.class)
					.isNotInstanceOf(WriterStoppedException.class);
				assertThat(third.get().commandSequence()).as(refused + ": next, no gap").isEqualTo(before + 2);
				assertThat(commandSequence()).isEqualTo(before + 2);
				assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE id = ?", Long.class,
						failing.orderId()))
					.isZero();
				assertThat(writer.isAvailable()).isTrue();
			}
			assertThat(runs).as("each run reached the engine whole").containsExactly(3, 3);
			assertThat(phases("run")).as("neither run committed as one; each fell back")
				.containsExactly("FALLBACK", "FALLBACK");
			assertThat(engine.depth(instrument, Side.BUY).get()).extracting(DepthLevel::quantity).containsExactly(6L);
			assertLedgerMatchesBalances();
		}
		finally {
			ddl("""
					DROP TRIGGER test_refused_now ON orders; DROP TRIGGER test_refused_at_commit ON orders;
					DROP FUNCTION test_refused_order();""");
		}
	}

	/**
	 * A run whose commit outcome is lost: every command of it fails, one with the halt and the others as stopped, since
	 * any of them may have committed. After a restart each key gives the committed result, and nothing runs twice.
	 */
	@Test
	void anUnknownCommitOfARunFailsEveryCommandAndEachKeyResolvesAfterARestart() throws Exception {
		List<Integer> runs = runningEngine(8);
		UUID seller = funded();
		UUID buyer = funded();
		long before = commandSequence();
		java.util.concurrent.CountDownLatch release = hold();
		List<CommandKey> keys = List.of(key(), key(), key());
		PlaceOrder sell = order(seller, Side.SELL, OrderType.LIMIT, "100.00", 5);
		PlaceOrder buy = order(buyer, Side.BUY, OrderType.LIMIT, "100.00", 2);
		CompletableFuture<PlacementResult> sold = engine.place(keys.get(0), sell);
		CompletableFuture<PlacementResult> bought = engine.place(keys.get(1), buy);
		CompletableFuture<CancelResult> canceled = engine.cancel(keys.get(2), new CancelOrder(sell.orderId(), seller));
		loseAnswer.set(true);

		release.countDown();

		assertThatThrownBy(sold::get).hasCauseInstanceOf(WriterHaltException.class);
		assertThatThrownBy(bought::get).hasCauseInstanceOf(WriterStoppedException.class)
			.hasMessageContaining("look it up by its key");
		assertThatThrownBy(canceled::get).hasCauseInstanceOf(WriterStoppedException.class);
		assertThat(runs).containsExactly(3);
		assertThat(writer.isAvailable()).isFalse();
		Map<String, Object> committed = snapshot();

		restartEngine();

		assertThat(engine.place(keys.get(0), sell).get().commandSequence()).isEqualTo(before + 1);
		PlacementResult boughtAgain = engine.place(keys.get(1), buy).get();
		assertThat(boughtAgain.filled()).isEqualTo(2);
		assertThat(boughtAgain.commandSequence()).isEqualTo(before + 2);
		CancelResult canceledAgain = engine.cancel(keys.get(2), new CancelOrder(sell.orderId(), seller)).get();
		assertThat(canceledAgain.status()).isEqualTo(CancelResult.Status.CANCELED);
		assertThat(canceledAgain.canceledQuantity()).isEqualTo(3);
		assertThat(snapshot()).as("nothing applied twice").isEqualTo(committed);
		assertThat(engine.depth(instrument, Side.SELL).get()).isEmpty();
		assertLedgerMatchesBalances();
	}

	/** A run that finds another writer has claimed the market halts and commits nothing. */
	@Test
	void aStaleWriterHaltsARunAndCommitsNothing() throws Exception {
		runningEngine(8);
		UUID seller = funded();
		java.util.concurrent.CountDownLatch release = hold();
		CompletableFuture<PlacementResult> first = engine.place(key(), order(seller, Side.SELL, OrderType.LIMIT, "100.00", 1));
		CompletableFuture<PlacementResult> second = engine.place(key(), order(seller, Side.SELL, OrderType.LIMIT, "100.00", 2));
		jdbc.update("UPDATE simulation SET writer_epoch = writer_epoch + 1");
		Map<String, Object> before = snapshot();

		release.countDown();

		assertThatThrownBy(first::get).hasCauseInstanceOf(WriterHaltException.class)
			.rootCause()
			.hasMessageContaining("another writer owns the market");
		assertThatThrownBy(second::get).hasCauseInstanceOf(WriterStoppedException.class)
			.hasMessageContaining("before this command's run committed");
		assertThat(writer.isAvailable()).isFalse();
		assertThat(snapshot()).isEqualTo(before);
		restartEngine();
	}

	/**
	 * Keys repeated within a run answer as they would one by one: the same request again gets the first answer, another
	 * request with the key is a conflict, neither takes a sequence, and only the first is written.
	 */
	@Test
	void keysRepeatedInARunAnswerAsTheyWouldOneByOne() throws Exception {
		List<Integer> runs = runningEngine(8);
		UUID seller = funded();
		long before = commandSequence();
		CommandKey placing = key(seller, UUID.randomUUID(), "sell 3 at 100.00");
		PlaceOrder sell = order(seller, Side.SELL, OrderType.LIMIT, "100.00", 3);
		CommandKey canceling = key(seller, UUID.randomUUID(), "cancel it");
		java.util.concurrent.CountDownLatch release = hold();
		CompletableFuture<PlacementResult> first = engine.place(placing, sell);
		CompletableFuture<PlacementResult> again = engine.place(placing, sell);
		PlaceOrder other = order(seller, Side.SELL, OrderType.LIMIT, "100.00", 4);
		CompletableFuture<PlacementResult> conflict = engine.place(key(seller, placing.idempotencyKey(), "sell 4"), other);
		CompletableFuture<CancelResult> cancel = engine.cancel(canceling, new CancelOrder(sell.orderId(), seller));
		CompletableFuture<CancelResult> cancelAgain = engine.cancel(canceling, new CancelOrder(sell.orderId(), seller));
		CompletableFuture<PlacementResult> next = engine.place(key(), order(seller, Side.SELL, OrderType.LIMIT, "101.00", 1));

		release.countDown();

		assertThat(again.get()).isEqualTo(first.get());
		assertThat(first.get().commandSequence()).isEqualTo(before + 1);
		assertThatThrownBy(conflict::get).hasCauseInstanceOf(IdempotencyConflictException.class);
		assertThat(cancel.get().status()).isEqualTo(CancelResult.Status.CANCELED);
		assertThat(cancelAgain.get()).isEqualTo(cancel.get());
		assertThat(cancel.get().commandSequence()).isEqualTo(before + 2);
		assertThat(next.get().commandSequence()).isEqualTo(before + 3);
		assertThat(commandSequence()).isEqualTo(before + 3);
		assertThat(runs).containsExactly(6);
		assertThat(phases("run")).as("one transaction").containsExactly("BEGIN", "WORK", "COMMIT", "APPLY");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE id = ?", Long.class, other.orderId())).isZero();
		assertThat(engine.depth(instrument, Side.SELL).get()).extracting(DepthLevel::quantity).containsExactly(1L);
		// After the run, from the database, the same answers.
		assertThat(engine.place(placing, sell).get()).isEqualTo(first.get());
		assertThat(engine.cancel(canceling, new CancelOrder(sell.orderId(), seller)).get()).isEqualTo(cancel.get());
	}

	/**
	 * Each command's snapshot in a run is its instrument's public state once that command commits: the book with the
	 * run's earlier commands, and the tape with their trades.
	 */
	@Test
	void eachSnapshotInARunCarriesTheRunsEarlierCommands() throws Exception {
		runningEngine(8);
		UUID seller = funded();
		UUID buyer = funded();
		java.util.concurrent.CountDownLatch release = hold();
		CompletableFuture<PlacementResult> rests = engine.place(key(), order(seller, Side.SELL, OrderType.LIMIT, "100.00", 5));
		CompletableFuture<PlacementResult> takes = engine.place(key(), order(buyer, Side.BUY, OrderType.LIMIT, "100.00", 2));
		CompletableFuture<PlacementResult> takesMore = engine.place(key(),
				order(buyer, Side.BUY, OrderType.LIMIT, "100.00", 1));

		release.countDown();

		MarketSnapshot first = marketSnapshot(rests.get().commandSequence());
		assertThat(first.getAsks()).containsExactly(new BookLevel(10_000L, 5L, 1));
		assertThat(first.getRecentTrades()).isEmpty();
		MarketSnapshot second = marketSnapshot(takes.get().commandSequence());
		assertThat(second.getAsks()).containsExactly(new BookLevel(10_000L, 3L, 1));
		assertThat(second.getRecentTrades()).extracting(PublicTrade::getTradeSequence).containsExactly(1L);
		MarketSnapshot third = marketSnapshot(takesMore.get().commandSequence());
		assertThat(third.getAsks()).containsExactly(new BookLevel(10_000L, 2L, 1));
		assertThat(third.getRecentTrades()).extracting(PublicTrade::getTradeSequence).containsExactly(2L, 1L);
		assertThat(third.getLastTradeSequence()).isEqualTo(2);
		assertTapeMatchesDatabase(third);
		assertThat(phases("run")).as("one transaction").containsExactly("BEGIN", "WORK", "COMMIT", "APPLY");
	}

	/**
	 * Group commit leaves exactly what one command at a time leaves. A seeded stream of 20,000 orders and cancels runs
	 * twice, each time on two new instruments and new accounts, submitted ahead of the writer so that runs form: once
	 * with runs of one and once with runs of up to 32. The stream crosses, rests, sweeps several makers, meets its own
	 * account's orders, sends protected market orders, orders it cannot pay for, orders for an unknown instrument,
	 * cancels of orders placed just before (often in the same run), of filled, cancelled, unknown and others' orders,
	 * and keys repeated for the same request and for another. One account has so little that whether it can pay
	 * depends on what the run's earlier orders reserved, and one starts with cash and no position rows at all. Runs of
	 * 32 go twice: through the store as it is, each kind of row in one statement, and through one that sends one row
	 * per statement (a journal batch with its postings), so every kind of row a run writes twice or more is split. The
	 * answers, sequences, order rows, trades, ledger, balances, account versions, the accounts told of each change,
	 * outbox events and books are the same, ids aside, and the database reconciles.
	 */
	@Test
	void runsOfUpToThirtyTwoLeaveExactlyWhatRunsOfOneLeave() throws Exception {
		org.junit.jupiter.api.Assumptions.assumeTrue(maxRun() == 1, "the test runs both run limits itself");
		Map<String, Object> alone = streamRun(1, 1, store);
		Map<String, Object> together = streamRun(32, 2, store);
		Map<String, Object> split = streamRun(32, 3,
				new JdbcTradingStore(JdbcClient.create(dataSource), JsonMapper.builder().build(), 1));

		assertThat(alone.get("largest run")).isEqualTo(1);
		assertThat((int) together.get("largest run")).as("the largest run").isGreaterThan(8);
		assertThat((int) together.get("in runs")).as("commands that shared a run").isGreaterThan(10_000);
		// A run that fails is run again one by one and still gives the right answers, so a mistake that makes runs fail
		// would pass unseen above: every run of several commands must have committed as one.
		assertThat(together.get("committed whole")).as("runs of several committed as one")
			.isEqualTo(together.get("runs of several"));
		@SuppressWarnings("unchecked")
		Map<String, Integer> coverage = (Map<String, Integer>) alone.get("coverage");
		for (Map.Entry<String, Integer> least : Map.ofEntries(Map.entry("cancel CANCELED", 1_000),
				Map.entry("cancel CANCELED soon after", 300), Map.entry("cancel ALREADY_CANCELED", 20),
				Map.entry("cancel NOT_CANCELABLE", 50), Map.entry("cancel NOT_FOUND", 50),
				Map.entry("failed IdempotencyConflictException", 100),
				Map.entry("failed UnknownInstrumentException", 20), Map.entry("repeated", 300),
				Map.entry("CANCELED traded IOC_REMAINDER_CANCELED", 20), Map.entry("CANCELED SELF_TRADE_PREVENTED", 20),
				Map.entry("REJECTED INSUFFICIENT_CASH", 10), Map.entry("takers of several makers", 80),
				Map.entry("REJECTED ORDER_LIMIT_EXCEEDED", 20), Map.entry("REJECTED INSUFFICIENT_SHARES", 50))
			.entrySet()) {
			assertThat(coverage.getOrDefault(least.getKey(), 0)).as(least.getKey() + " in " + coverage)
				.isGreaterThanOrEqualTo(least.getValue());
		}
		assertThat(split.get("committed whole")).as("split runs of several committed as one")
			.isEqualTo(split.get("runs of several"));
		for (String part : List.of("coverage", "answers", "orders", "trades", "ledger", "balances", "versions", "told",
				"events", "books")) {
			assertThat(firstDifference(alone.get(part), together.get(part))).as(part).isNull();
			assertThat(firstDifference(alone.get(part), split.get(part))).as(part + ", rows split").isNull();
		}
		assertThat(store.reconciliationProblems(simulation)).isEmpty();
		assertLedgerMatchesBalances();
		restartEngine();
	}

	/** Where two parts first differ, or null if they are the same. */
	private static String firstDifference(Object alone, Object together) {
		if (alone instanceof List<?> first && together instanceof List<?> second) {
			for (int i = 0; i < Math.min(first.size(), second.size()); i++) {
				if (!first.get(i).equals(second.get(i))) {
					return "at " + i + ": alone " + first.get(i) + ", together " + second.get(i);
				}
			}
			return first.size() == second.size() ? null : "sizes " + first.size() + " and " + second.size();
		}
		return alone.equals(together) ? null : "alone " + alone + ", together " + together;
	}

	/** A step of the stream: what was sent, with which key. */
	private record Step(CommandKey key, Object command) {
	}

	/** The stream run on new instruments and accounts with this run limit; what it left, ids replaced by names. */
	private Map<String, Object> streamRun(int maxRun, long salt, TradingStore runStore) throws Exception {
		Map<UUID, String> names = new HashMap<>();
		List<UUID> instruments = new ArrayList<>();
		Map<String, String> symbols = new HashMap<>();
		for (int i = 0; i < 2; i++) {
			UUID id = UUID.randomUUID();
			String symbol = symbol(SYMBOLS.incrementAndGet());
			jdbc.update("INSERT INTO instrument (id, simulation_id, symbol, reference_price_cents) VALUES (?, ?, ?, 10000)",
					id, simulation, symbol);
			instruments.add(id);
			names.put(id, "i" + i);
			symbols.put(symbol, "i" + i);
		}
		List<UUID> accounts = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			// The last two: one that can afford little, and one with cash and no position rows.
			long cash = i == 10 ? 30_000 : STARTING_CASH;
			long shares = i == 10 ? 5 : i == 11 ? 0 : STARTING_SHARES;
			UUID account = new TransactionTemplate(transactionManager)
				.execute((status) -> fundIn(instruments, cash, shares));
			accounts.add(account);
			names.put(account, "a" + i);
		}
		writer.close(Duration.ofSeconds(5));
		List<Integer> runs = new java.util.concurrent.CopyOnWriteArrayList<>();
		writer = new CommandWriter("test-writer", 1_000, maxRun, runs::add);
		resting = new FailingRestingOrders();
		List<Map.Entry<Long, Set<UUID>>> told = java.util.Collections.synchronizedList(new ArrayList<>());
		// One instant for both runs, so trades and snapshots carry the same times.
		Clock clock = Clock.fixed(java.time.Instant.parse("2026-11-19T09:30:00.123456Z"), java.time.ZoneOffset.UTC);
		engine = new TradingEngine(runStore, new SpringTransactionRunner(transactionManager), writer, clock, (account) -> {
			throw new UnsupportedOperationException("no bots in these tests");
		}, (ids, sequence) -> told.add(Map.entry(sequence, Set.copyOf(ids))), timings, System::nanoTime, resting);
		engine.start();
		long base = commandSequence();
		timed.clear();

		// The stream depends only on the seed, never on answers, so both runs send the same commands.
		Random random = new Random(16_016L);
		List<Step> steps = new ArrayList<>();
		Map<String, Integer> coverage = new java.util.TreeMap<>();
		// Each account's limit orders not yet sent a cancel, oldest first: cancelling them in turn keeps the books
		// turning over, so accounts stay under their limit of resting orders.
		Map<UUID, java.util.Deque<Integer>> waiting = new HashMap<>();
		accounts.forEach((account) -> waiting.put(account, new java.util.ArrayDeque<>()));
		for (int step = 0; step < 20_000; step++) {
			int roll = random.nextInt(100);
			UUID account = accounts.get(random.nextInt(accounts.size()));
			int back = 1 + random.nextInt(Math.min(40, Math.max(1, step)));
			java.util.Deque<Integer> mine = waiting.get(account);
			if (roll < 35 && !mine.isEmpty()) {
				// Usually the account's oldest order, sometimes its newest, often in the same run.
				int target = random.nextInt(3) == 0 ? mine.pollLast() : mine.pollFirst();
				UUID order = new UUID(salt, target);
				UUID by = account;
				int odd = random.nextInt(20);
				if (odd == 0) {
					order = new UUID(salt + 100, step);
					names.put(order, "x" + step);
				}
				else if (odd == 1) {
					// Someone else's order, unless the draw picks its owner.
					by = accounts.get(random.nextInt(accounts.size()));
				}
				steps.add(new Step(streamKey(by, new UUID(salt + 200, step), "DELETE /api/v1/orders", "cancel " + step),
						new CancelOrder(order, by)));
				continue;
			}
			if (roll >= 35 && roll < 39 && step >= back) {
				// The same key and request again, a little later.
				steps.add(steps.get(step - back));
				coverage.merge("repeated", 1, Integer::sum);
				continue;
			}
			UUID instrument = instruments.get(random.nextInt(instruments.size()));
			Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
			OrderType type = roll >= 92 ? OrderType.MARKET_PROTECTED : OrderType.LIMIT;
			long quantity = roll >= 86 && roll < 92 ? 6 + random.nextInt(10) : 1 + random.nextInt(4);
			// Bids from 99.00 to 100.40 and asks from 99.60 to 101.00: many cross, many rest.
			long cents = (side == Side.BUY ? 9_900 : 9_960) + 10L * random.nextInt(15);
			if (roll == 99) {
				cents = 90_000;
				quantity = 10_000;
			}
			if (random.nextInt(200) == 0) {
				instrument = UUID.randomUUID();
			}
			UUID order = new UUID(salt, step);
			names.put(order, "o" + step);
			CommandKey key = streamKey(account, new UUID(salt + 200, step), "POST /api/v1/orders", "place " + step);
			if (roll >= 39 && roll < 41 && step >= back) {
				// An earlier key, for this other request: a conflict.
				CommandKey earlier = steps.get(step - back).key();
				key = streamKey(earlier.principalId(), earlier.idempotencyKey(), "POST /api/v1/orders", "other " + step);
				account = earlier.principalId();
			}
			if (type == OrderType.LIMIT) {
				waiting.get(account).addLast(step);
			}
			steps.add(new Step(key, new PlaceOrder(order, account, instrument, side, type, new Price(cents),
					new Quantity(quantity))));
		}

		List<CompletableFuture<?>> futures = new ArrayList<>(steps.size());
		for (int step = 0; step < steps.size(); step++) {
			if (step >= 600) {
				// At most 600 waiting, well inside the queue.
				futures.get(step - 600).handle((result, failure) -> null).join();
			}
			Step sent = steps.get(step);
			futures.add(sent.command() instanceof PlaceOrder place ? engine.place(sent.key(), place)
					: engine.cancel(sent.key(), (CancelOrder) sent.command()));
		}
		List<String> answers = new ArrayList<>(steps.size());
		for (int step = 0; step < steps.size(); step++) {
			Object result;
			try {
				result = futures.get(step).get();
			}
			catch (ExecutionException ex) {
				coverage.merge("failed " + ex.getCause().getClass().getSimpleName(), 1, Integer::sum);
				answers.add("failed " + ex.getCause().getClass().getSimpleName());
				continue;
			}
			if (result instanceof PlacementResult placed) {
				answers.add(names.get(placed.orderId()) + " " + placed.state() + " " + placed.reason() + " "
						+ placed.filled() + "/" + placed.working() + "/" + placed.canceled() + " at "
						+ (placed.commandSequence() - base));
				coverage.merge(placed.state() + (placed.filled() > 0 ? " traded" : "")
						+ (placed.reason() == null ? "" : " " + placed.reason()), 1, Integer::sum);
			}
			else {
				CancelResult canceled = (CancelResult) result;
				answers.add(names.get(canceled.orderId()) + " " + canceled.status() + " " + canceled.canceledQuantity()
						+ " at " + (canceled.commandSequence() - base));
				coverage.merge("cancel " + canceled.status(), 1, Integer::sum);
				String target = names.get(canceled.orderId());
				if (canceled.status() == CancelResult.Status.CANCELED && target.startsWith("o")
						&& step - Integer.parseInt(target.substring(1)) <= 32) {
					coverage.merge("cancel CANCELED soon after", 1, Integer::sum);
				}
			}
		}

		Map<String, Object> footprint = new java.util.LinkedHashMap<>();
		footprint.put("largest run", runs.stream().mapToInt(Integer::intValue).max().orElse(0));
		footprint.put("in runs", runs.stream().filter((size) -> size > 1).mapToInt(Integer::intValue).sum());
		footprint.put("runs of several", runs.stream().filter((size) -> size > 1).count());
		footprint.put("committed whole", phases("run").stream().filter("COMMIT"::equals).count());
		Object[] both = instruments.toArray();
		footprint.put("orders", jdbc.query("""
				SELECT id, account_id, instrument_id, side, type, bound_cents, requested_quantity, filled_quantity,
				    working_quantity, canceled_quantity, state, coalesce(reason, '-'), accepted_sequence, version
				FROM orders WHERE instrument_id IN (?, ?)""", (row, index) -> names.get(row.getObject(1, UUID.class)) + " "
				+ names.get(row.getObject(2, UUID.class)) + " " + names.get(row.getObject(3, UUID.class)) + " "
				+ row.getString(4) + " " + row.getString(5) + " " + row.getLong(6) + " " + row.getLong(7) + " "
				+ row.getLong(8) + "/" + row.getLong(9) + "/" + row.getLong(10) + " " + row.getString(11) + " "
				+ row.getString(12) + " at " + (row.getObject(13) == null ? "-" : row.getLong(13) - base) + " v"
				+ row.getLong(14), both).stream().sorted().toList());
		Map<UUID, String> tradeNames = new HashMap<>();
		Map<String, Integer> takers = new HashMap<>();
		footprint.put("trades", jdbc.query("""
				SELECT id, instrument_id, trade_sequence, maker_order_id, taker_order_id, buyer_account_id,
				    seller_account_id, price_cents, quantity, executed_at, command_sequence
				FROM trades WHERE instrument_id IN (?, ?) ORDER BY instrument_id = ?, trade_sequence""", (row, index) -> {
			String name = names.get(row.getObject(2, UUID.class)) + "#" + row.getLong(3);
			tradeNames.put(row.getObject(1, UUID.class), name);
			takers.merge(names.get(row.getObject(5, UUID.class)), 1, Integer::sum);
			return name + " maker " + names.get(row.getObject(4, UUID.class)) + " taker "
					+ names.get(row.getObject(5, UUID.class)) + " buyer " + names.get(row.getObject(6, UUID.class))
					+ " seller " + names.get(row.getObject(7, UUID.class)) + " " + row.getLong(8) + " x " + row.getLong(9)
					+ " " + row.getTimestamp(10).toInstant() + " at " + (row.getLong(11) - base);
		}, both[0], both[1], both[1]));
		coverage.put("takers of several makers", (int) takers.values().stream().filter((fills) -> fills > 1).count());
		footprint.put("coverage", coverage);
		footprint.put("ledger", jdbc.query("""
				SELECT b.command_sequence, b.cause, b.trade_id, e.account_id, e.instrument_id, e.bucket, e.delta
				FROM ledger_entry e JOIN journal_batch b ON b.id = e.batch_id
				WHERE b.command_sequence > ? AND b.cause <> 'GENESIS' AND e.account_id = ANY(?::uuid[])""",
				(row, index) -> (row.getLong(1) - base) + " " + row.getString(2) + " "
						+ tradeNames.get(row.getObject(3, UUID.class)) + " " + names.get(row.getObject(4, UUID.class))
						+ " " + names.get(row.getObject(5, UUID.class)) + " " + row.getString(6) + " " + row.getLong(7),
				base, accounts.stream().map(UUID::toString).toArray(String[]::new))
			.stream()
			.sorted(Comparator.comparingLong((String line) -> Long.parseLong(line.substring(0, line.indexOf(' '))))
				.thenComparing(Comparator.naturalOrder()))
			.toList());
		List<String> balances = new ArrayList<>();
		List<String> versions = new ArrayList<>();
		for (UUID account : accounts) {
			balances.add(names.get(account) + " " + cash(account) + " " + jdbc.query(
					"SELECT instrument_id, total_quantity, reserved_quantity FROM position WHERE account_id = ?",
					(row, index) -> names.get(row.getObject(1, UUID.class)) + " " + row.getLong(2) + "/" + row.getLong(3),
					account).stream().sorted().toList());
			versions.add(names.get(account) + " v" + jdbc.queryForObject("SELECT version FROM account WHERE id = ?",
					Long.class, account));
		}
		footprint.put("balances", balances);
		footprint.put("versions", versions);
		footprint.put("told", told.stream()
			.map((change) -> (change.getKey() - base) + " "
					+ change.getValue().stream().map(names::get).sorted().toList())
			.toList());
		List<String> events = new ArrayList<>();
		for (Map<String, Object> row : jdbc.queryForList(
				"SELECT topic, payload, command_sequence FROM outbox WHERE event_key IN (?, ?) ORDER BY id",
				simulation + "|" + instruments.get(0), simulation + "|" + instruments.get(1))) {
			byte[] payload = (byte[]) row.get("payload");
			long at = ((Number) row.get("command_sequence")).longValue() - base;
			if ("mse.trades.v1".equals(row.get("topic"))) {
				TradeExecuted trade = WireEvents.decodeTrade(payload);
				events.add(at + " trade " + tradeNames.get(trade.getTradeId()) + " " + names.get(trade.getInstrumentId())
						+ " " + symbols.get(trade.getSymbol()) + " " + trade.getTradeSequence() + " "
						+ (trade.getCommandSequence() - base) + " " + trade.getPriceCents() + " x " + trade.getQuantity()
						+ " " + trade.getExecutedAt());
			}
			else {
				MarketSnapshot snapshot = WireEvents.decodeSnapshot(payload);
				events.add(at + " snapshot " + names.get(snapshot.getInstrumentId()) + " "
						+ symbols.get(snapshot.getSymbol()) + " " + snapshot.getVenueState() + " last "
						+ snapshot.getLastTradeSequence() + " bids " + snapshot.getBids() + " asks " + snapshot.getAsks()
						+ " tape " + snapshot.getRecentTrades().stream()
							.map((trade) -> tradeNames.get(trade.getTradeId()) + " " + trade.getPriceCents() + " x "
									+ trade.getQuantity() + " " + trade.getAggressorSide() + " " + trade.getExecutedAt())
							.toList()
						+ " last trade " + (snapshot.getLastTrade() == null ? null
								: tradeNames.get(snapshot.getLastTrade().getTradeId()))
						+ " source " + (snapshot.getSourceCommandSequence() - base) + " " + snapshot.getGeneratedAt()
						+ " " + snapshot.getRules() + " " + snapshot.getRemoved());
			}
		}
		footprint.put("events", events);
		List<String> books = new ArrayList<>();
		for (UUID instrument : instruments) {
			books.add(names.get(instrument) + " bids " + engine.depth(instrument, Side.BUY).get() + " asks "
					+ engine.depth(instrument, Side.SELL).get());
		}
		footprint.put("books", books);
		footprint.put("answers", answers);
		return footprint;
	}

	private static CommandKey streamKey(UUID principal, UUID idempotencyKey, String route, String request) {
		try {
			return new CommandKey(principal, idempotencyKey, route,
					MessageDigest.getInstance("SHA-256").digest(request.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * A trader funded with this cash and these shares of each instrument, by one balanced genesis batch; with no shares,
	 * the account has no position rows at all.
	 */
	private UUID fundIn(List<UUID> instruments, long cash, long shares) {
		UUID account = UUID.randomUUID();
		jdbc.update("INSERT INTO account (id, simulation_id, kind, display_name) VALUES (?, ?, 'BOT', 'Bot')", account,
				simulation);
		UUID batch = UUID.randomUUID();
		jdbc.update("INSERT INTO journal_batch (id, simulation_id, cause, command_sequence) VALUES (?, ?, 'GENESIS', 1)",
				batch, simulation);
		String post = "INSERT INTO ledger_entry (batch_id, account_id, instrument_id, bucket, delta) VALUES (?, ?, ?, 'AVAILABLE', ?)";
		jdbc.update(post, batch, issuance, null, -cash);
		jdbc.update(post, batch, account, null, cash);
		jdbc.update("INSERT INTO cash_balance (account_id, account_kind, total_cents, reserved_cents) VALUES (?, 'BOT', ?, 0)",
				account, cash);
		for (UUID held : shares == 0 ? List.<UUID>of() : instruments) {
			jdbc.update(post, batch, issuance, held, -shares);
			jdbc.update(post, batch, account, held, shares);
			jdbc.update("""
					INSERT INTO position (account_id, account_kind, instrument_id, total_quantity, reserved_quantity)
					VALUES (?, 'BOT', ?, ?, 0)""", account, held, shares);
		}
		return account;
	}

	/**
	 * A command in the middle of a run finds the book out of step with the database: the run rolls back whole, that
	 * command fails with the halt, and the ones before and after it fail as stopped, none of them written.
	 */
	@Test
	void aHaltInsideARunFailsItWholeAndBlamesTheCommandThatCausedIt() throws Exception {
		runningEngine(8);
		UUID seller = funded();
		UUID hidden = restingOnlyInTheDatabase(seller);
		Map<String, Object> before = snapshot();
		java.util.concurrent.CountDownLatch release = hold();
		CompletableFuture<PlacementResult> first = engine.place(key(), order(seller, Side.SELL, OrderType.LIMIT, "100.00", 1));
		CompletableFuture<CancelResult> halting = engine.cancel(key(), new CancelOrder(hidden, seller));
		CompletableFuture<PlacementResult> third = engine.place(key(), order(seller, Side.SELL, OrderType.LIMIT, "101.00", 1));
		try {
			release.countDown();

			assertThatThrownBy(first::get).hasCauseInstanceOf(WriterStoppedException.class)
				.hasMessageContaining("before this command's run committed");
			assertThatThrownBy(halting::get).hasCauseInstanceOf(WriterHaltException.class)
				.rootCause()
				.hasMessageContaining("disagree about order " + hidden);
			assertThatThrownBy(third::get).hasCauseInstanceOf(WriterStoppedException.class);
			assertThat(writer.isAvailable()).isFalse();
			assertThat(snapshot()).as("nothing committed").isEqualTo(before);
		}
		finally {
			cancelInTheDatabase(hidden);
		}
		restartEngine();
	}

	/**
	 * A run that fails without halting runs again one by one, and a command that halts the writer then stops the ones
	 * after it, as one by one: the first commits, the refused one fails alone, the cancel of an order the database
	 * holds differently from the book halts the writer, and the last fails as never run. The run's rows are sent
	 * together at its end, the refused order before the cancel, so only one by one does the cancel's row show.
	 */
	@Test
	void aHaltWhileARunRunsAgainOneByOneStopsTheCommandsAfterIt() throws Exception {
		runningEngine(8);
		UUID seller = funded();
		PlacementResult held = place(seller, Side.SELL, OrderType.LIMIT, "100.00", 5);
		asOwner("UPDATE orders SET working_quantity = 3, canceled_quantity = 2 WHERE id = ?", held.orderId());
		ddl("""
				CREATE FUNCTION test_refused_now() RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN
				    RAISE EXCEPTION 'refused' USING ERRCODE = 'check_violation';
				END $$;
				CREATE TRIGGER test_refused_now AFTER INSERT ON orders
				    FOR EACH ROW WHEN (NEW.requested_quantity = 6667) EXECUTE FUNCTION test_refused_now();""");
		try {
			long before = commandSequence();
			java.util.concurrent.CountDownLatch release = hold();
			CompletableFuture<PlacementResult> first = engine.place(key(),
					order(seller, Side.SELL, OrderType.LIMIT, "100.00", 1));
			CompletableFuture<PlacementResult> refused = engine.place(key(),
					order(seller, Side.SELL, OrderType.LIMIT, "100.00", 6_667));
			CompletableFuture<CancelResult> halting = engine.cancel(key(), new CancelOrder(held.orderId(), seller));
			PlaceOrder last = order(seller, Side.SELL, OrderType.LIMIT, "101.00", 1);
			CompletableFuture<PlacementResult> never = engine.place(key(), last);

			release.countDown();

			assertThat(first.get().commandSequence()).isEqualTo(before + 1);
			assertThatThrownBy(refused::get).cause()
				.isNotInstanceOf(WriterHaltException.class)
				.isNotInstanceOf(WriterStoppedException.class);
			assertThatThrownBy(halting::get).hasCauseInstanceOf(WriterHaltException.class);
			assertThatThrownBy(never::get).hasCauseInstanceOf(WriterStoppedException.class)
				.hasMessageContaining("halted before this command ran");
			assertThat(phases("run")).containsExactly("FALLBACK");
			assertThat(commandSequence()).isEqualTo(before + 1);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE id = ?", Long.class, last.orderId()))
				.isZero();
		}
		finally {
			ddl("DROP TRIGGER test_refused_now ON orders; DROP FUNCTION test_refused_now();");
			asOwner("UPDATE orders SET working_quantity = 5, canceled_quantity = 0 WHERE id = ?", held.orderId());
		}
		restartEngine();
	}

	/** The market-wide limit of resting orders counts the orders the run's earlier commands rested. */
	@Test
	void theMarketWideLimitCountsTheRunsEarlierOrders() throws Exception {
		runningEngine(8);
		int total = jdbc.queryForObject("SELECT max_open_orders_total FROM simulation", Integer.class);
		UUID seller = funded();
		UUID buyer = funded();
		int resting = jdbc.queryForObject("SELECT count(*) FROM orders WHERE state IN ('OPEN', 'PARTIALLY_FILLED')",
				Integer.class);
		asOwner("UPDATE simulation SET max_open_orders_total = ?", resting + 1);
		try {
			java.util.concurrent.CountDownLatch release = hold();
			CompletableFuture<PlacementResult> ask = engine.place(key(), order(seller, Side.SELL, OrderType.LIMIT, "490.00", 1));
			CompletableFuture<PlacementResult> bid = engine.place(key(), order(buyer, Side.BUY, OrderType.LIMIT, "10.00", 1));

			release.countDown();

			assertThat(ask.get().rejection()).isNull();
			assertThat(bid.get().rejection()).isEqualTo(RejectionCode.ORDER_LIMIT_EXCEEDED);
			assertThat(phases("run")).as("one transaction").containsExactly("BEGIN", "WORK", "COMMIT", "APPLY");
		}
		finally {
			asOwner("UPDATE simulation SET max_open_orders_total = ?", total);
		}
	}

	/**
	 * Anything failing after a run's commit halts the writer, not just a book: here the account listener throws for
	 * the first command. It was applied but halts the writer; the two after it committed without reaching the books,
	 * fail as stopped, and their keys give their results once a restart has rebuilt the books.
	 */
	@Test
	void aListenerFailingAfterARunsCommitHaltsTheWriter() throws Exception {
		AtomicBoolean failNext = new AtomicBoolean(true);
		runningEngine(8, (accounts, sequence) -> {
			if (failNext.getAndSet(false)) {
				throw new IllegalStateException("injected listener failure");
			}
		});
		UUID seller = funded();
		long before = commandSequence();
		List<CommandKey> keys = List.of(key(), key(), key());
		List<PlaceOrder> orders = List.of(order(seller, Side.SELL, OrderType.LIMIT, "300.00", 1),
				order(seller, Side.SELL, OrderType.LIMIT, "301.00", 1), order(seller, Side.SELL, OrderType.LIMIT, "302.00", 1));
		java.util.concurrent.CountDownLatch release = hold();
		List<CompletableFuture<PlacementResult>> answers = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			answers.add(engine.place(keys.get(i), orders.get(i)));
		}

		release.countDown();

		assertThatThrownBy(answers.get(0)::get).hasCauseInstanceOf(WriterHaltException.class)
			.rootCause()
			.hasMessage("injected listener failure");
		for (CompletableFuture<PlacementResult> later : answers.subList(1, 3)) {
			assertThatThrownBy(later::get).hasCauseInstanceOf(WriterStoppedException.class)
				.hasMessageContaining("look it up by its key");
		}
		assertThat(writer.isAvailable()).isFalse();

		restartEngine();
		for (int i = 0; i < 3; i++) {
			PlacementResult answer = engine.place(keys.get(i), orders.get(i)).get();
			assertThat(answer.state()).isEqualTo(OrderState.OPEN);
			assertThat(answer.commandSequence()).isEqualTo(before + 1 + i);
		}
		assertThat(engine.depth(instrument, Side.SELL).get()).extracting(DepthLevel::quantity)
			.containsExactly(1L, 1L, 1L);
	}

	/**
	 * A row out of step is found only when a run's rows are sent, where nothing says whose row it is: the run rolls back
	 * and runs again one by one, so the order that fills against a maker the database no longer has resting halts the
	 * writer, the one before it commits, and the one after it fails as never run, exactly as one by one.
	 */
	@Test
	void aRowOutOfStepFoundWhenARunsRowsAreSentBlamesItsOwnCommand() throws Exception {
		runningEngine(8);
		UUID seller = funded();
		UUID buyer = funded();
		PlacementResult maker = place(seller, Side.SELL, OrderType.LIMIT, "100.00", 5);
		long before = commandSequence();
		asOwner("UPDATE orders SET state = 'FILLED', working_quantity = 0, filled_quantity = 5 WHERE id = ?",
				maker.orderId());
		try {
			java.util.concurrent.CountDownLatch release = hold();
			CompletableFuture<PlacementResult> first = engine.place(key(),
					order(buyer, Side.BUY, OrderType.LIMIT, "90.00", 1));
			CompletableFuture<PlacementResult> filling = engine.place(key(),
					order(buyer, Side.BUY, OrderType.LIMIT, "100.00", 2));
			CompletableFuture<PlacementResult> last = engine.place(key(),
					order(buyer, Side.BUY, OrderType.LIMIT, "91.00", 1));

			release.countDown();

			assertThat(first.get().commandSequence()).isEqualTo(before + 1);
			assertThatThrownBy(filling::get).hasCauseInstanceOf(WriterHaltException.class)
				.rootCause()
				.hasMessageContaining("Filled 0 of 1 resting orders the book matched");
			assertThatThrownBy(last::get).hasCauseInstanceOf(WriterStoppedException.class)
				.hasMessageContaining("halted before this command ran");
			assertThat(phases("run")).containsExactly("FALLBACK");
			assertThat(commandSequence()).isEqualTo(before + 1);
		}
		finally {
			asOwner("UPDATE orders SET state = 'OPEN', working_quantity = 5, filled_quantity = 0 WHERE id = ?",
					maker.orderId());
		}
		restartEngine();
	}

	/** An order the database has resting that no book holds, as a book out of step would leave it. */
	private UUID restingOnlyInTheDatabase(UUID seller) throws Exception {
		UUID hidden = UUID.randomUUID();
		asOwner("""
				INSERT INTO orders (id, simulation_id, account_id, instrument_id, side, type, time_in_force, bound_cents,
				    requested_quantity, working_quantity, state, accepted_sequence)
				VALUES (?, ?, ?, ?, 'SELL', 'LIMIT', 'GTC', 50000, 2, 2, 'OPEN', ?)""", hidden, simulation, seller, instrument,
				9_000_000_000_000L + SYMBOLS.incrementAndGet());
		return hidden;
	}

	/** Cancelled, it holds nothing, so the next startup's reconciliation has nothing to find. */
	private void cancelInTheDatabase(UUID order) throws Exception {
		asOwner("""
				UPDATE orders SET state = 'CANCELED', working_quantity = 0, canceled_quantity = 2, reason = 'TEST'
				WHERE id = ?""", order);
	}

	/** Holds the writer until the latch is released, so the commands queued meanwhile reach it as one run. */
	private java.util.concurrent.CountDownLatch hold() {
		java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
		writer.submit(() -> release.await(5, java.util.concurrent.TimeUnit.SECONDS));
		return release;
	}

	/** A started engine on a writer with this run limit and the failing store and runner; returns the run sizes. */
	private List<Integer> runningEngine(int maxRun) throws InterruptedException {
		return runningEngine(maxRun, AccountChanges.NONE);
	}

	private List<Integer> runningEngine(int maxRun, AccountChanges changes) throws InterruptedException {
		writer.close(Duration.ofSeconds(5));
		List<Integer> runs = new java.util.concurrent.CopyOnWriteArrayList<>();
		writer = new CommandWriter("test-writer", 1_000, maxRun, runs::add);
		resting = new FailingRestingOrders();
		engine = new TradingEngine(failingBeforeCommit(store), losingAnswers(new SpringTransactionRunner(transactionManager)),
				writer, Clock.systemUTC(), (account) -> {
					throw new UnsupportedOperationException("no bots in these tests");
				}, changes, timings, System::nanoTime, resting);
		engine.start();
		return runs;
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
		assertTapeMatchesDatabase(snapshot);
	}

	/** The tape lives in memory between commands; a restart rebuilds it from the database, like the books. */
	@Test
	void aRestartRebuildsTheTape() throws Exception {
		UUID seller = funded();
		UUID buyer = funded();
		place(seller, Side.SELL, OrderType.LIMIT, "100.00", 10);
		for (int i = 0; i < 3; i++) {
			place(buyer, Side.BUY, OrderType.LIMIT, "100.00", 1);
		}

		restartEngine();
		PlacementResult after = place(seller, Side.SELL, OrderType.LIMIT, "99.00", 1);
		PlacementResult taker = place(buyer, Side.BUY, OrderType.LIMIT, "100.00", 1);

		MarketSnapshot snapshot = marketSnapshot(taker.commandSequence());
		assertThat(snapshot.getRecentTrades()).extracting(PublicTrade::getTradeSequence).containsExactly(4L, 3L, 2L, 1L);
		assertTapeMatchesDatabase(snapshot);
		assertThat(after.state()).isEqualTo(OrderState.OPEN);
	}

	/** A database failure just before commit: nothing in the database changes, the book is untouched, work goes on. */
	@Test
	void aFailureBeforeCommitLeavesNoTrace() throws Exception {
		UUID seller = funded();
		UUID buyer = funded();
		place(seller, Side.SELL, OrderType.LIMIT, "100.00", 5);
		Map<String, Object> before = snapshot();
		List<DepthLevel> bookBefore = engine.depth(instrument, Side.SELL).get();

		// The flush is the last write: every row of the transaction, its answer included, goes in it. It fails once they
		// are all written, so the rollback has them to undo.
		failAt.set("flush written");
		CompletableFuture<PlacementResult> failed = engine.place(key(), order(buyer, Side.BUY, OrderType.LIMIT, "100.00", 3));

		assertThatThrownBy(failed::get).isInstanceOf(ExecutionException.class)
			.rootCause()
			.hasMessageContaining("injected");
		assertThat(snapshot()).isEqualTo(before);
		assertThat(engine.depth(instrument, Side.SELL).get()).isEqualTo(bookBefore);

		failAt.set(null);
		PlacementResult retried = place(buyer, Side.BUY, OrderType.LIMIT, "100.00", 3);
		assertThat(retried.state()).isEqualTo(OrderState.FILLED);
		assertTapeMatchesDatabase(marketSnapshot(retried.commandSequence()));
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

	/**
	 * A market is built for 10,000 bots with 20 orders resting each, so it starts with room for 200,000; and an older
	 * market still at 50,000 was raised (V26).
	 */
	@Test
	void aNewMarketHasRoomForTenThousandBotsOrders() {
		assertThat(jdbc.queryForObject("SELECT max_open_orders_total FROM simulation", Integer.class)).isEqualTo(200_000);
		assertThat(jdbc.queryForObject("""
				SELECT column_default FROM information_schema.columns
				WHERE table_name = 'simulation' AND column_name = 'max_open_orders_total'""", String.class))
			.isEqualTo("200000");
	}

	/**
	 * An order that would rest is refused once its account, or the whole market, has as many resting as allowed; one
	 * that only trades is not. The market's count comes from the books, so it follows cancels and survives a restart.
	 * Other tests' orders may still rest in this shared market, so the limits are set from what rests now.
	 */
	@Test
	void restingOrdersAreLimitedPerAccountAndAcrossTheMarket() throws Exception {
		int perAccount = jdbc.queryForObject("SELECT max_open_orders_per_account FROM simulation", Integer.class);
		int total = jdbc.queryForObject("SELECT max_open_orders_total FROM simulation", Integer.class);
		try {
			UUID busy = funded();
			asOwner("UPDATE simulation SET max_open_orders_per_account = 2");
			place(busy, Side.SELL, OrderType.LIMIT, "500.00", 1);
			place(busy, Side.SELL, OrderType.LIMIT, "501.00", 1);
			PlacementResult third = place(busy, Side.SELL, OrderType.LIMIT, "502.00", 1);
			assertThat(third.rejection()).isEqualTo(RejectionCode.ORDER_LIMIT_EXCEEDED);
			assertThat(orderState(third.orderId())).isEqualTo("REJECTED");
			asOwner("UPDATE simulation SET max_open_orders_per_account = ?", perAccount);

			int resting = jdbc.queryForObject(
					"SELECT count(*) FROM orders WHERE state IN ('OPEN', 'PARTIALLY_FILLED')", Integer.class);
			asOwner("UPDATE simulation SET max_open_orders_total = ?", resting + 2);
			UUID seller = funded();
			UUID buyer = funded();
			PlacementResult ask = place(seller, Side.SELL, OrderType.LIMIT, "490.00", 2);
			PlacementResult bid = place(buyer, Side.BUY, OrderType.LIMIT, "10.00", 1);
			assertThat(ask.rejection()).isNull();
			assertThat(bid.rejection()).isNull();
			// Full now: another order that would rest is refused, whichever account sends it.
			assertThat(place(buyer, Side.BUY, OrderType.LIMIT, "11.00", 1).rejection())
				.isEqualTo(RejectionCode.ORDER_LIMIT_EXCEEDED);
			// An order that trades in full rests nothing, so it is not refused.
			PlacementResult taker = place(buyer, Side.BUY, OrderType.LIMIT, "490.00", 1);
			assertThat(taker.rejection()).isNull();
			assertThat(orderState(taker.orderId())).isEqualTo("FILLED");
			// The ask still rests with one share left, so the market is still full.
			assertThat(place(buyer, Side.BUY, OrderType.LIMIT, "12.00", 1).rejection())
				.isEqualTo(RejectionCode.ORDER_LIMIT_EXCEEDED);
			// A cancel makes room, and a restart rebuilds the count with the books.
			engine.cancel(key(), new CancelOrder(bid.orderId(), buyer)).get();
			assertThat(place(buyer, Side.BUY, OrderType.LIMIT, "13.00", 1).rejection()).isNull();
			restartEngine();
			assertThat(place(buyer, Side.BUY, OrderType.LIMIT, "14.00", 1).rejection())
				.isEqualTo(RejectionCode.ORDER_LIMIT_EXCEEDED);
		}
		finally {
			asOwner("UPDATE simulation SET max_open_orders_per_account = ?, max_open_orders_total = ?", perAccount, total);
		}
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

		failAt.set("flush written");
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
	/**
	 * The snapshot's tape is exactly the newest trades in the database, newest first, as snapshots used to query them.
	 * Called right after the command, so nothing newer exists yet.
	 */
	private void assertTapeMatchesDatabase(MarketSnapshot snapshot) {
		List<PublicTrade> stored = jdbc.query("""
				SELECT t.id, t.trade_sequence, t.price_cents, t.quantity, t.executed_at, o.side
				FROM trades t JOIN orders o ON o.id = t.taker_order_id
				WHERE t.instrument_id = ? ORDER BY t.trade_sequence DESC LIMIT 50""",
				(row, index) -> new PublicTrade(row.getObject("id", UUID.class), row.getLong("trade_sequence"),
						row.getLong("price_cents"), row.getLong("quantity"), AggressorSide.valueOf(row.getString("side")),
						row.getTimestamp("executed_at").toInstant()),
				instrument);
		assertThat(snapshot.getRecentTrades()).isEqualTo(stored);
		assertThat(snapshot.getLastTrade()).isEqualTo(stored.isEmpty() ? null : stored.getFirst());
	}

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
	/** Commits for real, then reports the outcome as unknown when {@link #loseAnswer} is set. */
	private TransactionRunner losingAnswers(TransactionRunner real) {
		return new TransactionRunner() {
			@Override
			public <T> T inTransaction(Supplier<T> work) {
				T result = real.inTransaction(work);
				if (loseAnswer.getAndSet(false)) {
					throw new CommitOutcomeUnknownException(new IOException("connection lost after the commit"));
				}
				return result;
			}
		};
	}

	/** Counts as the books do, and throws an Error where {@link #failBookAt} says, after the command committed. */
	private final class FailingRestingOrders extends RestingOrders {

		@Override
		public void added(UUID accountId) {
			if ("added".equals(failBookAt.get())) {
				throw new AssertionError("injected error while applying");
			}
			if (!"miscount".equals(failBookAt.get())) {
				super.added(accountId);
			}
		}

		@Override
		public void removed(UUID accountId) {
			if ("removed".equals(failBookAt.get())) {
				throw new AssertionError("injected error while applying");
			}
			super.removed(accountId);
		}

	}

	private TradingStore failingBeforeCommit(TradingStore store) {
		return (TradingStore) Proxy.newProxyInstance(TradingStore.class.getClassLoader(),
				new Class<?>[] { TradingStore.class }, (proxy, method, args) -> {
					if (method.getName().equals(failAt.get())) {
						throw new IllegalStateException("injected failure before commit");
					}
					Object answer;
					try {
						answer = method.invoke(store, args);
					}
					catch (InvocationTargetException ex) {
						throw ex.getCause();
					}
					if ((method.getName() + " written").equals(failAt.get())) {
						throw new IllegalStateException("injected failure before commit, its rows written");
					}
					return answer;
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

	/**
	 * The process dies after a commit, before the book changes or the answer is sent; here the commit succeeds and its
	 * outcome is then lost. The writer halts rather than trade on a book it can't trust. A restart rebuilds the book with
	 * the order, and the same key returns the committed answer without applying anything twice.
	 */
	@Test
	void aCommitWhoseAnswerIsLostIsRecoveredByARestartAndItsKey() throws Exception {
		UUID seller = funded();
		UUID buyer = funded();
		place(seller, Side.SELL, OrderType.LIMIT, "100.00", 10);
		CommandKey key = key(buyer, UUID.randomUUID(), "buy 4 at 100.00");
		PlaceOrder order = order(buyer, Side.BUY, OrderType.LIMIT, "100.00", 4);

		loseAnswer.set(true);
		CompletableFuture<PlacementResult> lost = engine.place(key, order);

		assertThatThrownBy(lost::get).hasCauseInstanceOf(WriterHaltException.class);
		assertThat(writer.isAvailable()).as("the writer stops").isFalse();
		assertThat(jdbc.queryForObject("SELECT state FROM orders WHERE id = ?", String.class, order.orderId()))
			.as("the order committed")
			.isEqualTo("FILLED");
		Map<String, Object> committed = snapshot();

		restartEngine();
		PlacementResult answer = engine.place(key, order).get();

		assertThat(answer.state()).isEqualTo(OrderState.FILLED);
		assertThat(answer.filled()).isEqualTo(4);
		assertThat(snapshot()).as("nothing applied twice").isEqualTo(committed);
		assertThat(engine.depth(instrument, Side.SELL).get())
			.as("the rebuilt book has the fill")
			.containsExactly(new DepthLevel(Price.parse("100.00"), 6, 1));
		assertThat(cash(buyer)).containsExactly(STARTING_CASH - 40_000, 0L);
		assertLedgerMatchesBalances();
	}

	/**
	 * A restarted process takes requests before its startup has rebuilt the books; clients retrying across a crash
	 * arrive then. Run on an engine with no epoch and no books, a command would fence the writer for good, so it is
	 * refused without being queued, and the same key goes through once startup has finished.
	 */
	@Test
	void aCommandBeforeStartupIsRefusedAndItsKeyWorksAfter() throws Exception {
		UUID seller = funded();
		PlacementResult resting = place(seller, Side.SELL, OrderType.LIMIT, "100.00", 10);
		CommandKey key = key(seller, UUID.randomUUID(), "cancel the sell");
		CancelOrder cancel = new CancelOrder(resting.orderId(), seller);
		replaceEngine();
		Map<String, Object> before = snapshot();

		assertThatThrownBy(() -> engine.cancel(key, cancel)).isInstanceOf(WriterStoppedException.class);
		assertThatThrownBy(() -> engine.depth(instrument, Side.SELL)).isInstanceOf(WriterStoppedException.class);
		assertThat(snapshot()).as("nothing written").isEqualTo(before);

		engine.start();
		CancelResult answer = engine.cancel(key, cancel).get();

		assertThat(writer.isAvailable()).as("the writer still runs").isTrue();
		assertThat(answer.status()).isEqualTo(CancelResult.Status.CANCELED);
		assertThat(answer.canceledQuantity()).isEqualTo(10);
		assertThat(engine.depth(instrument, Side.SELL).get()).isEmpty();
		assertLedgerMatchesBalances();
	}

	/** Simulates a process restart: the old writer stops, a new engine starts from the database. */
	private void restartEngine() throws InterruptedException {
		replaceEngine();
		engine.start();
	}

	/** A new process up to the point where its web server takes requests: the engine exists but hasn't started. */
	private void replaceEngine() throws InterruptedException {
		writer.close(Duration.ofSeconds(5));
		writer = new CommandWriter("test-writer", 1_000, maxRun());
		resting = new FailingRestingOrders();
		engine = new TradingEngine(failingBeforeCommit(store), losingAnswers(new SpringTransactionRunner(transactionManager)),
				writer,
				Clock.systemUTC(), (account) -> {
					throw new UnsupportedOperationException("no bots in these tests");
				}, AccountChanges.NONE, timings, System::nanoTime, resting);
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

	/**
	 * After a commit, the book must take the plan whatever goes wrong; an Error (not only an exception) leaves the book
	 * behind the database just the same, so the writer halts, and a restart rebuilds the book from what committed.
	 */
	@Test
	void anErrorWhileApplyingACommittedCommandHaltsTheWriter() throws Exception {
		UUID seller = funded();
		PlaceOrder sell = order(seller, Side.SELL, OrderType.LIMIT, "100.00", 3);
		failBookAt.set("added");
		try {
			CompletableFuture<PlacementResult> placed = engine.place(key(), sell);

			assertThatThrownBy(placed::get).hasCauseInstanceOf(WriterHaltException.class);
			assertThat(writer.isAvailable()).as("the writer halted").isFalse();
		}
		finally {
			failBookAt.set(null);
		}
		assertThat(orderState(sell.orderId())).as("committed before the book failed").isEqualTo("OPEN");

		restartEngine();
		assertThat(engine.depth(instrument, Side.SELL).get())
			.containsExactly(new DepthLevel(Price.parse("100.00"), 3, 1));

		failBookAt.set("removed");
		try {
			CompletableFuture<CancelResult> canceled = engine.cancel(key(), new CancelOrder(sell.orderId(), seller));

			assertThatThrownBy(canceled::get).hasCauseInstanceOf(WriterHaltException.class);
			assertThat(writer.isAvailable()).as("the writer halted").isFalse();
		}
		finally {
			failBookAt.set(null);
		}
		restartEngine();
		assertThat(engine.depth(instrument, Side.SELL).get()).as("the committed cancel, rebuilt").isEmpty();
	}

	/**
	 * The writer counts each account's resting orders itself instead of asking the database before every order that
	 * would rest. A seeded mix of crossing and resting orders and cancels, with failures injected before some commits,
	 * against a small per-account limit, keeps the count equal to the database's throughout and after a restart.
	 */
	@Test
	void theWriterCountsRestingOrdersExactlyThroughFailuresAndARestart() throws Exception {
		int perAccount = jdbc.queryForObject("SELECT max_open_orders_per_account FROM simulation", Integer.class);
		asOwner("UPDATE simulation SET max_open_orders_per_account = 4");
		try {
			java.util.Random random = new java.util.Random(20_261_117L);
			List<UUID> accounts = new ArrayList<>();
			for (int i = 0; i < 6; i++) {
				accounts.add(funded());
			}
			Map<UUID, List<UUID>> mine = new HashMap<>();
			// Reading the balances, or writing the rows: the flush is the last write of every command.
			String[] failures = { "available", "flush", "flush" };
			int injected = 0;
			int refused = 0;
			int filled = 0;
			for (int step = 1; step <= 1_500; step++) {
				UUID account = accounts.get(random.nextInt(accounts.size()));
				List<UUID> open = mine.computeIfAbsent(account, (ignored) -> new ArrayList<>());
				boolean failing = random.nextInt(20) == 0;
				if (failing) {
					failAt.set(failures[random.nextInt(failures.length)]);
					injected++;
				}
				try {
					if (!open.isEmpty() && random.nextInt(3) == 0) {
						UUID order = open.remove(random.nextInt(open.size()));
						CancelResult result = engine.cancel(key(), new CancelOrder(order, account)).get();
						assertThat(result.status()).isNotEqualTo(CancelResult.Status.NOT_FOUND);
					}
					else {
						Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
						String price = (side == Side.BUY ? 98 + random.nextInt(4) : 100 + random.nextInt(4)) + ".00";
						PlacementResult result = engine
							.place(key(), order(account, side, OrderType.LIMIT, price, 1 + random.nextInt(5)))
							.get();
						if (result.state() == OrderState.REJECTED) {
							refused++;
						}
						if (result.state() == OrderState.OPEN || result.state() == OrderState.PARTIALLY_FILLED) {
							open.add(result.orderId());
						}
						if (result.filled() > 0) {
							filled++;
						}
					}
				}
				catch (java.util.concurrent.ExecutionException ex) {
					assertThat(failing).as("only an injected failure fails a command").isTrue();
					assertThat(ex).hasRootCauseMessage("injected failure before commit");
				}
				finally {
					failAt.set(null);
				}
				if (step % 250 == 0) {
					assertThat(resting.counts()).as("after %d commands", step).isEqualTo(restingInDatabase());
				}
			}
			assertThat(injected).as("failures injected").isGreaterThan(20);
			assertThat(refused).as("orders refused at the per-account limit").isGreaterThan(20);
			assertThat(filled).as("orders that traded").isGreaterThan(100);
			Map<UUID, Integer> inDatabase = restingInDatabase();
			assertThat(accounts).as("none of these accounts above its limit")
				.allSatisfy((account) -> assertThat(inDatabase.getOrDefault(account, 0)).isLessThanOrEqualTo(4));

			List<Long> sequences = jdbc.queryForList(
					"SELECT trade_sequence FROM trades WHERE instrument_id = ? ORDER BY trade_sequence", Long.class,
					instrument);
			assertThat(sequences).as("trades numbered without gaps, through rollbacks")
				.containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, sequences.size()).boxed().toList());
			assertThat(jdbc.queryForObject("SELECT last_trade_sequence FROM instrument WHERE id = ?", Long.class,
					instrument))
				.isEqualTo(sequences.size());
			// Every snapshot names the newest trade committed with or before its own command.
			List<Map<String, Object>> snapshots = jdbc.queryForList("""
					SELECT o.payload, o.command_sequence,
					    (SELECT coalesce(max(t.trade_sequence), 0) FROM trades t
					     WHERE t.instrument_id = ? AND t.command_sequence <= o.command_sequence) AS newest
					FROM outbox o WHERE o.topic = 'mse.market.v1' AND o.event_key = ?""", instrument,
					simulation + "|" + instrument);
			assertThat(snapshots).as("snapshots checked").hasSizeGreaterThan(500);
			for (Map<String, Object> row : snapshots) {
				assertThat(WireEvents.decodeSnapshot((byte[]) row.get("payload")).getLastTradeSequence())
					.as("snapshot at command %s", row.get("command_sequence"))
					.isEqualTo(((Number) row.get("newest")).longValue());
			}

			restartEngine();
			assertThat(resting.counts()).as("rebuilt").isEqualTo(restingInDatabase());
			assertLedgerMatchesBalances();
		}
		finally {
			asOwner("UPDATE simulation SET max_open_orders_per_account = ?", perAccount);
		}
	}

	@Test
	void aCountOfRestingOrdersThatDisagreesWithTheDatabaseStopsStartup() throws Exception {
		place(funded(), Side.SELL, OrderType.LIMIT, "100.00", 2);
		replaceEngine();
		failBookAt.set("miscount");
		try {
			assertThatThrownBy(engine::start).isInstanceOf(ReconciliationException.class)
				.hasMessageContaining("Resting orders per account differ between the books and the database");
		}
		finally {
			failBookAt.set(null);
		}
		restartEngine();
	}

	/** A book made for an instrument added while the writer runs counts into the same per-account total. */
	@Test
	void anInstrumentAddedWhileRunningSharesTheCountOfRestingOrders() throws Exception {
		int perAccount = jdbc.queryForObject("SELECT max_open_orders_per_account FROM simulation", Integer.class);
		UUID operator = UUID.randomUUID();
		TestUsers.insert(jdbc, operator, "op-" + operator.toString().substring(0, 8), "unused", "OPERATOR");
		UUID added = UUID.randomUUID();
		jdbc.update("UPDATE simulation SET state = 'SETUP', opened_at = NULL");
		try {
			assertThat(engine
				.addInstrument(key(),
						new AddInstrument(added, symbol(SYMBOLS.incrementAndGet()), null, Price.parse("50.00"), operator))
				.get()
				.status()).isEqualTo(InstrumentResult.Status.CREATED);
		}
		finally {
			jdbc.update("UPDATE simulation SET state = 'OPEN', opened_at = now()");
		}
		UUID buyer = funded();
		asOwner("UPDATE simulation SET max_open_orders_per_account = 2");
		try {
			PlacementResult first = engine
				.place(key(), new PlaceOrder(UUID.randomUUID(), buyer, added, Side.BUY, OrderType.LIMIT,
						Price.parse("10.00"), new Quantity(1)))
				.get();
			PlacementResult second = place(buyer, Side.BUY, OrderType.LIMIT, "10.00", 1);
			PlacementResult third = engine
				.place(key(), new PlaceOrder(UUID.randomUUID(), buyer, added, Side.BUY, OrderType.LIMIT,
						Price.parse("11.00"), new Quantity(1)))
				.get();

			assertThat(first.rejection()).isNull();
			assertThat(second.rejection()).isNull();
			assertThat(third.rejection()).as("one resting in each book makes two")
				.isEqualTo(RejectionCode.ORDER_LIMIT_EXCEEDED);
			assertThat(resting.of(buyer)).isEqualTo(2);
		}
		finally {
			asOwner("UPDATE simulation SET max_open_orders_per_account = ?", perAccount);
		}
	}

	/** Cash or shares held for a resting order are not available to the next order. */
	@Test
	void whatARestingOrderHoldsIsNotAvailableAgain() throws Exception {
		UUID trader = funded();
		// 1,000 at 99.00 holds 99,000.00 of the 100,000.00, and the sell holds all but 100 of the shares.
		assertThat(STARTING_CASH).isEqualTo(10_000_000);
		assertThat(place(trader, Side.BUY, OrderType.LIMIT, "99.00", 1_000).rejection()).isNull();
		assertThat(place(trader, Side.SELL, OrderType.LIMIT, "900.00", STARTING_SHARES - 100).rejection()).isNull();

		assertThat(place(trader, Side.BUY, OrderType.LIMIT, "100.00", 11).rejection())
			.as("1,100.00 is more than the 1,000.00 left")
			.isEqualTo(RejectionCode.INSUFFICIENT_CASH);
		assertThat(place(trader, Side.BUY, OrderType.LIMIT, "100.00", 10).rejection()).as("exactly what is left")
			.isNull();
		assertThat(place(trader, Side.SELL, OrderType.LIMIT, "900.00", 101).rejection())
			.isEqualTo(RejectionCode.INSUFFICIENT_SHARES);
		assertThat(place(trader, Side.SELL, OrderType.LIMIT, "900.00", 100).rejection()).isNull();
	}

	/**
	 * The writer numbers trades from its own counter and moves the row once, guarded by the value it expects. A row that
	 * moved without it halts the writer instead of committing a trade under a number the row disagrees with, and a
	 * restart refuses to start until the counter names the newest trade again.
	 */
	@Test
	void aTradeCounterOutOfStepHaltsTheWriterAndStopsStartup() throws Exception {
		UUID seller = funded();
		UUID buyer = funded();
		place(seller, Side.SELL, OrderType.LIMIT, "100.00", 5);
		place(buyer, Side.BUY, OrderType.LIMIT, "100.00", 1);
		long counted = jdbc.queryForObject("SELECT last_trade_sequence FROM instrument WHERE id = ?", Long.class,
				instrument);
		asOwner("UPDATE instrument SET last_trade_sequence = last_trade_sequence + 5 WHERE id = ?", instrument);
		try {
			CompletableFuture<PlacementResult> traded = engine.place(key(),
					order(buyer, Side.BUY, OrderType.LIMIT, "100.00", 1));

			assertThatThrownBy(traded::get).hasCauseInstanceOf(WriterHaltException.class);
			assertThat(writer.isAvailable()).isFalse();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM trades WHERE instrument_id = ?", Long.class, instrument))
				.as("the trade rolled back")
				.isEqualTo(1);
			assertThatThrownBy(this::restartEngine).isInstanceOf(ReconciliationException.class)
				.hasMessageContaining("has last trade sequence " + (counted + 5) + " but its newest trade is " + counted);
		}
		finally {
			// From the trades stored, not the value read before: if the guard regressed, the trade committed, and the
			// counter must still name the newest trade for every later test's startup.
			asOwner("""
					UPDATE instrument SET last_trade_sequence =
					    (SELECT coalesce(max(trade_sequence), 0) FROM trades WHERE instrument_id = ?)
					WHERE id = ?""", instrument, instrument);
		}
		restartEngine();
		assertThat(place(buyer, Side.BUY, OrderType.LIMIT, "100.00", 1).filled()).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT max(trade_sequence) FROM trades WHERE instrument_id = ?", Long.class,
				instrument))
			.isEqualTo(counted + 1);
	}

	/**
	 * An edit or an add whose transaction rolls back after its snapshot was written changes nothing the writer keeps:
	 * the next market-wide snapshot still carries the committed instrument, and none for the instrument never added.
	 */
	@Test
	void anInstrumentCommandThatRollsBackLeavesTheCatalogueAsCommitted() throws Exception {
		UUID operator = UUID.randomUUID();
		TestUsers.insert(jdbc, operator, "op-" + operator.toString().substring(0, 8), "unused", "OPERATOR");
		UUID neverAdded = UUID.randomUUID();
		jdbc.update("UPDATE simulation SET state = 'SETUP', opened_at = NULL");
		try {
			for (String last : List.of("flush", "insertAudit")) {
				failAt.set(last);
				try {
					assertThatThrownBy(() -> engine
						.editInstrument(key(),
								new EditInstrument(instrument, Price.parse("123.45"), new DisplayName("Never"), operator))
						.get()).hasRootCauseMessage("injected failure before commit");
					assertThatThrownBy(() -> engine
						.addInstrument(key(), new AddInstrument(neverAdded, symbol(SYMBOLS.incrementAndGet()), null,
								Price.parse("10.00"), operator))
						.get()).hasRootCauseMessage("injected failure before commit");
				}
				finally {
					failAt.set(null);
				}
			}
			MarketTransition opened = engine.control(key(), new MarketControl(MarketControl.Action.OPEN, operator))
				.get();

			assertThat(opened.status()).isEqualTo(MarketTransition.Status.APPLIED);
			List<byte[]> published = jdbc.queryForList("""
					SELECT payload FROM outbox WHERE topic = 'mse.market.v1' AND command_sequence = ?""", byte[].class,
					opened.commandSequence());
			List<MarketSnapshot> snapshots = new ArrayList<>();
			for (byte[] payload : published) {
				snapshots.add(WireEvents.decodeSnapshot(payload));
			}
			assertThat(snapshots).extracting(MarketSnapshot::getInstrumentId).doesNotContain(neverAdded).contains(instrument);
			MarketSnapshot ours = snapshots.stream()
				.filter((snapshot) -> snapshot.getInstrumentId().equals(instrument))
				.findFirst()
				.orElseThrow();
			assertThat(ours.getRules().getReferencePriceCents()).isEqualTo(10_000);
			assertThat(ours.getDisplayName()).isNull();
		}
		finally {
			jdbc.update("UPDATE simulation SET state = 'OPEN', opened_at = coalesce(opened_at, now())");
		}
	}

	/**
	 * The writer sends a command's writes in one statement per kind of row. A seeded stream of crossing, resting and
	 * sweeping orders, protected market orders, refusals and cancels, from accounts with and without a position, runs
	 * once through it and once through a reference that sends the same rows one at a time with the SQL used before.
	 * Every row the two runs leave behind is the same, ids and times aside.
	 */
	@Test
	void writesSentOncePerKindLeaveTheSameRowsAsWritesSentOneAtATime() throws Exception {
		Map<String, Object> batched = differentialRun();
		UUID second = UUID.randomUUID();
		jdbc.update("INSERT INTO instrument (id, simulation_id, symbol, reference_price_cents) VALUES (?, ?, ?, 10000)",
				second, simulation, symbol(SYMBOLS.incrementAndGet()));
		instrument = second;
		writer.close(Duration.ofSeconds(5));
		writer = new CommandWriter("test-writer", 1_000);
		resting = new FailingRestingOrders();
		engine = new TradingEngine(rowByRow(store), new SpringTransactionRunner(transactionManager), writer,
				Clock.systemUTC(), (account) -> {
					throw new UnsupportedOperationException("no bots in these tests");
				}, AccountChanges.NONE, timings, System::nanoTime, resting);
		engine.start();
		Map<String, Object> oneAtATime = differentialRun();

		assertThat(batched.get("steps")).as("the stream did what it is for").isEqualTo(oneAtATime.get("steps"));
		for (String part : List.of("orders", "trades", "ledger", "balances", "versions", "events")) {
			assertThat(batched.get(part)).as(part).isEqualTo(oneAtATime.get(part));
		}
	}

	/** 3,000 seeded commands on the current instrument and new accounts; what they left, ids replaced by positions. */
	private Map<String, Object> differentialRun() throws Exception {
		java.util.Random random = new java.util.Random(4_242L);
		List<UUID> accounts = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			accounts.add(funded());
		}
		accounts.add(fundedWithCashOnly());
		List<UUID> stepOrders = new ArrayList<>();
		Map<Long, Integer> stepOfSequence = new HashMap<>();
		Map<UUID, List<UUID>> open = new HashMap<>();
		Map<String, Integer> did = new java.util.TreeMap<>();
		for (int step = 0; step < 3_000; step++) {
			int who = random.nextInt(accounts.size());
			UUID account = accounts.get(who);
			List<UUID> mine = open.computeIfAbsent(account, (ignored) -> new ArrayList<>());
			int roll = random.nextInt(100);
			if (roll < 25 && !mine.isEmpty()) {
				UUID order = mine.remove(random.nextInt(mine.size()));
				CancelResult result = engine.cancel(key(), new CancelOrder(order, account)).get();
				stepOrders.add(null);
				stepOfSequence.put(result.commandSequence(), step);
				did.merge("cancel " + result.status(), 1, Integer::sum);
				continue;
			}
			// The cash-only account only buys; it has no shares to sell.
			Side side = who == accounts.size() - 1 || random.nextBoolean() ? Side.BUY : Side.SELL;
			OrderType type = roll >= 90 ? OrderType.MARKET_PROTECTED : OrderType.LIMIT;
			long quantity = roll >= 85 && roll < 90 ? 8 + random.nextInt(8) : 1 + random.nextInt(4);
			String bound = (side == Side.BUY ? 98 + random.nextInt(5) : 99 + random.nextInt(5)) + "." + random.nextInt(10)
					+ "0";
			if (roll >= 97) {
				bound = "900.00";
				quantity = 10_000;
			}
			PlacementResult result = engine
				.place(key(), new PlaceOrder(UUID.randomUUID(), account, instrument, side, type, Price.parse(bound),
						new Quantity(quantity)))
				.get();
			stepOrders.add(result.orderId());
			stepOfSequence.put(result.commandSequence(), step);
			did.merge(result.state() + (result.filled() > 0 ? " with fills" : ""), 1, Integer::sum);
			if (result.state() == OrderState.OPEN || result.state() == OrderState.PARTIALLY_FILLED) {
				mine.add(result.orderId());
			}
		}
		assertThat(did.getOrDefault("FILLED with fills", 0)).as("orders that traded in full").isGreaterThan(300);
		// Both runs share the engine, so check its trades against their orders too: the buyer is the buying side's.
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM trades t
				JOIN orders taker ON taker.id = t.taker_order_id JOIN orders maker ON maker.id = t.maker_order_id
				WHERE t.instrument_id = ? AND (taker.side = maker.side
				    OR t.buyer_account_id <> CASE WHEN taker.side = 'BUY' THEN taker.account_id ELSE maker.account_id END
				    OR t.seller_account_id <> CASE WHEN taker.side = 'SELL' THEN taker.account_id ELSE maker.account_id END)""",
				Long.class, instrument)).as("trades whose buyer or seller is not their orders'").isZero();
		assertThat(did.getOrDefault("REJECTED", 0)).as("orders refused").isGreaterThan(20);
		assertThat(did.getOrDefault("cancel CANCELED", 0)).as("cancels").isGreaterThan(300);

		Map<UUID, Integer> accountIndex = new HashMap<>();
		for (int i = 0; i < accounts.size(); i++) {
			accountIndex.put(accounts.get(i), i);
		}
		Map<UUID, Integer> orderIndex = new HashMap<>();
		for (int i = 0; i < stepOrders.size(); i++) {
			if (stepOrders.get(i) != null) {
				orderIndex.put(stepOrders.get(i), i);
			}
		}
		Map<String, Object> footprint = new java.util.LinkedHashMap<>();
		footprint.put("steps", did);
		List<String> orders = new ArrayList<>();
		for (UUID order : stepOrders) {
			orders.add(order == null ? "cancel" : jdbc.queryForObject("""
					SELECT state || ' ' || filled_quantity || ' ' || working_quantity || ' ' || canceled_quantity || ' '
					    || coalesce(reason, '-') || ' v' || version FROM orders WHERE id = ?""", String.class, order));
		}
		footprint.put("orders", orders);
		footprint.put("trades", jdbc.query("""
				SELECT trade_sequence, maker_order_id, taker_order_id, buyer_account_id, seller_account_id, price_cents,
				    quantity
				FROM trades WHERE instrument_id = ? ORDER BY trade_sequence""",
				(row, index) -> row.getLong(1) + " maker " + orderIndex.get(row.getObject(2, UUID.class)) + " taker "
						+ orderIndex.get(row.getObject(3, UUID.class)) + " buyer "
						+ accountIndex.get(row.getObject(4, UUID.class)) + " seller "
						+ accountIndex.get(row.getObject(5, UUID.class)) + " " + row.getLong(6) + " x " + row.getLong(7),
				instrument));
		// Each command's postings, as a sorted list: the order of rows inside one command is not part of the result.
		Map<Integer, List<String>> ledger = new java.util.TreeMap<>();
		jdbc.query("""
				SELECT b.command_sequence, b.cause, t.trade_sequence, e.account_id, e.instrument_id, e.bucket, e.delta
				FROM ledger_entry e JOIN journal_batch b ON b.id = e.batch_id LEFT JOIN trades t ON t.id = b.trade_id
				WHERE e.account_id IN (?, ?, ?, ?, ?, ?) AND b.cause <> 'GENESIS'""", (row) -> {
			ledger.computeIfAbsent(stepOfSequence.get(row.getLong(1)), (ignored) -> new ArrayList<>())
				.add(row.getString(2) + " trade " + row.getObject(3) + " account "
						+ accountIndex.get(row.getObject(4, UUID.class)) + " "
						+ (row.getObject(5) == null ? "cash" : "shares") + " " + row.getString(6) + " " + row.getLong(7));
		}, accounts.toArray());
		ledger.values().forEach(java.util.Collections::sort);
		footprint.put("ledger", ledger);
		List<String> balances = new ArrayList<>();
		List<String> versions = new ArrayList<>();
		for (UUID account : accounts) {
			balances.add(cash(account) + " " + jdbc.queryForList(
					"SELECT total_quantity || '/' || reserved_quantity FROM position WHERE account_id = ? AND instrument_id = ?",
					String.class, account, instrument));
			versions.add(jdbc.queryForObject("SELECT version FROM account WHERE id = ?", Long.class, account).toString());
		}
		footprint.put("balances", balances);
		footprint.put("versions", versions);
		List<String> events = new ArrayList<>();
		for (Map<String, Object> row : jdbc.queryForList(
				"SELECT topic, payload, command_sequence FROM outbox WHERE event_key = ? ORDER BY id",
				simulation + "|" + instrument)) {
			byte[] payload = (byte[]) row.get("payload");
			int at = stepOfSequence.getOrDefault(((Number) row.get("command_sequence")).longValue(), -1);
			if ("mse.trades.v1".equals(row.get("topic"))) {
				TradeExecuted trade = WireEvents.decodeTrade(payload);
				events.add(at + " trade " + trade.getTradeSequence() + " " + trade.getPriceCents() + " x "
						+ trade.getQuantity());
			}
			else {
				MarketSnapshot snapshot = WireEvents.decodeSnapshot(payload);
				events.add(at + " snapshot last " + snapshot.getLastTradeSequence() + " bids " + snapshot.getBids()
						+ " asks " + snapshot.getAsks() + " tape "
						+ snapshot.getRecentTrades().stream().map(PublicTrade::getTradeSequence).toList());
			}
		}
		footprint.put("events", events);
		return footprint;
	}

	/**
	 * The book matched an order that the database no longer has resting: the fill changes nothing, and the writer halts
	 * rather than trade on a book out of step. A restart rebuilds the book from what the database holds.
	 */
	@Test
	void aFillAgainstAnOrderTheDatabaseNoLongerHasRestingHaltsTheWriter() throws Exception {
		UUID seller = funded();
		UUID buyer = funded();
		PlacementResult sell = place(seller, Side.SELL, OrderType.LIMIT, "100.00", 5);
		Map<String, Object> before = snapshot();
		asOwner("UPDATE orders SET state = 'FILLED', working_quantity = 0, filled_quantity = 5 WHERE id = ?",
				sell.orderId());
		try {
			CompletableFuture<PlacementResult> traded = engine.place(key(),
					order(buyer, Side.BUY, OrderType.LIMIT, "100.00", 2));

			assertThatThrownBy(traded::get).hasCauseInstanceOf(WriterHaltException.class)
				.rootCause()
				.hasMessageContaining("Filled 0 of 1 resting orders the book matched");
			assertThat(writer.isAvailable()).isFalse();
		}
		finally {
			asOwner("UPDATE orders SET state = 'OPEN', working_quantity = 5, filled_quantity = 0 WHERE id = ?",
					sell.orderId());
		}
		assertThat(snapshot()).as("nothing committed").isEqualTo(before);
		restartEngine();
		assertThat(place(buyer, Side.BUY, OrderType.LIMIT, "100.00", 2).filled()).isEqualTo(2);
	}

	/**
	 * A cancel of an order the book holds updates the row without reading it, guarded by what the book says: still
	 * resting, the caller's, with exactly the book's quantity working. A row that differs halts the writer.
	 */
	@Test
	void aCancelOfAnOrderTheDatabaseHoldsDifferentlyHaltsTheWriter() throws Exception {
		UUID seller = funded();
		PlacementResult sell = place(seller, Side.SELL, OrderType.LIMIT, "100.00", 5);
		Map<String, Object> before = snapshot();
		asOwner("UPDATE orders SET working_quantity = 3, canceled_quantity = 2 WHERE id = ?", sell.orderId());
		try {
			CompletableFuture<CancelResult> canceled = engine.cancel(key(), new CancelOrder(sell.orderId(), seller));

			assertThatThrownBy(canceled::get).hasCauseInstanceOf(WriterHaltException.class);
			assertThat(writer.isAvailable()).isFalse();
		}
		finally {
			asOwner("UPDATE orders SET working_quantity = 5, canceled_quantity = 0 WHERE id = ?", sell.orderId());
		}
		assertThat(snapshot()).as("nothing committed").isEqualTo(before);
		restartEngine();
		assertThat(engine.cancel(key(), new CancelOrder(sell.orderId(), seller)).get().status())
			.isEqualTo(CancelResult.Status.CANCELED);
	}

	/** An order the database has resting but the book does not: the book is out of step, so the writer halts. */
	@Test
	void aCancelOfAnOrderRestingOnlyInTheDatabaseHaltsTheWriter() throws Exception {
		UUID seller = funded();
		UUID hidden = UUID.randomUUID();
		asOwner("""
				INSERT INTO orders (id, simulation_id, account_id, instrument_id, side, type, time_in_force, bound_cents,
				    requested_quantity, working_quantity, state, accepted_sequence)
				VALUES (?, ?, ?, ?, 'SELL', 'LIMIT', 'GTC', 50000, 2, 2, 'OPEN', ?)""", hidden, simulation, seller, instrument,
				9_000_000_000_000L + SYMBOLS.incrementAndGet());
		Map<String, Object> before = snapshot();
		try {
			CompletableFuture<CancelResult> canceled = engine.cancel(key(), new CancelOrder(hidden, seller));

			assertThatThrownBy(canceled::get).hasCauseInstanceOf(WriterHaltException.class);
			assertThat(writer.isAvailable()).isFalse();
			// Halted before the commit: no answer stored, no sequence taken. A halt after committing an answer would
			// leave a false one to replay for the key.
			assertThat(snapshot()).as("nothing committed").isEqualTo(before);
			assertThat(orderState(hidden)).isEqualTo("OPEN");
		}
		finally {
			// Canceled, it holds nothing, so the next startup's reconciliation has nothing to find.
			asOwner("""
					UPDATE orders SET state = 'CANCELED', working_quantity = 0, canceled_quantity = 2, reason = 'TEST'
					WHERE id = ?""", hidden);
		}
		restartEngine();
	}

	/** An account with cash and no position at all: its first buy creates its position row. */
	private UUID fundedWithCashOnly() {
		return new TransactionTemplate(transactionManager).execute((status) -> {
			UUID account = UUID.randomUUID();
			jdbc.update("INSERT INTO account (id, simulation_id, kind, display_name) VALUES (?, ?, 'BOT', 'Bot')",
					account, simulation);
			UUID batch = UUID.randomUUID();
			jdbc.update(
					"INSERT INTO journal_batch (id, simulation_id, cause, command_sequence) VALUES (?, ?, 'GENESIS', 1)",
					batch, simulation);
			String post = "INSERT INTO ledger_entry (batch_id, account_id, instrument_id, bucket, delta) VALUES (?, ?, ?, 'AVAILABLE', ?)";
			jdbc.update(post, batch, issuance, null, -STARTING_CASH);
			jdbc.update(post, batch, account, null, STARTING_CASH);
			jdbc.update(
					"INSERT INTO cash_balance (account_id, account_kind, total_cents, reserved_cents) VALUES (?, 'BOT', ?, 0)",
					account, STARTING_CASH);
			return account;
		});
	}

	/**
	 * The store, but a transaction's write set is sent one row at a time, with the statements the writer used before it
	 * collected them, as the reference the statements per kind of row must match. Outbox events go one per statement.
	 */
	private TradingStore rowByRow(TradingStore real) {
		JdbcClient client = JdbcClient.create(dataSource);
		return (TradingStore) Proxy.newProxyInstance(TradingStore.class.getClassLoader(),
				new Class<?>[] { TradingStore.class }, (proxy, method, args) -> {
					if (method.getName().equals("flush")) {
						flushRowByRow(client, real, (UUID) args[0], (WriteSet) args[1]);
						return null;
					}
					try {
						return method.invoke(real, args);
					}
					catch (InvocationTargetException ex) {
						throw ex.getCause();
					}
				});
	}

	private static void flushRowByRow(JdbcClient jdbc, TradingStore real, UUID simulationId, WriteSet writes) {
		for (WriteSet.TradeSequence move : writes.tradeSequences()) {
			one(jdbc.sql("UPDATE instrument SET last_trade_sequence = ? WHERE id = ? AND last_trade_sequence = ?")
				.params(move.to(), move.instrumentId(), move.from())
				.update());
		}
		for (TradingStore.NewOrder order : writes.orders()) {
			com.mia_princz.mse.execution.trading.domain.OrderRequest request = order.request();
			one(jdbc.sql("""
					INSERT INTO orders (id, simulation_id, account_id, instrument_id, side, type, time_in_force, bound_cents,
					    requested_quantity, filled_quantity, working_quantity, canceled_quantity, state, reason, accepted_sequence)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
				.params(order.id(), order.simulationId(), request.accountId(), order.instrumentId(), request.side().name(),
						request.type().name(), request.type() == OrderType.LIMIT ? "GTC" : "IOC", request.bound().cents(),
						request.quantity().shares(), order.filled(), order.working(), order.canceled(),
						order.state().name(), order.reason(), order.acceptedSequence())
				.update());
		}
		for (WriteSet.MakerFill fill : writes.makerFills()) {
			one(jdbc.sql("""
					UPDATE orders SET filled_quantity = filled_quantity + :quantity,
					    working_quantity = working_quantity - :quantity,
					    state = CASE WHEN working_quantity = :quantity THEN 'FILLED' ELSE 'PARTIALLY_FILLED' END,
					    version = version + :fills, updated_at = now()
					WHERE id = :id AND state IN ('OPEN', 'PARTIALLY_FILLED') AND working_quantity >= :quantity""")
				.param("quantity", fill.quantity())
				.param("fills", fill.fills())
				.param("id", fill.orderId())
				.update());
		}
		for (WriteSet.Cancel cancel : writes.cancels()) {
			one(jdbc.sql("""
					UPDATE orders SET canceled_quantity = canceled_quantity + :quantity, working_quantity = 0,
					    state = 'CANCELED', reason = :reason, version = version + 1, updated_at = now()
					WHERE id = :id AND account_id = :account AND working_quantity = :quantity
					    AND state IN ('OPEN', 'PARTIALLY_FILLED')""")
				.param("quantity", cancel.quantity())
				.param("reason", cancel.reason())
				.param("id", cancel.orderId())
				.param("account", cancel.accountId())
				.update());
		}
		for (WriteSet.NewTrade trade : writes.trades()) {
			one(jdbc.sql("""
					INSERT INTO trades (id, simulation_id, instrument_id, trade_sequence, maker_order_id, taker_order_id,
					    buyer_account_id, seller_account_id, price_cents, quantity, executed_at, command_sequence)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
				.params(trade.id(), simulationId, trade.instrumentId(), trade.tradeSequence(), trade.makerOrderId(),
						trade.takerOrderId(), trade.buyerAccountId(), trade.sellerAccountId(), trade.priceCents(),
						trade.quantity(), java.sql.Timestamp.from(trade.executedAt()), trade.commandSequence())
				.update());
		}
		for (WriteSet.Batch batch : writes.batches()) {
			one(jdbc.sql(
					"INSERT INTO journal_batch (id, simulation_id, cause, command_sequence, trade_id) VALUES (?, ?, ?, ?, ?)")
				.params(batch.id(), simulationId, batch.cause().name(), batch.commandSequence(), batch.tradeId())
				.update());
			for (com.mia_princz.mse.execution.trading.domain.Posting posting : batch.postings()) {
				one(jdbc.sql(
						"INSERT INTO ledger_entry (batch_id, account_id, instrument_id, bucket, delta) VALUES (?, ?, ?, ?, ?)")
					.params(batch.id(), posting.accountId(), posting.instrumentId(), posting.bucket().name(),
							posting.delta())
					.update());
			}
		}
		writes.balances().forEach((key, change) -> {
			if (key.instrumentId() == null) {
				if (jdbc.sql("""
						UPDATE cash_balance SET total_cents = total_cents + ?, reserved_cents = reserved_cents + ?
						WHERE account_id = ?""").params(change.totalDelta(), change.reservedDelta(), key.accountId())
					.update() == 0) {
					one(jdbc.sql("""
							INSERT INTO cash_balance (account_id, account_kind, total_cents, reserved_cents)
							SELECT id, kind, ?, ? FROM account WHERE id = ?""")
						.params(change.totalDelta(), change.reservedDelta(), key.accountId())
						.update());
				}
				return;
			}
			if (jdbc.sql("""
					UPDATE position SET total_quantity = total_quantity + ?, reserved_quantity = reserved_quantity + ?
					WHERE account_id = ? AND instrument_id = ?""")
				.params(change.totalDelta(), change.reservedDelta(), key.accountId(), key.instrumentId())
				.update() == 0) {
				one(jdbc.sql("""
						INSERT INTO position (account_id, account_kind, instrument_id, total_quantity, reserved_quantity)
						SELECT id, kind, ?, ?, ? FROM account WHERE id = ?""")
					.params(key.instrumentId(), change.totalDelta(), change.reservedDelta(), key.accountId())
					.update());
			}
		});
		writes.accounts().forEach((account, commands) -> one(
				jdbc.sql("UPDATE account SET version = version + ? WHERE id = ?").params(commands, account).update()));
		for (WriteSet.Event event : writes.events()) {
			WriteSet single = new WriteSet();
			switch (event) {
				case WriteSet.TradeEvent trade -> single.tradeEvent(trade.trade(), trade.symbol());
				case WriteSet.Snapshot snapshot -> single.snapshot(snapshot.view());
			}
			real.flush(simulationId, single);
		}
		JsonMapper json = JsonMapper.builder().build();
		for (WriteSet.Result result : writes.results()) {
			CommandKey key = result.key();
			one(jdbc.sql("""
					INSERT INTO command_result (simulation_id, principal_id, idempotency_key, route, request_hash,
					    response_status, response_body, command_sequence)
					VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)""")
				.params(simulationId, key.principalId(), key.idempotencyKey(), key.route(), key.requestHash(),
						result.httpStatus(), json.writeValueAsString(result.result()), result.commandSequence())
				.update());
		}
	}

	private static void one(int rows) {
		assertThat(rows).as("rows a reference statement changed").isEqualTo(1);
	}

	private Map<UUID, Integer> restingInDatabase() {
		Map<UUID, Integer> counts = new HashMap<>();
		jdbc.query("SELECT account_id, count(*) FROM orders WHERE state IN ('OPEN', 'PARTIALLY_FILLED') GROUP BY account_id",
				(row) -> {
					counts.put(row.getObject(1, UUID.class), row.getInt(2));
				});
		return counts;
	}

	@Test
	void aReplayOrAConflictTakesNoSequenceSoTheNextCommandFollowsOn() throws Exception {
		UUID buyer = funded();
		UUID idempotencyKey = UUID.randomUUID();
		CommandKey key = key(buyer, idempotencyKey, "buy 1 at 10.00");
		PlaceOrder order = order(buyer, Side.BUY, OrderType.LIMIT, "10.00", 1);
		PlacementResult first = engine.place(key, order).get();
		assertThat(commandSequence()).isEqualTo(first.commandSequence());

		assertThat(engine.place(key, order).get().commandSequence()).isEqualTo(first.commandSequence());
		assertThatThrownBy(() -> engine
			.place(key(buyer, idempotencyKey, "buy 2 at 10.00"), order(buyer, Side.BUY, OrderType.LIMIT, "10.00", 2))
			.get()).hasCauseInstanceOf(IdempotencyConflictException.class);
		CancelResult cancel = engine.cancel(key(), new CancelOrder(first.orderId(), buyer)).get();

		assertThat(cancel.commandSequence()).as("gap-free after a replay and a conflict")
			.isEqualTo(first.commandSequence() + 1);
		assertThat(commandSequence()).isEqualTo(cancel.commandSequence());
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

		failAt.set("flush written");
		assertThatThrownBy(() -> engine.place(key, order).get())
			.hasRootCauseMessage("injected failure before commit, its rows written");
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
