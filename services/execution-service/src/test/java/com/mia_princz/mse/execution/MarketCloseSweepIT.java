package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import com.mia_princz.mse.execution.trading.adapter.TradingStartup;
import com.mia_princz.mse.execution.trading.adapter.WriterOwnership;
import com.mia_princz.mse.execution.trading.application.AccountChanges;
import com.mia_princz.mse.execution.trading.application.AddInstrument;
import com.mia_princz.mse.execution.trading.application.BotCredentials;
import com.mia_princz.mse.execution.trading.application.CommandKey;
import com.mia_princz.mse.execution.trading.application.CommandWriter;
import com.mia_princz.mse.execution.trading.application.CommandWriter.WriterHaltException;
import com.mia_princz.mse.execution.trading.application.CommandWriter.WriterStoppedException;
import com.mia_princz.mse.execution.trading.application.InstrumentResult;
import com.mia_princz.mse.execution.trading.application.MarketClosing;
import com.mia_princz.mse.execution.trading.application.MarketControl;
import com.mia_princz.mse.execution.trading.application.MarketTransition;
import com.mia_princz.mse.execution.trading.application.PlaceOrder;
import com.mia_princz.mse.execution.trading.application.PlacementResult;
import com.mia_princz.mse.execution.trading.application.TradingEngine;
import com.mia_princz.mse.execution.trading.application.TradingEngine.CloseUnfinishedException;
import com.mia_princz.mse.execution.trading.application.TradingStore;
import com.mia_princz.mse.execution.trading.application.TransactionRunner;
import com.mia_princz.mse.execution.trading.application.TransactionRunner.CommitOutcomeUnknownException;
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
 * <p>
 * The rule under test throughout: once a close's own transaction has committed, the writer either cancels every
 * resting order or halts, and the next start finishes before it takes a command. No writer goes on taking commands
 * for a closed market with orders resting.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "mse.test.database=market-close-sweep")
@ExtendWith(OutputCaptureExtension.class)
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

	/** The application's own hold on the market, which a process started by the test shares. */
	@Autowired
	WriterOwnership ownership;

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

	/** Removing token files fails, after it was asked. */
	private volatile boolean discardFails;

	/** Telling the account streams fails that many tellings from now; never while negative. */
	private final AtomicInteger failTellingIn = new AtomicInteger(-1);

	/** A process that stopped: nothing after it runs. Not a failure the engine knows, as a real stop is none. */
	private static final class ProcessStopped extends RuntimeException {

		private static final long serialVersionUID = 1L;

	}

	/**
	 * Real transactions that the test can end early. Each setting counts from the next transaction the engine asks
	 * for, which is 0. From {@link #stopAfter} on none starts: the process is gone, or its database is. The one at
	 * {@link #rollBackIn} does all its work and then fails before its commit, so it rolls back. The one at
	 * {@link #loseAnswerIn} commits, and the answer to its commit is lost. An action given to {@link #before} runs
	 * before its transaction starts, one given to {@link #afterWork} inside it, between its work and its commit.
	 */
	private final class StoppableTransactions implements TransactionRunner {

		private final TransactionRunner real = new SpringTransactionRunner(transactionManager);

		/** How many transactions the engine has asked for, started or not. */
		final AtomicInteger asked = new AtomicInteger();

		private volatile int stopFrom = Integer.MAX_VALUE;

		private volatile int rollBack = -1;

		private volatile int loseAnswer = -1;

		private volatile int beforeAt = -1;

		private volatile Runnable before;

		private volatile int afterWorkAt = -1;

		private volatile Runnable afterWork;

		void stopAfter(int transactions) {
			stopFrom = asked.get() + transactions;
		}

		void rollBackIn(int transactions) {
			rollBack = asked.get() + transactions;
		}

		void loseAnswerIn(int transactions) {
			loseAnswer = asked.get() + transactions;
		}

		void before(int transactions, Runnable action) {
			before = action;
			beforeAt = asked.get() + transactions;
		}

		void afterWork(int transactions, Runnable action) {
			afterWork = action;
			afterWorkAt = asked.get() + transactions;
		}

		@Override
		public <T> T inTransaction(Supplier<T> work) {
			int ordinal = asked.getAndIncrement();
			if (ordinal >= stopFrom) {
				throw new ProcessStopped();
			}
			if (ordinal == beforeAt) {
				before.run();
			}
			if (ordinal == rollBack) {
				return real.inTransaction(() -> {
					work.get();
					throw new IllegalStateException("injected failure before the commit");
				});
			}
			if (ordinal == afterWorkAt) {
				return real.inTransaction(() -> {
					T result = work.get();
					afterWork.run();
					return result;
				});
			}
			T result = real.inTransaction(work);
			if (ordinal == loseAnswer) {
				throw new CommitOutcomeUnknownException(new IOException("connection lost after the commit"));
			}
			return result;
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
	 * operator had stopped but no worker had confirmed. Right after the close itself commits nothing more can be
	 * written: the process is stopping, or its database is gone. The market is closed, its runs are ended and its
	 * tokens revoked, two orders are cancelled, and the other three still rest with their holds, which reconciles. The
	 * writer tries the next part three times and then halts, saying what it left. The next process finishes the sweep
	 * while it starts, in two more parts, before it takes a command.
	 */
	@Test
	void aCloseWhileHaltedThatStopsBetweenPartsIsFinishedAtStartup(CapturedOutput output) throws Exception {
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
		Map<UUID, Long> versions = versions(a, b, c, bot);
		long halted = sequence();
		told.clear();

		CommandKey closeKey = key(CLOSE_ROUTE);
		long close = halted + 1;
		int asked = transactions.asked.get();
		transactions.stopAfter(1);
		assertThat(unfinished(engine.close(closeKey, closeCommand()), close, 3)).isInstanceOf(ProcessStopped.class);
		assertThat(transactions.asked.get() - asked).as("the close, then the next part tried three times").isEqualTo(4);
		assertThat(writer.isAvailable()).as("no writer goes on with a market half closed").isFalse();
		// The halt is on record where it was decided, whoever still waits for the answer, with the last attempt.
		assertThat(errors(output)).anyMatch((line) -> line.contains("giving the close up (attempt 3 of 3)"))
			.anyMatch((line) -> line.contains("The market's close at sequence " + close
					+ " halted the writer before it finished; resting orders left: at most 3."));

		// Between two parts: closed for good, with what the first transaction cancelled and nothing more.
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
		assertThat(risen(versions)).as("one command for each account of the part")
			.isEqualTo(Map.of(a, 1L, b, 1L, c, 0L, bot, 0L));
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

		// The next process: it finishes the sweep before it takes a command. One sent while it is still cancelling,
		// before its third transaction (the rebuild, then who closed, then the first part), is refused, not queued.
		told.clear();
		TradingEngine next = restarted();
		List<Throwable> whileFinishing = new ArrayList<>();
		transactions.before(2,
				() -> whileFinishing.add(catchThrowable(() -> place(next, a, first, Side.BUY, "90.00", 1))));
		next.start();

		assertThat(whileFinishing).hasSize(1);
		assertThat(whileFinishing.getFirst()).isInstanceOf(WriterStoppedException.class)
			.hasMessage("Trading has not started");
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
		assertThat(risen(versions)).as("once for each part that cancelled one of the account's orders, not per order")
			.isEqualTo(Map.of(a, 1L, b, 2L, c, 1L, bot, 1L));
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

		// The close's key, sent again to the new process, has the answer the stopped one stored. It tidies the token
		// files once more: a process may stop after its last part and before it has removed them.
		MarketClosing stored = next.close(closeKey, closeCommand()).get(5, TimeUnit.SECONDS);
		assertThat(stored).isEqualTo(new MarketClosing(MarketClosing.Status.CLOSED, "CLOSED", close, 5, 1));
		assertThat(discarded).hasSize(2);
		assertThat(place(next, a, first, Side.BUY, "90.00", 1).rejection()).isEqualTo(RejectionCode.MARKET_CLOSED);
		long after = sequence();
		restarted().start();
		assertThat(sequence()).as("a later start finds nothing to finish").isEqualTo(after);
	}

	/**
	 * A transaction of the close that fails before its commit rolls back whole, and the books, which change only after
	 * a commit, still hold what the database holds. After the close's own transaction failed nothing is closed, and the
	 * same key closes the market as if for the first time. A later part that fails is tried again by the close itself:
	 * the answer is the one the close would have given, and the attempt that failed left nothing, not even a sequence.
	 */
	@Test
	void aPartThatFailsLeavesTheBooksAsTheDatabaseAndIsTriedAgain() throws Exception {
		UUID a = funded();
		UUID b = funded();
		TradingEngine engine = engine();
		engine.start();
		List<UUID> orders = fiveResting(engine, a, b);
		long open = sequence();
		List<DepthLevel> bids = engine.depth(first, Side.BUY).get(5, TimeUnit.SECONDS);
		CommandKey closeKey = key(CLOSE_ROUTE);

		// The close's own transaction fails: nothing of it is there, the books are as they were, and trading goes on.
		transactions.rollBackIn(0);
		assertThatThrownBy(() -> engine.close(closeKey, closeCommand()).get(20, TimeUnit.SECONDS))
			.hasCauseInstanceOf(IllegalStateException.class);
		assertThat(sequence()).isEqualTo(open);
		assertThat(jdbc.queryForObject("SELECT state FROM simulation WHERE id = ?", String.class, simulation))
			.isEqualTo("OPEN");
		assertThat(states().values()).containsOnly("OPEN null");
		assertThat(engine.depth(first, Side.BUY).get(5, TimeUnit.SECONDS)).isEqualTo(bids).hasSize(3);
		assertThat(engine.simulationIdUnlessClosed()).isEqualTo(simulation);
		assertThat(writer.isAvailable()).isTrue();

		// Now the close commits and its second transaction fails, once. The close tries that part again and answers.
		int asked = transactions.asked.get();
		transactions.rollBackIn(1);
		MarketClosing closed = engine.close(closeKey, closeCommand()).get(20, TimeUnit.SECONDS);

		assertThat(closed).isEqualTo(new MarketClosing(MarketClosing.Status.CLOSED, "CLOSED", open + 1, 5, 0));
		assertThat(transactions.asked.get() - asked)
			.as("the close, the part that failed, the two parts, and the read of whose token files to remove")
			.isEqualTo(5);
		assertThat(sequence()).as("the close and two parts: the attempt that failed took no sequence")
			.isEqualTo(open + 3);
		assertThat(states().keySet()).containsExactlyInAnyOrderElementsOf(orders);
		assertThat(states().values()).containsOnly("CANCELED MARKET_CLOSED");
		assertNothingRests();
		for (UUID instrument : List.of(first, second)) {
			for (Side side : Side.values()) {
				assertThat(engine.depth(instrument, side).get(5, TimeUnit.SECONDS)).isEmpty();
			}
		}
		assertThat(engine.simulationIdUnlessClosed()).isNull();
		assertThat(writer.isAvailable()).as("a failure the close got over does not stop trading").isTrue();
		assertThat(discarded).hasSize(1);
	}

	/**
	 * A later part commits, and the answer to its commit is lost. The books no longer certainly hold what the database
	 * holds, so the part is not tried again: the writer halts at once, as for any command whose commit is unknown. The
	 * part did commit here, and the next start reads what is left from the database and finishes.
	 */
	@Test
	void aPartWhoseCommitIsUnknownHaltsTheWriterAndTheNextStartFinishes() throws Exception {
		UUID a = funded();
		UUID b = funded();
		TradingEngine engine = engine();
		engine.start();
		fiveResting(engine, a, b);
		long open = sequence();
		CommandKey closeKey = key(CLOSE_ROUTE);

		int asked = transactions.asked.get();
		transactions.loseAnswerIn(1);
		assertThat(unfinished(engine.close(closeKey, closeCommand()), open + 1, 3))
			.as("the books were not changed for a commit nobody heard of")
			.isInstanceOf(CommitOutcomeUnknownException.class);
		assertThat(transactions.asked.get() - asked).as("not tried again").isEqualTo(2);
		assertThat(writer.isAvailable()).isFalse();
		assertThat(sequence()).isEqualTo(open + 2);
		assertThat(resting()).isEqualTo(1);
		assertThat(discarded).isEmpty();

		theNextStartFinishesTheClose(closeKey, open + 1, open + 3);
	}

	/**
	 * The close's own commit is the one whose answer is lost. Whether the market closed is not known, so this is a halt
	 * like any other command's, not an unfinished close. It did close here: the next start loads the closed market
	 * with three orders resting and cancels them, and the key has the close's answer.
	 */
	@Test
	void aCloseWhoseOwnCommitIsUnknownHaltsTheWriterAndTheNextStartFinishes(CapturedOutput output) throws Exception {
		UUID a = funded();
		UUID b = funded();
		TradingEngine engine = engine();
		engine.start();
		fiveResting(engine, a, b);
		long open = sequence();
		CommandKey closeKey = key(CLOSE_ROUTE);

		int asked = transactions.asked.get();
		transactions.loseAnswerIn(0);
		assertThat(haltCause(engine.close(closeKey, closeCommand()))).isInstanceOf(CommitOutcomeUnknownException.class);
		assertThat(transactions.asked.get() - asked).isEqualTo(1);
		assertThat(writer.isAvailable()).isFalse();
		assertThat(errors(output)).as("on record, with what the books still hold")
			.anyMatch((line) -> line.contains("The market's close halted the writer in its own transaction")
					&& line.contains("resting orders in the books: 5."));
		assertThat(jdbc.queryForObject("SELECT state FROM simulation WHERE id = ?", String.class, simulation))
			.isEqualTo("CLOSED");
		assertThat(sequence()).isEqualTo(open + 1);
		assertThat(resting()).isEqualTo(3);

		theNextStartFinishesTheClose(closeKey, open + 1, open + 3);
	}

	/**
	 * Another process takes the market over between two parts. The part finds the epoch changed and writes nothing;
	 * it is not tried again, since the market is no longer this writer's, and the writer halts. Whoever starts next
	 * finishes the sweep.
	 */
	@Test
	void aCloseFencedOffBetweenTwoPartsHaltsTheWriterAndTheNextStartFinishes() throws Exception {
		UUID a = funded();
		UUID b = funded();
		TradingEngine engine = engine();
		engine.start();
		fiveResting(engine, a, b);
		long open = sequence();
		CommandKey closeKey = key(CLOSE_ROUTE);

		int asked = transactions.asked.get();
		transactions.before(1,
				() -> jdbc.update("UPDATE simulation SET writer_epoch = writer_epoch + 1 WHERE id = ?", simulation));
		assertThat(unfinished(engine.close(closeKey, closeCommand()), open + 1, 3))
			.hasMessageContaining("writer epoch changed");
		assertThat(transactions.asked.get() - asked).as("not tried again").isEqualTo(2);
		assertThat(writer.isAvailable()).isFalse();
		assertThat(sequence()).isEqualTo(open + 1);
		assertThat(resting()).isEqualTo(3);
		assertThat(reconciliationProblems()).isEmpty();

		theNextStartFinishesTheClose(closeKey, open + 1, open + 3);
	}

	/**
	 * A part has committed, and applying it fails: here the account streams cannot be told. The books may be behind
	 * the database, so the writer halts; the part stays committed, and the next start goes on from it.
	 */
	@Test
	void aCommittedPartThatCannotBeAppliedHaltsTheWriterAndTheNextStartFinishes() throws Exception {
		UUID a = funded();
		UUID b = funded();
		TradingEngine engine = engine();
		engine.start();
		fiveResting(engine, a, b);
		long open = sequence();
		CommandKey closeKey = key(CLOSE_ROUTE);

		// The close's own transaction tells its accounts; the next telling, the first later part's, fails.
		failTellingIn.set(1);
		assertThat(unfinished(engine.close(closeKey, closeCommand()), open + 1, 1))
			.hasMessage("injected failure while telling the accounts");
		assertThat(writer.isAvailable()).isFalse();
		assertThat(sequence()).isEqualTo(open + 2);
		assertThat(resting()).isEqualTo(1);

		theNextStartFinishesTheClose(closeKey, open + 1, open + 3);
	}

	/**
	 * The writer is shut down while a close is in its second transaction, and the time it is given runs out. That
	 * transaction commits, and the close stops there instead of running every part that is left past the shutdown. The
	 * next start finishes.
	 */
	@Test
	void aWriterShutDownDuringACloseStopsBetweenTwoPartsAndTheNextStartFinishes() throws Exception {
		UUID a = funded();
		UUID b = funded();
		TradingEngine engine = engine();
		engine.start();
		fiveResting(engine, a, b);
		long open = sequence();
		CommandKey closeKey = key(CLOSE_ROUTE);

		// The second transaction is held between its work and its commit until the shutdown interrupts the writer.
		CountDownLatch held = new CountDownLatch(1);
		transactions.afterWork(1, () -> {
			held.countDown();
			try {
				new CountDownLatch(1).await();
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		});
		CompletableFuture<MarketClosing> closing = engine.close(closeKey, closeCommand());
		assertThat(held.await(20, TimeUnit.SECONDS)).isTrue();
		writer.close(Duration.ofMillis(100));

		assertThat(unfinished(closing, open + 1, 1)).isInstanceOf(WriterStoppedException.class);
		assertThat(sequence()).as("the part it was in committed, and no other was begun").isEqualTo(open + 2);
		assertThat(resting()).isEqualTo(1);

		theNextStartFinishesTheClose(closeKey, open + 1, open + 3);
	}

	/**
	 * A process that starts on a market closed half-way and cannot finish the close does not start: it fails, its
	 * health stays down, and it takes no command. The process after it finishes.
	 */
	@Test
	void aStartThatCannotFinishTheCloseFailsAndTakesNoCommand() throws Exception {
		UUID a = funded();
		UUID b = funded();
		TradingEngine engine = engine();
		engine.start();
		fiveResting(engine, a, b);
		long open = sequence();
		CommandKey closeKey = key(CLOSE_ROUTE);
		transactions.loseAnswerIn(0);
		haltCause(engine.close(closeKey, closeCommand()));

		// The rebuild and the read of who closed succeed; from the first part on nothing can be written.
		TradingEngine next = restarted();
		transactions.stopAfter(2);
		TradingStartup startup = new TradingStartup(ownership, store, next, writer);
		assertThatThrownBy(() -> startup.run(null)).isInstanceOf(IllegalStateException.class)
			.hasMessage("The market's close could not be finished")
			.hasCauseInstanceOf(ProcessStopped.class);

		assertThat(transactions.asked.get()).as("the part was tried three times").isEqualTo(5);
		assertThat(startup.health().getStatus()).isEqualTo(Status.DOWN);
		assertThatThrownBy(() -> place(next, a, first, Side.BUY, "90.00", 1)).isInstanceOf(WriterStoppedException.class)
			.hasMessage("Trading has not started");
		assertThat(sequence()).isEqualTo(open + 1);
		assertThat(resting()).isEqualTo(3);

		theNextStartFinishesTheClose(closeKey, open + 1, open + 3);
	}

	/**
	 * Removing the token files is tidying. Whatever it runs into, the market has closed and the close says so: first
	 * when the files cannot be removed, then, on the key's second attempt, when the accounts they belong to cannot
	 * even be read.
	 */
	@Test
	void tokenFilesThatCannotBeRemovedDoNotFailTheClose() throws Exception {
		UUID trader = funded();
		TradingEngine engine = engine();
		engine.start();
		place(engine, trader, first, Side.BUY, "90.00", 5);
		long open = sequence();
		CommandKey closeKey = key(CLOSE_ROUTE);
		MarketClosing expected = new MarketClosing(MarketClosing.Status.CLOSED, "CLOSED", open + 1, 1, 0);

		discardFails = true;
		assertThat(engine.close(closeKey, closeCommand()).get(20, TimeUnit.SECONDS)).isEqualTo(expected);
		assertThat(discarded).as("it was asked to").hasSize(1);

		transactions.stopAfter(1);
		assertThat(engine.close(closeKey, closeCommand()).get(20, TimeUnit.SECONDS)).isEqualTo(expected);
		assertThat(discarded).as("this time it could not find out whose").hasSize(1);
		assertThat(writer.isAvailable()).isTrue();
	}

	/**
	 * Which runs a close ends, and when. A run whose worker confirmed its stop long before keeps every time and
	 * sequence it has: the close ends and stamps only what had not ended and what no worker had confirmed. A run that
	 * started after the time the service's clock now shows, because the clock was set back, ends at its own start, not
	 * before it, which the table would refuse and the close with it.
	 */
	@Test
	void aCloseLeavesAConfirmedRunAsItWasAndEndsNoRunBeforeItsStart() throws Exception {
		jdbc.update("UPDATE simulation SET last_command_sequence = 10 WHERE id = ?", simulation);
		UUID confirmed = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO bot_run (id, simulation_id, desired_state, seed, commands_per_second, max_order_quantity,
				    max_position, started_by, started_at, start_sequence, stop_requested_at, stop_sequence, worker_id,
				    stopped_at, end_sequence, ended_by, ended_at)
				VALUES (?, ?, 'STOPPED', 1, 1, 10, 100, ?, ?, 1, ?, 2, 'worker-a', ?, 3, 'WORKER', ?)""", confirmed,
				simulation, operator, Timestamp.from(NOW.minusSeconds(3_600)), Timestamp.from(NOW.minusSeconds(1_800)),
				Timestamp.from(NOW.minusSeconds(1_700)), Timestamp.from(NOW.minusSeconds(1_700)));
		UUID late = UUID.randomUUID();
		Instant started = NOW.plusSeconds(3_600);
		jdbc.update("""
				INSERT INTO bot_run (id, simulation_id, desired_state, seed, commands_per_second, max_order_quantity,
				    max_position, started_by, started_at, start_sequence)
				VALUES (?, ?, 'RUNNING', 1, 1, 10, 100, ?, ?, 4)""", late, simulation, operator,
				Timestamp.from(started));
		Map<String, Object> before = jdbc.queryForMap("SELECT * FROM bot_run WHERE id = ?", confirmed);
		TradingEngine engine = engine();
		engine.start();

		MarketClosing closed = engine.close(key(CLOSE_ROUTE), closeCommand()).get(20, TimeUnit.SECONDS);

		assertThat(closed).isEqualTo(new MarketClosing(MarketClosing.Status.CLOSED, "CLOSED", 11, 0, 1));
		assertThat(jdbc.queryForMap("SELECT * FROM bot_run WHERE id = ?", confirmed)).isEqualTo(before);
		Map<String, Object> ended = jdbc.queryForMap("SELECT * FROM bot_run WHERE id = ?", late);
		assertThat(ended.get("ended_by")).isEqualTo("MARKET_CLOSE");
		assertThat(ended.get("desired_state")).isEqualTo("STOPPED");
		assertThat(ended.get("stop_sequence")).isEqualTo(11L);
		assertThat(ended.get("end_sequence")).isEqualTo(11L);
		assertThat(((Timestamp) ended.get("ended_at")).toInstant()).as("not before it started").isEqualTo(started);
		assertThat(((Timestamp) ended.get("stop_requested_at")).toInstant()).isEqualTo(NOW);
		assertThat(((Timestamp) ended.get("stopped_at")).toInstant()).isEqualTo(NOW);
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

	/**
	 * A process that starts on a database whose only market is closed, through the startup the application itself
	 * runs. It loads that market, as closed. So a setup command is refused by it, as by any market past SETUP; it does
	 * not find "no market" and quietly create a second one, and a transition is not answered as if nothing were set up.
	 */
	@Test
	void aProcessThatStartsOnAClosedMarketLoadsItAndSetupCommandsCreateNoOther() throws Exception {
		TradingEngine engine = engine();
		engine.start();
		engine.close(key(CLOSE_ROUTE), new MarketControl(MarketControl.Action.CLOSE, operator)).get(5, TimeUnit.SECONDS);
		long simulations = jdbc.queryForObject("SELECT count(*) FROM simulation", Long.class);
		long sequence = sequence();

		TradingEngine next = restarted();
		TradingStartup startup = new TradingStartup(ownership, store, next, writer);
		startup.run(null);

		assertThat(startup.health().getStatus()).isEqualTo(Status.UP);
		assertThat(next.simulationId()).isEqualTo(simulation);
		assertThat(next.simulationIdUnlessClosed()).isNull();
		InstrumentResult added = next
			.addInstrument(key("POST /api/v1/admin/instruments"),
					new AddInstrument(UUID.randomUUID(), "LATE", null, Price.parse("1.00"), operator))
			.get(5, TimeUnit.SECONDS);
		assertThat(added.status()).isEqualTo(InstrumentResult.Status.NOT_IN_SETUP);
		MarketTransition opened = next
			.control(key("POST /api/v1/admin/market/open"), new MarketControl(MarketControl.Action.OPEN, operator))
			.get(5, TimeUnit.SECONDS);
		assertThat(opened.status()).isEqualTo(MarketTransition.Status.NOT_ALLOWED);
		assertThat(opened.state()).isEqualTo("CLOSED");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM simulation", Long.class)).isEqualTo(simulations);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM simulation WHERE state <> 'CLOSED'", Long.class)).isZero();
		assertThat(sequence()).as("both refusals are the closed market's own commands").isEqualTo(sequence + 2);
	}

	/** A new market beside the closed ones: open, with two instruments. Whatever was not closed before it is, first. */
	private void newSimulation() {
		jdbc.update("""
				UPDATE simulation SET state = 'CLOSED', opened_at = coalesce(opened_at, now()), closed_at = now()
				WHERE state <> 'CLOSED'""");
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
				if (discardFails) {
					throw new IllegalStateException("injected failure while removing token files");
				}
			}

		};
		AccountChanges changes = (accounts, sequence) -> {
			if (failTellingIn.getAndDecrement() == 0) {
				throw new IllegalStateException("injected failure while telling the accounts");
			}
			told.add(Map.entry(sequence, Set.copyOf(accounts)));
		};
		return new TradingEngine(store, transactions, writer, CLOCK, credentials, changes, WriterTimings.NONE,
				System::nanoTime, new RestingOrders(), 2);
	}

	/** The lines the log holds at ERROR since the test began. */
	private static List<String> errors(CapturedOutput output) {
		return output.getAll().lines().filter((line) -> line.contains("ERROR")).toList();
	}

	private MarketControl closeCommand() {
		return new MarketControl(MarketControl.Action.CLOSE, operator);
	}

	/**
	 * Five resting orders of two accounts, which a close takes three transactions for: the two best bids, then the
	 * third bid and the first instrument's offer, then the second instrument's offer.
	 */
	private List<UUID> fiveResting(TradingEngine engine, UUID a, UUID b) throws Exception {
		List<UUID> orders = new ArrayList<>();
		for (String price : new String[] { "90.00", "91.00", "92.00" }) {
			orders.add(place(engine, a, first, Side.BUY, price, 1).orderId());
		}
		orders.add(place(engine, b, first, Side.SELL, "120.00", 7).orderId());
		orders.add(place(engine, b, second, Side.SELL, "60.00", 3).orderId());
		return orders;
	}

	/** What the writer halted for, once the command's future has failed with the halt. */
	private static Throwable haltCause(CompletableFuture<?> command) {
		Throwable failure = catchThrowable(() -> command.get(20, TimeUnit.SECONDS));
		assertThat(failure).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(WriterHaltException.class);
		return failure.getCause().getCause();
	}

	/**
	 * The close halted the writer as unfinished, naming its own sequence and how many orders its books still held.
	 * Returns what stopped it.
	 */
	private static Throwable unfinished(CompletableFuture<?> close, long sequence, int resting) {
		Throwable cause = haltCause(close);
		assertThat(cause).isInstanceOfSatisfying(CloseUnfinishedException.class, (unfinished) -> {
			assertThat(unfinished.commandSequence()).isEqualTo(sequence);
			assertThat(unfinished.restingOrders()).isEqualTo(resting);
		});
		return cause.getCause();
	}

	/**
	 * The next process finishes the close while it starts, by {@code last}; after that the close's key has the answer
	 * of the whole close of {@link #fiveResting}.
	 */
	private void theNextStartFinishesTheClose(CommandKey closeKey, long close, long last) throws Exception {
		TradingEngine next = restarted();
		next.start();
		assertThat(sequence()).isEqualTo(last);
		assertNothingRests();
		assertThat(discarded).as("the start that finished the close tidied the token files").hasSize(1);
		assertThat(next.close(closeKey, closeCommand()).get(20, TimeUnit.SECONDS))
			.isEqualTo(new MarketClosing(MarketClosing.Status.CLOSED, "CLOSED", close, 5, 0));
		assertThat(sequence()).as("the key's answer was stored by the process that closed").isEqualTo(last);
	}

	/** No order rests and nothing is held, the market reconciles, and every sequence has its one stored answer. */
	private void assertNothingRests() {
		assertThat(resting()).isZero();
		assertThat(reserved()).isZero();
		assertThat(reconciliationProblems()).isEmpty();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM command_result WHERE simulation_id = ?", Long.class,
				simulation)).as("one stored answer for each sequence").isEqualTo(sequence());
	}

	/** How many orders rest in this market, by the database. */
	private long resting() {
		return jdbc.queryForObject("""
				SELECT count(*) FROM orders WHERE simulation_id = ? AND state IN ('OPEN', 'PARTIALLY_FILLED')""",
				Long.class, simulation);
	}

	private Map<UUID, Long> versions(UUID... accounts) {
		Map<UUID, Long> versions = new HashMap<>();
		for (UUID account : accounts) {
			versions.put(account, jdbc.queryForObject("SELECT version FROM account WHERE id = ?", Long.class, account));
		}
		return versions;
	}

	/** By how much each account's version has risen since {@code before} was read. */
	private Map<UUID, Long> risen(Map<UUID, Long> before) {
		Map<UUID, Long> risen = new HashMap<>();
		versions(before.keySet().toArray(UUID[]::new))
			.forEach((account, version) -> risen.put(account, version - before.get(account)));
		return risen;
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
