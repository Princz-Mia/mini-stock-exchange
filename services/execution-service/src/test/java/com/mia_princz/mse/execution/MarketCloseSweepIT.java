package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

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

import tools.jackson.databind.json.JsonMapper;

import com.mia_princz.mse.contracts.events.MarketSnapshot;
import com.mia_princz.mse.contracts.events.VenueState;
import com.mia_princz.mse.contracts.events.WireEvents;
import com.mia_princz.mse.execution.identity.ApiTokens;
import com.mia_princz.mse.execution.trading.adapter.JdbcTradingStore;
import com.mia_princz.mse.execution.trading.adapter.SpringTransactionRunner;
import com.mia_princz.mse.execution.trading.application.AccountChanges;
import com.mia_princz.mse.execution.trading.application.BotCredentials;
import com.mia_princz.mse.execution.trading.application.CommandKey;
import com.mia_princz.mse.execution.trading.application.CommandWriter;
import com.mia_princz.mse.execution.trading.application.MarketClosing;
import com.mia_princz.mse.execution.trading.application.MarketControl;
import com.mia_princz.mse.execution.trading.application.PlaceOrder;
import com.mia_princz.mse.execution.trading.application.PlacementResult;
import com.mia_princz.mse.execution.trading.application.TradingEngine;
import com.mia_princz.mse.execution.trading.application.TradingStore;
import com.mia_princz.mse.execution.trading.application.TransactionRunner;
import com.mia_princz.mse.execution.trading.application.WriterTimings;
import com.mia_princz.mse.execution.trading.domain.DepthLevel;
import com.mia_princz.mse.execution.trading.domain.OrderType;
import com.mia_princz.mse.execution.trading.domain.Price;
import com.mia_princz.mse.execution.trading.domain.Quantity;
import com.mia_princz.mse.execution.trading.domain.RejectionCode;
import com.mia_princz.mse.execution.trading.domain.RestingOrders;
import com.mia_princz.mse.execution.trading.domain.Side;

/**
 * A close that does not go through in one piece, against real PostgreSQL, with engines the test builds so that it can
 * stop one where a process would stop. Each test has a market of its own: the one before it is closed, and a new
 * simulation is inserted beside it, which is what starting a new simulation will do. A close here cancels two orders
 * per transaction; the clock is fixed at a time no database clock shows, so a time the close wrote can be told from
 * one the database chose.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "mse.test.database=market-close-sweep")
class MarketCloseSweepIT {

	private static final Instant NOW = Instant.parse("2031-03-04T05:06:07.123456Z");

	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	private static final String CLOSE_ROUTE = "POST /api/v1/admin/market/close";

	private static final long CASH = 10_000_000;

	private static final long SHARES = 1_000;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	DataSource dataSource;

	@Autowired
	PlatformTransactionManager transactionManager;

	private TradingStore store;

	private StoppableTransactions transactions;

	private CommandWriter writer;

	private UUID operator;

	private UUID simulation;

	private UUID issuance;

	private UUID first;

	private UUID second;

	/** What each engine told the account streams: a command's sequence and its accounts, in the order told. */
	private final List<Map.Entry<Long, Set<UUID>>> told = new ArrayList<>();

	/** The accounts whose token files each close asked to have removed. */
	private final List<List<UUID>> discarded = new ArrayList<>();

	/** A process that stopped: nothing after it runs. Not a failure the engine knows, as a real stop is none. */
	private static final class ProcessStopped extends RuntimeException {

		private static final long serialVersionUID = 1L;

	}

	/**
	 * Real transactions that the test can end early. {@code stopAfter} transactions from now the process stops: the
	 * next one never starts. {@code rollBackIn} transactions from now one does all its work and then fails before its
	 * commit, so it rolls back.
	 */
	private final class StoppableTransactions implements TransactionRunner {

		private final TransactionRunner real = new SpringTransactionRunner(transactionManager);

		final AtomicInteger stopAfter = new AtomicInteger(-1);

		final AtomicInteger rollBackIn = new AtomicInteger(-1);

		@Override
		public <T> T inTransaction(Supplier<T> work) {
			if (stopAfter.get() == 0) {
				throw new ProcessStopped();
			}
			stopAfter.updateAndGet((left) -> left > 0 ? left - 1 : left);
			if (rollBackIn.getAndUpdate((left) -> left >= 0 ? left - 1 : left) == 0) {
				return real.inTransaction(() -> {
					work.get();
					throw new IllegalStateException("injected failure before the commit");
				});
			}
			return real.inTransaction(work);
		}

	}

	@BeforeEach
	void market() {
		store = new JdbcTradingStore(JdbcClient.create(dataSource), JsonMapper.builder().build());
		transactions = new StoppableTransactions();
		operator = jdbc.queryForList("SELECT id FROM app_user WHERE username = 'sweep.operator'", UUID.class)
			.stream()
			.findFirst()
			.orElseGet(() -> {
				UUID id = UUID.randomUUID();
				TestUsers.insert(jdbc, id, "sweep.operator", TestBrowser.passwordHash("test-sweep-password"), "OPERATOR");
				return id;
			});
		newSimulation();
	}

	@AfterEach
	void stop() throws InterruptedException {
		if (writer != null) {
			writer.close(Duration.ofSeconds(5));
		}
	}

	/**
	 * A halted market with five orders of four accounts resting on two instruments, one partially filled, and a run its
	 * operator had stopped but no worker had confirmed. The process stops right after the close itself commits: the
	 * market is closed, its runs are ended and its tokens revoked, two orders are cancelled, and the other three still
	 * rest with their holds, which reconciles. The next process finishes the sweep while it starts, in two more parts,
	 * before it takes a command.
	 */
	@Test
	void aCloseWhileHaltedThatStopsBetweenPartsIsFinishedAtStartup() throws Exception {
		UUID a = funded();
		UUID b = funded();
		UUID c = funded();
		UUID bot = funded();
		UUID token = UUID.randomUUID();
		jdbc.update("INSERT INTO api_token (id, account_id, account_kind, token_hash, scope) VALUES (?, ?, 'BOT', ?, 'TRADE')",
				token, bot, ApiTokens.issue().hash());
		UUID run = stoppingRun(bot);
		TradingEngine engine = engine();
		engine.start();
		UUID ninety = place(engine, a, first, Side.BUY, "90.00", 5).orderId();
		UUID ninetyFive = place(engine, b, first, Side.BUY, "95.00", 3).orderId();
		UUID partial = place(engine, c, first, Side.SELL, "100.00", 6).orderId();
		assertThat(place(engine, a, first, Side.BUY, "100.00", 2).state()).isEqualTo(PlacementResult.OrderState.FILLED);
		UUID offer = place(engine, bot, second, Side.SELL, "55.00", 4).orderId();
		UUID bid = place(engine, b, second, Side.BUY, "45.00", 2).orderId();
		engine.control(key("POST /api/v1/admin/market/halt"),
				new MarketControl(MarketControl.Action.HALT, operator)).get(5, TimeUnit.SECONDS);
		Map<UUID, List<Long>> before = totals();
		long halted = sequence();
		told.clear();

		CommandKey closeKey = key(CLOSE_ROUTE);
		transactions.stopAfter.set(1);
		assertThatThrownBy(() -> engine.close(closeKey, new MarketControl(MarketControl.Action.CLOSE, operator))
			.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(ProcessStopped.class);

		// Between two parts: closed for good, with what the first transaction cancelled and nothing more.
		long close = halted + 1;
		assertThat(sequence()).isEqualTo(close);
		assertThat(jdbc.queryForObject("SELECT state FROM simulation WHERE id = ?", String.class, simulation))
			.isEqualTo("CLOSED");
		assertThat(jdbc.queryForObject("SELECT closed_at FROM simulation WHERE id = ?", Timestamp.class, simulation)
			.toInstant()).as("the service's clock, not the database's").isEqualTo(NOW);
		assertThat(states()).containsEntry(ninetyFive, "CANCELED MARKET_CLOSED")
			.containsEntry(ninety, "CANCELED MARKET_CLOSED")
			.containsEntry(partial, "PARTIALLY_FILLED null")
			.containsEntry(offer, "OPEN null")
			.containsEntry(bid, "OPEN null");
		assertThat(reserved()).as("the orders not yet cancelled still hold").isEqualTo(4 + 4 + 2 * 4_500);
		assertThat(reconciliationProblems()).as("between two parts").isEmpty();
		assertThat(told).containsExactly(Map.entry(close, Set.of(a, b)));
		assertThat(snapshots(close)).containsOnlyKeys(first, second)
			.allSatisfy((instrument, snapshot) -> assertThat(snapshot.getVenueState()).isEqualTo(VenueState.CLOSED));
		// The run keeps the stop its operator asked for, and ends with the close; the token is revoked with it.
		Map<String, Object> ended = jdbc.queryForMap("SELECT * FROM bot_run WHERE id = ?", run);
		assertThat(ended.get("ended_by")).isEqualTo("MARKET_CLOSE");
		assertThat(ended.get("stop_sequence")).isEqualTo(2L);
		assertThat(ended.get("end_sequence")).isEqualTo(close);
		assertThat(((Timestamp) ended.get("stop_requested_at")).toInstant()).isEqualTo(NOW.minusSeconds(1_800));
		assertThat(((Timestamp) ended.get("ended_at")).toInstant()).isEqualTo(NOW);
		assertThat(((Timestamp) ended.get("stopped_at")).toInstant()).isEqualTo(NOW);
		assertThat(jdbc.queryForObject("SELECT revoked_at FROM api_token WHERE id = ?", Timestamp.class, token).toInstant())
			.isEqualTo(NOW);
		assertThat(discarded).as("the close had not finished").isEmpty();

		// The next process: it finishes the sweep before it takes a command.
		told.clear();
		TradingEngine next = restarted();
		next.start();

		assertThat(sequence()).as("two more parts").isEqualTo(close + 2);
		assertThat(states().values()).filteredOn((state) -> state.startsWith("OPEN") || state.startsWith("PARTIALLY"))
			.isEmpty();
		assertThat(states()).containsEntry(partial, "CANCELED MARKET_CLOSED")
			.containsEntry(offer, "CANCELED MARKET_CLOSED")
			.containsEntry(bid, "CANCELED MARKET_CLOSED");
		assertThat(reserved()).isZero();
		assertThat(totals()).as("no cash or share moved").isEqualTo(before);
		assertThat(reconciliationProblems()).isEmpty();
		assertThat(told).containsExactly(Map.entry(close + 1, Set.of(b, c)), Map.entry(close + 2, Set.of(bot)));
		assertThat(snapshots(close + 1)).containsOnlyKeys(first, second);
		assertThat(snapshots(close + 2)).containsOnlyKeys(second)
			.allSatisfy((instrument, snapshot) -> {
				assertThat(snapshot.getVenueState()).isEqualTo(VenueState.CLOSED);
				assertThat(snapshot.getAsks()).isEmpty();
			});
		assertThat(jdbc.queryForList("""
				SELECT command_sequence || ' ' || action || ' ' || outcome || ' ' || (actor_user_id = ?) FROM audit_log
				WHERE simulation_id = ? AND command_sequence >= ? ORDER BY command_sequence""", String.class, operator,
				simulation, close)).containsExactly(close + " MARKET_CLOSE APPLIED true",
						(close + 1) + " MARKET_CLOSE_SWEEP APPLIED true", (close + 2) + " MARKET_CLOSE_SWEEP APPLIED true");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM command_result WHERE simulation_id = ?", Long.class,
				simulation)).as("one stored answer for each sequence").isEqualTo(close + 2);
		for (UUID instrument : List.of(first, second)) {
			for (Side side : Side.values()) {
				assertThat(next.depth(instrument, side).get(5, TimeUnit.SECONDS)).isEmpty();
			}
		}
		assertThat(discarded).hasSize(1);
		assertThat(discarded.getFirst()).as("every bot account of the market").containsExactlyInAnyOrder(a, b, c, bot);

		// The close's key, sent again to the new process, has the answer the stopped one stored.
		MarketClosing stored = next.close(closeKey, new MarketControl(MarketControl.Action.CLOSE, operator))
			.get(5, TimeUnit.SECONDS);
		assertThat(stored).isEqualTo(new MarketClosing(MarketClosing.Status.CLOSED, "CLOSED", close, 5, 1));
		assertThat(place(next, a, first, Side.BUY, "90.00", 1).rejection()).isEqualTo(RejectionCode.MARKET_CLOSED);
		long after = sequence();
		restarted().start();
		assertThat(sequence()).as("a later start finds nothing to finish").isEqualTo(after);
	}

	/**
	 * A transaction of the close that fails before its commit rolls back whole, and the books, which change only after
	 * a commit, still hold what the database holds. So the same key can be sent again: after the close's own
	 * transaction failed, it closes the market as if for the first time; after a later part failed, it finds its stored
	 * answer and cancels the orders that still rest.
	 */
	@Test
	void aPartThatFailsLeavesTheBooksAsTheDatabaseAndTheSameKeyFinishesTheClose() throws Exception {
		UUID a = funded();
		UUID b = funded();
		TradingEngine engine = engine();
		engine.start();
		List<UUID> orders = new ArrayList<>();
		for (String price : new String[] { "90.00", "91.00", "92.00" }) {
			orders.add(place(engine, a, first, Side.BUY, price, 1).orderId());
		}
		orders.add(place(engine, b, first, Side.SELL, "120.00", 7).orderId());
		orders.add(place(engine, b, second, Side.SELL, "60.00", 3).orderId());
		long open = sequence();
		List<DepthLevel> bids = engine.depth(first, Side.BUY).get(5, TimeUnit.SECONDS);
		CommandKey closeKey = key(CLOSE_ROUTE);
		MarketControl close = new MarketControl(MarketControl.Action.CLOSE, operator);

		// The close's own transaction fails: nothing of it is there, and the books are as they were.
		transactions.rollBackIn.set(0);
		assertThatThrownBy(() -> engine.close(closeKey, close).get(5, TimeUnit.SECONDS))
			.hasCauseInstanceOf(IllegalStateException.class);
		assertThat(sequence()).isEqualTo(open);
		assertThat(jdbc.queryForObject("SELECT state FROM simulation WHERE id = ?", String.class, simulation))
			.isEqualTo("OPEN");
		assertThat(states().values()).containsOnly("OPEN null");
		assertThat(engine.depth(first, Side.BUY).get(5, TimeUnit.SECONDS)).isEqualTo(bids).hasSize(3);
		assertThat(engine.simulationIdUnlessClosed()).isEqualTo(simulation);

		// Now the close commits and its second part fails: two orders are cancelled, three rest, in both places.
		transactions.rollBackIn.set(1);
		assertThatThrownBy(() -> engine.close(closeKey, close).get(5, TimeUnit.SECONDS))
			.hasCauseInstanceOf(IllegalStateException.class);
		assertThat(sequence()).isEqualTo(open + 1);
		assertThat(states().values()).filteredOn("OPEN null"::equals).hasSize(3);
		assertThat(engine.depth(first, Side.BUY).get(5, TimeUnit.SECONDS)).containsExactly(bids.get(2));
		assertThat(engine.depth(first, Side.SELL).get(5, TimeUnit.SECONDS)).hasSize(1);
		assertThat(reconciliationProblems()).isEmpty();
		assertThat(engine.simulationIdUnlessClosed()).isNull();
		assertThat(discarded).isEmpty();

		// The same key again: the stored answer, once nothing rests any more.
		MarketClosing closed = engine.close(closeKey, close).get(5, TimeUnit.SECONDS);
		assertThat(closed).isEqualTo(new MarketClosing(MarketClosing.Status.CLOSED, "CLOSED", open + 1, 5, 0));
		assertThat(sequence()).as("two more parts").isEqualTo(open + 3);
		assertThat(states().values()).containsOnly("CANCELED MARKET_CLOSED");
		assertThat(states().keySet()).containsExactlyInAnyOrderElementsOf(orders);
		assertThat(reserved()).isZero();
		assertThat(reconciliationProblems()).isEmpty();
		assertThat(engine.depth(first, Side.SELL).get(5, TimeUnit.SECONDS)).isEmpty();
		assertThat(engine.depth(second, Side.SELL).get(5, TimeUnit.SECONDS)).isEmpty();
		assertThat(discarded).hasSize(1);
	}

	/**
	 * A close reaches its own simulation only. An older closed simulation here still has what a real close would not
	 * have left: a token never revoked, a run still marked running and an order left open. Closing the market that
	 * came after it changes none of them. That market has one resting order, and the one before it had none, so its
	 * close was a single transaction.
	 */
	@Test
	void aCloseTouchesOnlyItsOwnSimulation() throws Exception {
		UUID olderBot = funded();
		TradingEngine older = engine();
		older.start();
		long before = sequence();
		assertThat(older.close(key(CLOSE_ROUTE), new MarketControl(MarketControl.Action.CLOSE, operator))
			.get(5, TimeUnit.SECONDS)).isEqualTo(new MarketClosing(MarketClosing.Status.CLOSED, "CLOSED", before + 1, 0, 0));
		assertThat(sequence()).as("nothing rested: one transaction").isEqualTo(before + 1);
		assertThat(snapshots(before + 1)).containsOnlyKeys(first, second);
		writer.close(Duration.ofSeconds(5));
		UUID olderSimulation = simulation;
		UUID olderToken = UUID.randomUUID();
		jdbc.update("INSERT INTO api_token (id, account_id, account_kind, token_hash, scope) VALUES (?, ?, 'BOT', ?, 'TRADE')",
				olderToken, olderBot, ApiTokens.issue().hash());
		UUID olderRun = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO bot_run (id, simulation_id, desired_state, seed, commands_per_second, max_order_quantity,
				    max_position, started_by, started_at, start_sequence)
				VALUES (?, ?, 'RUNNING', 1, 1, 10, 100, ?, ?, 1)""", olderRun, olderSimulation, operator,
				Timestamp.from(NOW.minusSeconds(3_600)));
		UUID olderOrder = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO orders (id, simulation_id, account_id, instrument_id, side, type, time_in_force, bound_cents,
				    requested_quantity, filled_quantity, working_quantity, state, accepted_sequence)
				VALUES (?, ?, ?, ?, 'BUY', 'LIMIT', 'GTC', 9000, 5, 0, 5, 'OPEN', 900)""", olderOrder, olderSimulation,
				olderBot, first);

		newSimulation();
		UUID trader = funded();
		TradingEngine engine = engine();
		engine.start();
		UUID resting = place(engine, trader, first, Side.BUY, "90.00", 5).orderId();
		MarketClosing closed = engine.close(key(CLOSE_ROUTE), new MarketControl(MarketControl.Action.CLOSE, operator))
			.get(5, TimeUnit.SECONDS);

		assertThat(closed.canceledOrders()).isEqualTo(1);
		assertThat(closed.endedRuns()).isZero();
		assertThat(states()).containsOnly(Map.entry(resting, "CANCELED MARKET_CLOSED"));
		assertThat(jdbc.queryForObject("SELECT revoked_at FROM api_token WHERE id = ?", Timestamp.class, olderToken))
			.isNull();
		assertThat(jdbc.queryForObject("""
				SELECT desired_state || ' ' || coalesce(ended_by, 'null') || ' ' || coalesce(stopped_at::text, 'null')
				FROM bot_run WHERE id = ?""", String.class, olderRun)).isEqualTo("RUNNING null null");
		assertThat(jdbc.queryForObject("SELECT state FROM orders WHERE id = ?", String.class, olderOrder))
			.isEqualTo("OPEN");
		assertThat(jdbc.queryForObject("SELECT last_command_sequence FROM simulation WHERE id = ?", Long.class,
				olderSimulation)).isEqualTo(before + 1);
		assertThat(discarded).as("each close removes its own market's token files")
			.containsExactly(List.of(olderBot), List.of(trader));
	}

	/** A new market beside the closed ones: open, with two instruments. Whatever was not closed before it is, first. */
	private void newSimulation() {
		jdbc.update("UPDATE simulation SET state = 'CLOSED', closed_at = now() WHERE state <> 'CLOSED'");
		simulation = UUID.randomUUID();
		issuance = UUID.randomUUID();
		first = UUID.randomUUID();
		second = UUID.randomUUID();
		jdbc.update("INSERT INTO simulation (id, state, opened_at) VALUES (?, 'OPEN', now())", simulation);
		jdbc.update("INSERT INTO account (id, simulation_id, kind, display_name) VALUES (?, ?, 'ISSUANCE', 'Issuance')",
				issuance, simulation);
		jdbc.update("INSERT INTO instrument (id, simulation_id, symbol, reference_price_cents) VALUES (?, ?, 'AAA', 10000)",
				first, simulation);
		jdbc.update("INSERT INTO instrument (id, simulation_id, symbol, reference_price_cents) VALUES (?, ?, 'BBB', 5000)",
				second, simulation);
	}

	/** An engine as a process starting on this database has it: nothing in memory yet. */
	private TradingEngine engine() {
		writer = new CommandWriter("close-writer", 1_000);
		BotCredentials credentials = new BotCredentials() {

			@Override
			public byte[] issue(UUID accountId) {
				throw new UnsupportedOperationException("no bots are made in these tests");
			}

			@Override
			public void discard(Collection<UUID> accountIds) {
				discarded.add(List.copyOf(accountIds));
			}

		};
		AccountChanges changes = (accounts, sequence) -> told.add(Map.entry(sequence, Set.copyOf(accounts)));
		return new TradingEngine(store, transactions, writer, CLOCK, credentials, changes, WriterTimings.NONE,
				System::nanoTime, new RestingOrders(), 2);
	}

	/** The process after a stop: the stopped one's writer is gone, and its transactions run to their end again. */
	private TradingEngine restarted() throws InterruptedException {
		writer.close(Duration.ofSeconds(5));
		transactions = new StoppableTransactions();
		return engine();
	}

	/**
	 * An account funded with cash and both instruments by a balanced genesis batch. A bot's, which needs no login; the
	 * engine treats every trading account alike.
	 */
	private UUID funded() {
		String kind = "BOT";
		return new TransactionTemplate(transactionManager).execute((status) -> {
			UUID account = UUID.randomUUID();
			jdbc.update("""
					INSERT INTO account (id, simulation_id, kind, display_name, bot_strategy)
					VALUES (?, ?, 'BOT', 'Bot', 'NOISE')""", account, simulation);
			UUID batch = UUID.randomUUID();
			jdbc.update("INSERT INTO journal_batch (id, simulation_id, cause, command_sequence) VALUES (?, ?, 'GENESIS', 1)",
					batch, simulation);
			String post = "INSERT INTO ledger_entry (batch_id, account_id, instrument_id, bucket, delta) VALUES (?, ?, ?, 'AVAILABLE', ?)";
			jdbc.update(post, batch, issuance, null, -CASH);
			jdbc.update(post, batch, account, null, CASH);
			jdbc.update("INSERT INTO cash_balance (account_id, account_kind, total_cents, reserved_cents) VALUES (?, ?, ?, 0)",
					account, kind, CASH);
			for (UUID instrument : List.of(first, second)) {
				jdbc.update(post, batch, issuance, instrument, -SHARES);
				jdbc.update(post, batch, account, instrument, SHARES);
				jdbc.update("""
						INSERT INTO position (account_id, account_kind, instrument_id, total_quantity, reserved_quantity)
						VALUES (?, ?, ?, ?, 0)""", account, kind, instrument, SHARES);
			}
			return account;
		});
	}

	/** A run whose operator asked it to stop at sequence 2, half an hour ago, and whose worker has not confirmed. */
	private UUID stoppingRun(UUID bot) {
		UUID run = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO bot_run (id, simulation_id, desired_state, seed, commands_per_second, max_order_quantity,
				    max_position, started_by, started_at, start_sequence, stop_requested_at, stop_sequence, worker_id,
				    lease_expires_at)
				VALUES (?, ?, 'STOPPED', 1, 1, 10, 100, ?, ?, 1, ?, 2, 'worker-a', ?)""", run, simulation, operator,
				Timestamp.from(NOW.minusSeconds(3_600)), Timestamp.from(NOW.minusSeconds(1_800)),
				Timestamp.from(NOW.plusSeconds(10)));
		jdbc.update("""
				INSERT INTO bot_run_instrument (run_id, simulation_id, instrument_id, start_trade_sequence)
				VALUES (?, ?, ?, 0)""", run, simulation, first);
		jdbc.update("INSERT INTO bot_assignment (run_id, account_id, account_kind, strategy) VALUES (?, ?, 'BOT', 'NOISE')",
				run, bot);
		return run;
	}

	private PlacementResult place(TradingEngine engine, UUID account, UUID instrument, Side side, String price,
			long quantity) throws Exception {
		return engine.place(new CommandKey(account, UUID.randomUUID(), "POST /api/v1/orders", new byte[32]),
				new PlaceOrder(UUID.randomUUID(), account, instrument, side, OrderType.LIMIT, Price.parse(price),
						new Quantity(quantity)))
			.get(5, TimeUnit.SECONDS);
	}

	private CommandKey key(String route) {
		return new CommandKey(operator, UUID.randomUUID(), route, new byte[32]);
	}

	private long sequence() {
		return jdbc.queryForObject("SELECT last_command_sequence FROM simulation WHERE id = ?", Long.class, simulation);
	}

	/** Each order of this market that was accepted, with its state and reason. */
	private Map<UUID, String> states() {
		Map<UUID, String> states = new HashMap<>();
		jdbc.query("SELECT id, state || ' ' || coalesce(reason, 'null') FROM orders WHERE simulation_id = ? AND state <> 'FILLED'",
				(row) -> {
					states.put(row.getObject(1, UUID.class), row.getString(2));
				}, simulation);
		return states;
	}

	/** Everything this market's accounts hold for orders: cents and shares, added up. */
	private long reserved() {
		return jdbc.queryForObject("""
				SELECT (SELECT coalesce(sum(b.reserved_cents), 0) FROM cash_balance b JOIN account a ON a.id = b.account_id
				        WHERE a.simulation_id = ?)
				    + (SELECT coalesce(sum(p.reserved_quantity), 0) FROM position p JOIN account a ON a.id = p.account_id
				        WHERE a.simulation_id = ?)""", Long.class, simulation, simulation);
	}

	/** Each trading account's cash and its shares of both instruments. */
	private Map<UUID, List<Long>> totals() {
		Map<UUID, List<Long>> totals = new TreeMap<>();
		jdbc.query("""
				SELECT a.id, b.total_cents,
				    (SELECT total_quantity FROM position WHERE account_id = a.id AND instrument_id = ?),
				    (SELECT total_quantity FROM position WHERE account_id = a.id AND instrument_id = ?)
				FROM account a JOIN cash_balance b ON b.account_id = a.id
				WHERE a.simulation_id = ? AND a.kind <> 'ISSUANCE'""", (row) -> {
			totals.put(row.getObject(1, UUID.class), List.of(row.getLong(2), row.getLong(3), row.getLong(4)));
		}, first, second, simulation);
		return totals;
	}

	private List<String> reconciliationProblems() {
		return new TransactionTemplate(transactionManager).execute((status) -> store.reconciliationProblems(simulation));
	}

	private Map<UUID, MarketSnapshot> snapshots(long commandSequence) throws Exception {
		Map<UUID, MarketSnapshot> byInstrument = new HashMap<>();
		for (byte[] payload : jdbc.queryForList("""
				SELECT payload FROM outbox WHERE topic = 'mse.market.v1' AND command_sequence = ? AND event_key LIKE ?""",
				byte[].class, commandSequence, simulation + "|%")) {
			MarketSnapshot snapshot = WireEvents.decodeSnapshot(payload);
			assertThat(byInstrument.put(snapshot.getInstrumentId(), snapshot)).as("one snapshot for each instrument")
				.isNull();
		}
		return byInstrument;
	}

}
