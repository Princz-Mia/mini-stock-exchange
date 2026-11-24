package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

import com.mia_princz.mse.execution.identity.ApiTokens;
import com.mia_princz.mse.execution.trading.adapter.JdbcTradingStore;
import com.mia_princz.mse.execution.trading.adapter.SpringTransactionRunner;
import com.mia_princz.mse.execution.trading.application.AccountChanges;
import com.mia_princz.mse.execution.trading.application.AccountResult;
import com.mia_princz.mse.execution.trading.application.AddInstrument;
import com.mia_princz.mse.execution.trading.application.BotCredentials;
import com.mia_princz.mse.execution.trading.application.CommandKey;
import com.mia_princz.mse.execution.trading.application.CommandWriter;
import com.mia_princz.mse.execution.trading.application.CommandWriter.WriterHaltException;
import com.mia_princz.mse.execution.trading.application.IdempotencyConflictException;
import com.mia_princz.mse.execution.trading.application.InstrumentResult;
import com.mia_princz.mse.execution.trading.application.MarketClosing;
import com.mia_princz.mse.execution.trading.application.MarketControl;
import com.mia_princz.mse.execution.trading.application.MarketTransition;
import com.mia_princz.mse.execution.trading.application.PlaceOrder;
import com.mia_princz.mse.execution.trading.application.PlacementResult;
import com.mia_princz.mse.execution.trading.application.ProvisionAccount;
import com.mia_princz.mse.execution.trading.application.SimulationStart;
import com.mia_princz.mse.execution.trading.application.StartSimulation;
import com.mia_princz.mse.execution.trading.application.TradingEngine;
import com.mia_princz.mse.execution.trading.application.TradingEngine.UnknownAccountException;
import com.mia_princz.mse.execution.trading.application.TradingEngine.UnknownInstrumentException;
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
import com.mia_princz.mse.execution.trading.domain.SimulationName;

/**
 * Starting a simulation where it does not go through in one piece, and what an engine holds afterwards, against real
 * PostgreSQL, with engines the test builds so that it can stop one where a process would stop. Every test begins with
 * a closed, empty simulation as the current one, which its engine loads as a process starting on it would, so each
 * start here is the real command after a close: it retires that row and writes the next.
 * <p>
 * Every market is set up through the engine's own commands, with the same two symbols and the same two traders each
 * time, as an operator sets one simulation up after another. The whole story over HTTP is in
 * {@link SimulationStartIT}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "mse.test.database=simulation-start-engine")
class SimulationStartEngineIT {

	private static final String START_ROUTE = "POST /api/v1/admin/simulations";

	private static final String CLOSE_ROUTE = "POST /api/v1/admin/market/close";

	private static final String OPEN_ROUTE = "POST /api/v1/admin/market/open";

	private static final String HALT_ROUTE = "POST /api/v1/admin/market/halt";

	private static final long CASH = 1_000_000;

	private static final long SHARES = 100;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	DataSource dataSource;

	@Autowired
	PlatformTransactionManager transactionManager;

	private TradingStore store;

	private FailingTransactions transactions;

	private CommandWriter writer;

	private UUID operator;

	/** The closed, empty simulation each test's first engine loads. */
	private UUID closed;

	/** Each simulation an engine said it had moved to, with the one before it. */
	private final List<List<UUID>> told = new ArrayList<>();

	/** Each time an engine removed bot token files: the accounts it removed them for. */
	private final List<Collection<UUID>> discarded = new ArrayList<>();

	/** The command that opened the market {@link #setUp} set up last. */
	private CommandKey opening;

	/** A market set up through the engine: its simulation, its two instruments and its two traders' accounts. */
	private record Market(UUID simulation, UUID nova, UUID orbt, UUID buyer, UUID seller) {
	}

	/**
	 * Real transactions that the test can end badly, each setting counted from the next one the engine asks for, which
	 * is 0. The one at {@link #rollBackIn} does all its work and then fails before its commit. The one at
	 * {@link #loseAnswerIn} commits, and the answer to its commit is lost. The one at {@link #misreportIn} commits and
	 * is then reported as a failure that reads as a rollback: what a writer would see if it took a lost answer for a
	 * refused commit. The one at {@link #holdIn} does all its work, says so, and commits only when released, so that
	 * the test can do something while its locks are held.
	 */
	private final class FailingTransactions implements TransactionRunner {

		private final TransactionRunner real = new SpringTransactionRunner(transactionManager);

		private final AtomicInteger asked = new AtomicInteger();

		private volatile int rollBack = -1;

		private volatile int loseAnswer = -1;

		private volatile int misreport = -1;

		private volatile int hold = -1;

		private final CountDownLatch held = new CountDownLatch(1);

		private final CountDownLatch release = new CountDownLatch(1);

		void holdIn(int transactions) {
			hold = asked.get() + transactions;
		}

		void rollBackIn(int transactions) {
			rollBack = asked.get() + transactions;
		}

		void loseAnswerIn(int transactions) {
			loseAnswer = asked.get() + transactions;
		}

		void misreportIn(int transactions) {
			misreport = asked.get() + transactions;
		}

		@Override
		public <T> T inTransaction(Supplier<T> work) {
			int ordinal = asked.getAndIncrement();
			if (ordinal == rollBack) {
				return real.inTransaction(() -> {
					work.get();
					throw new IllegalStateException("injected failure before the commit");
				});
			}
			if (ordinal == hold) {
				return real.inTransaction(() -> {
					T done = work.get();
					held.countDown();
					try {
						if (!release.await(30, TimeUnit.SECONDS)) {
							throw new IllegalStateException("the held transaction was never released");
						}
					}
					catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
						throw new IllegalStateException(ex);
					}
					return done;
				});
			}
			T result = real.inTransaction(work);
			if (ordinal == loseAnswer) {
				throw new CommitOutcomeUnknownException(new IOException("connection lost after the commit"));
			}
			if (ordinal == misreport) {
				throw new IllegalStateException("injected: a commit that went through, reported as failed");
			}
			return result;
		}

	}

	@BeforeEach
	void aClosedSimulationIsCurrent() {
		store = new JdbcTradingStore(JdbcClient.create(dataSource), JsonMapper.builder().build());
		transactions = new FailingTransactions();
		operator = user("start.operator", "OPERATOR");
		// Whatever a test before left is closed, and a newer closed row with nothing in it becomes the current one.
		jdbc.update("""
				UPDATE simulation SET state = 'CLOSED', opened_at = coalesce(opened_at, now()), closed_at = now()
				WHERE state <> 'CLOSED'""");
		// Its epoch is past the first, as after a few restarts, so that an epoch of 1 is never right by accident.
		closed = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO simulation (id, state, opened_at, closed_at, writer_epoch)
				VALUES (?, 'CLOSED', now(), now(), 5)""", closed);
		jdbc.update("INSERT INTO account (id, simulation_id, kind, display_name) VALUES (?, ?, 'ISSUANCE', 'Issuance')",
				UUID.randomUUID(), closed);
	}

	@AfterEach
	void stop() throws InterruptedException {
		if (writer != null) {
			writer.close(Duration.ofSeconds(5));
		}
	}

	/**
	 * A start whose transaction fails and rolls back changed nothing: no new simulation, the closed row still this
	 * writer's, the engine still on it and still running, nobody told. The key was never answered, so the same key
	 * then starts the simulation.
	 */
	@Test
	void aStartThatRollsBackChangesNothingAndItsKeyStartsLater() throws Exception {
		TradingEngine engine = engine();
		engine.start();
		long epoch = epoch(closed);
		long closedSequence = sequence(closed);
		long simulations = simulations();
		CommandKey key = key(START_ROUTE);
		StartSimulation command = start("Rolled back");

		transactions.rollBackIn(0);
		Throwable failure = catchThrowable(() -> engine.startSimulation(key, command).get(5, TimeUnit.SECONDS));

		assertThat(failure).isInstanceOf(ExecutionException.class);
		assertThat(failure.getCause()).isNotInstanceOf(WriterHaltException.class);
		assertThat(writer.isAvailable()).isTrue();
		assertThat(simulations()).isEqualTo(simulations);
		assertThat(epoch(closed)).as("the closed row is not retired by a start that did not commit").isEqualTo(epoch);
		assertThat(engine.simulationId()).isEqualTo(closed);
		assertThat(engine.simulationIdUnlessClosed()).isNull();
		assertThat(told).isEmpty();

		SimulationStart started = engine.startSimulation(key, command).get(5, TimeUnit.SECONDS);
		assertThat(started.status()).isEqualTo(SimulationStart.Status.STARTED);
		assertThat(engine.simulationId()).isEqualTo(command.simulationId());
		assertThat(engine.simulationIdUnlessClosed()).as("the engine holds a simulation that is not closed")
			.isEqualTo(command.simulationId());
		assertThat(epoch(closed)).isZero();
		assertThat(told).containsExactly(List.of(closed, command.simulationId()));
		// The start's stored answer and its audit row are the new simulation's, at its first sequence.
		assertThat(jdbc.queryForObject("""
				SELECT (simulation_id = ?) || ' ' || command_sequence || ' ' || response_status FROM command_result
				WHERE idempotency_key = ?""", String.class, command.simulationId(), key.idempotencyKey()))
			.isEqualTo("true 1 201");
		assertThat(jdbc.queryForObject("""
				SELECT (simulation_id = ?) || ' ' || command_sequence || ' ' || action || ' ' || outcome FROM audit_log
				WHERE correlation_id = ?""", String.class, command.simulationId(), key.idempotencyKey()))
			.isEqualTo("true 1 SIMULATION_START APPLIED");
		assertThat(sequence(closed)).as("the closed simulation took no command for it").isEqualTo(closedSequence);
	}

	/**
	 * A start whose commit went through without its answer: the writer cannot know, so it halts, still holding the
	 * closed simulation. The next process loads the new simulation and nothing else, and the key that started it gets
	 * its answer from there.
	 */
	@Test
	void aStartWhoseCommitIsUnknownHaltsTheWriterAndTheNextProcessLoadsTheNewSimulation() throws Exception {
		TradingEngine engine = engine();
		engine.start();
		long epoch = epoch(closed);
		assertThat(epoch).as("the closed row's epoch and one more, for this start").isEqualTo(6);
		CommandKey key = key(START_ROUTE);
		StartSimulation command = start("Unknown commit");

		transactions.loseAnswerIn(0);
		Throwable failure = catchThrowable(() -> engine.startSimulation(key, command).get(5, TimeUnit.SECONDS));

		assertThat(failure.getCause()).isInstanceOf(WriterHaltException.class)
			.hasCauseInstanceOf(CommitOutcomeUnknownException.class);
		assertThat(writer.isAvailable()).isFalse();
		assertThat(engine.simulationId()).as("nothing in memory moved for a commit nobody heard of").isEqualTo(closed);
		assertThat(told).isEmpty();
		assertThat(epoch(closed)).isZero();
		assertThat(jdbc.queryForObject("SELECT state || ' ' || writer_epoch || ' ' || last_command_sequence FROM simulation WHERE id = ?",
				String.class, command.simulationId())).isEqualTo("SETUP " + epoch + " 1");

		TradingEngine next = restarted();
		next.start();

		assertThat(next.simulationId()).isEqualTo(command.simulationId());
		assertThat(next.simulationIdUnlessClosed()).isEqualTo(command.simulationId());
		assertThat(epoch(command.simulationId())).as("claimed by the process that took it over").isEqualTo(epoch + 1);
		assertThat(epoch(closed)).as("nothing claims a retired simulation").isZero();
		long simulations = simulations();
		SimulationStart again = next.startSimulation(key, command).get(5, TimeUnit.SECONDS);
		assertThat(again).isEqualTo(new SimulationStart(SimulationStart.Status.STARTED, command.simulationId(),
				command.name().value(), "SETUP", 1));
		assertThat(simulations()).isEqualTo(simulations);
		assertThat(sequence(command.simulationId())).as("a repeated key takes no sequence").isEqualTo(1);
	}

	/**
	 * The fence on the closed row. A writer whose start committed while it was told otherwise still holds the closed
	 * simulation and the epoch that owned it. Without the fence its next command would be written into the closed
	 * simulation, as a command of a market that has a successor. The closed row's epoch is 0, which no writer holds,
	 * so that command finds nothing to lock: the writer halts as any stale writer does, and neither simulation is
	 * written.
	 * <p>
	 * The command sent here is a transition the closed simulation has itself answered before. A transition looks its
	 * key up in the other simulations before it opens, and must not take an answer of the simulation it holds from
	 * there: that answer is read by the opening, under the row's lock, which is what stops a writer that has lost the
	 * row.
	 */
	@Test
	void aWriterThatMissedItsOwnStartCanWriteNothingMoreIntoTheClosedSimulation() throws Exception {
		TradingEngine engine = engine();
		engine.start();
		long epoch = epoch(closed);
		CommandKey answered = key(OPEN_ROUTE);
		MarketTransition refused = engine.control(answered, new MarketControl(MarketControl.Action.OPEN, operator))
			.get(5, TimeUnit.SECONDS);
		assertThat(refused.status()).as("a closed market does not open").isEqualTo(MarketTransition.Status.NOT_ALLOWED);
		long closedSequence = sequence(closed);
		long closedAudited = audited(closed);
		StartSimulation command = start("Missed start");

		transactions.misreportIn(0);
		Throwable failure = catchThrowable(
				() -> engine.startSimulation(key(START_ROUTE), command).get(5, TimeUnit.SECONDS));

		assertThat(failure.getCause()).isNotInstanceOf(WriterHaltException.class);
		assertThat(writer.isAvailable()).as("the writer believes nothing happened").isTrue();
		assertThat(engine.simulationId()).isEqualTo(closed);
		assertThat(epoch(closed)).as("retired in the transaction that started the next").isZero();
		assertThat(epoch(command.simulationId())).isEqualTo(epoch);
		// What a stale process does with the closed simulation's ID and the epoch it had: nothing opens.
		assertThat(store.open(closed, epoch, key("POST /api/v1/admin/market/open"), MarketTransition.class)).isEmpty();
		assertThat(store.openRun(closed, epoch, List.of())).isEmpty();
		assertThat(store.lockOwnedMarket(closed, epoch)).isEmpty();

		Throwable halted = catchThrowable(() -> engine
			.control(answered, new MarketControl(MarketControl.Action.OPEN, operator))
			.get(5, TimeUnit.SECONDS));

		assertThat(halted).as("not the answer the closed simulation once gave").isNotNull();
		assertThat(halted.getCause()).isInstanceOf(WriterHaltException.class);
		assertThat(halted.getCause().getCause()).hasMessageContaining("another writer owns the market");
		assertThat(writer.isAvailable()).isFalse();
		assertThat(sequence(closed)).as("no command of the closed simulation").isEqualTo(closedSequence);
		assertThat(sequence(command.simulationId())).isEqualTo(1);
		assertThat(audited(closed)).isEqualTo(closedAudited);
	}

	/**
	 * After a start the engine holds nothing of the simulation before: no book and no instrument (an order for an old
	 * instrument is for an unknown one, and its depth cannot be read), and no account (an order from an old account
	 * for the new simulation's instrument, which has the same symbol, is refused before any transaction). Opening the
	 * new market writes one snapshot for each of its own instruments and none for an old one. Whoever keeps
	 * something per account is told once, after the commit.
	 */
	@Test
	void afterAStartTheEngineHoldsNoBookInstrumentOrAccountOfTheSimulationBefore() throws Exception {
		TradingEngine engine = engine();
		engine.start();
		Market first = market(engine, "First");
		assertThat(place(engine, first.buyer(), first.nova(), Side.BUY, "90.00", 5).state())
			.isEqualTo(PlacementResult.OrderState.OPEN);
		assertThat(place(engine, first.seller(), first.nova(), Side.SELL, "100.00", 2).state())
			.isEqualTo(PlacementResult.OrderState.OPEN);
		assertThat(place(engine, first.buyer(), first.nova(), Side.BUY, "100.00", 1).state())
			.isEqualTo(PlacementResult.OrderState.FILLED);
		assertThat(depth(engine, first.nova(), Side.BUY)).hasSize(1);
		MarketClosing closing = engine
			.close(key(CLOSE_ROUTE), new MarketControl(MarketControl.Action.CLOSE, operator))
			.get(5, TimeUnit.SECONDS);
		assertThat(closing.canceledOrders()).isEqualTo(2);
		long firstSequence = sequence(first.simulation());
		long orders = orders();
		told.clear();

		StartSimulation command = start("Second");
		SimulationStart started = engine.startSimulation(key(START_ROUTE), command).get(5, TimeUnit.SECONDS);

		assertThat(started.status()).isEqualTo(SimulationStart.Status.STARTED);
		assertThat(told).containsExactly(List.of(first.simulation(), command.simulationId()));
		assertThat(engine.simulationId()).isEqualTo(command.simulationId());
		assertThatThrownBy(() -> depth(engine, first.nova(), Side.BUY)).as("no book of the old instrument")
			.isInstanceOf(ExecutionException.class);
		assertThatThrownBy(() -> place(engine, first.buyer(), first.nova(), Side.BUY, "90.00", 1))
			.hasCauseInstanceOf(UnknownInstrumentException.class);

		// The same symbols and the same traders again, as the operator sets the next simulation up.
		Market second = setUp(engine, command.simulationId());
		assertThat(second.nova()).isNotEqualTo(first.nova());
		assertThat(second.buyer()).isNotEqualTo(first.buyer());
		long opened = sequence(second.simulation());
		assertThat(jdbc.queryForList("""
				SELECT event_key FROM outbox WHERE topic = 'mse.market.v1' AND command_sequence = ? AND event_key LIKE ?
				ORDER BY event_key""", String.class, opened, second.simulation() + "|%"))
			.as("the open's snapshots: the new simulation's two instruments, and nothing of the old catalogue")
			.containsExactlyInAnyOrder(second.simulation() + "|" + second.nova(), second.simulation() + "|" + second.orbt());
		assertThatThrownBy(() -> place(engine, first.buyer(), second.nova(), Side.BUY, "90.00", 1))
			.as("an account of the closed simulation does not trade here")
			.hasCauseInstanceOf(UnknownAccountException.class);
		assertThat(orders()).as("refused before any transaction").isEqualTo(orders);
		assertThat(sequence(second.simulation())).isEqualTo(opened);
		assertThat(writer.isAvailable()).isTrue();

		// The new market trades from its own first sequences, with its own accounts.
		PlacementResult resting = place(engine, second.seller(), second.nova(), Side.SELL, "101.00", 3);
		assertThat(resting.state()).isEqualTo(PlacementResult.OrderState.OPEN);
		assertThat(place(engine, second.buyer(), second.nova(), Side.BUY, "101.00", 1).state())
			.isEqualTo(PlacementResult.OrderState.FILLED);
		assertThat(jdbc.queryForObject("SELECT max(trade_sequence) FROM trades WHERE instrument_id = ?", Long.class,
				second.nova())).as("trades are numbered from 1 again").isEqualTo(1);
		assertThat(depth(engine, second.nova(), Side.SELL)).containsExactly(new DepthLevel(Price.parse("101.00"), 2, 1));
		assertThat(sequence(first.simulation())).as("the closed simulation took no command").isEqualTo(firstSequence);
		assertThat(reconciliationProblems(first.simulation())).isEmpty();
		assertThat(reconciliationProblems(second.simulation())).isEmpty();
	}

	/**
	 * A process that starts with a closed simulation and a newer one beside it loads the newer one only: it rebuilds
	 * and reconciles that one's books and claims that one's epoch. The closed one keeps epoch 0 and its last sequence.
	 * The current simulation is the one that is not closed, even if a closed one was created after it.
	 */
	@Test
	void aRestartWithAClosedSimulationAndANewerOneLoadsTheNewerOnly() throws Exception {
		TradingEngine engine = engine();
		engine.start();
		Market first = market(engine, "Restart first");
		place(engine, first.buyer(), first.nova(), Side.BUY, "80.00", 4);
		engine.close(key(CLOSE_ROUTE), new MarketControl(MarketControl.Action.CLOSE, operator)).get(5, TimeUnit.SECONDS);
		Market second = market(engine, "Restart second");
		UUID resting = place(engine, second.seller(), second.orbt(), Side.SELL, "55.00", 7).orderId();
		long epoch = epoch(second.simulation());
		long firstSequence = sequence(first.simulation());
		long secondSequence = sequence(second.simulation());
		// A closed simulation created later than the one that is not closed does not become the current one.
		UUID later = UUID.randomUUID();
		jdbc.update("INSERT INTO simulation (id, state, opened_at, closed_at) VALUES (?, 'CLOSED', now(), now())", later);
		assertThat(jdbc.queryForObject("""
				SELECT (SELECT created_at FROM simulation WHERE id = ?) > (SELECT created_at FROM simulation WHERE id = ?)""",
				Boolean.class, later, second.simulation())).isTrue();

		TradingEngine next = restarted();
		next.start();

		assertThat(next.simulationId()).isEqualTo(second.simulation());
		assertThat(next.simulationIdUnlessClosed()).isEqualTo(second.simulation());
		assertThat(epoch(second.simulation())).isEqualTo(epoch + 1);
		assertThat(epoch(first.simulation())).as("the closed simulation is not taken over").isZero();
		assertThat(epoch(later)).as("nor is a closed one made later").isZero();
		assertThat(sequence(first.simulation())).isEqualTo(firstSequence);
		assertThat(sequence(second.simulation())).as("a start takes no sequence").isEqualTo(secondSequence);
		assertThat(depth(next, second.orbt(), Side.SELL)).containsExactly(new DepthLevel(Price.parse("55.00"), 7, 1));
		assertThatThrownBy(() -> depth(next, first.nova(), Side.BUY)).isInstanceOf(ExecutionException.class);
		assertThatThrownBy(() -> place(next, first.buyer(), second.nova(), Side.BUY, "90.00", 1))
			.as("the accounts read at startup are the newer simulation's")
			.hasCauseInstanceOf(UnknownAccountException.class);
		PlacementResult bought = place(next, second.buyer(), second.orbt(), Side.BUY, "55.00", 7);
		assertThat(bought.state()).isEqualTo(PlacementResult.OrderState.FILLED);
		assertThat(jdbc.queryForObject("SELECT state FROM orders WHERE id = ?", String.class, resting)).isEqualTo("FILLED");
		assertThat(reconciliationProblems(second.simulation())).isEmpty();
	}

	/**
	 * A start is the one command whose key is looked up in every simulation. A key refused while a market was open is
	 * answered the same after that market has closed and after another simulation has started, and starts nothing;
	 * the key that started a simulation still names that simulation two starts later.
	 */
	@Test
	void aStartsKeyKeepsItsFirstAnswerThroughLaterStarts() throws Exception {
		TradingEngine engine = engine();
		engine.start();
		CommandKey firstKey = key(START_ROUTE);
		StartSimulation firstCommand = start("Keyed first");
		SimulationStart first = engine.startSimulation(firstKey, firstCommand).get(5, TimeUnit.SECONDS);
		Market market = setUp(engine, firstCommand.simulationId());
		CommandKey firstOpening = opening;
		CommandKey refusedKey = key(START_ROUTE);
		StartSimulation refusedCommand = start("Keyed refused");
		SimulationStart refused = engine.startSimulation(refusedKey, refusedCommand).get(5, TimeUnit.SECONDS);
		assertThat(refused.status()).isEqualTo(SimulationStart.Status.NOT_CLOSED);
		assertThat(refused.state()).isEqualTo("OPEN");
		assertThat(refused.commandSequence()).as("a command of the open simulation").isEqualTo(sequence(market.simulation()));

		engine.close(key(CLOSE_ROUTE), new MarketControl(MarketControl.Action.CLOSE, operator)).get(5, TimeUnit.SECONDS);
		long closedSequence = sequence(market.simulation());
		long simulations = simulations();
		// Before a later start: the closed simulation is still current, and the key has its answer there.
		assertThat(engine.startSimulation(refusedKey, refusedCommand).get(5, TimeUnit.SECONDS)).isEqualTo(refused);
		assertThat(simulations()).as("the market has closed, and the refused key still starts nothing").isEqualTo(simulations);

		StartSimulation secondCommand = start("Keyed second");
		engine.startSimulation(key(START_ROUTE), secondCommand).get(5, TimeUnit.SECONDS);
		setUp(engine, secondCommand.simulationId());
		engine.close(key(CLOSE_ROUTE), new MarketControl(MarketControl.Action.CLOSE, operator)).get(5, TimeUnit.SECONDS);
		simulations = simulations();
		long secondSequence = sequence(secondCommand.simulationId());

		// After a later start, with every simulation closed, where a lookup in the current one alone finds neither key.
		assertThat(engine.startSimulation(refusedKey, refusedCommand).get(5, TimeUnit.SECONDS)).isEqualTo(refused);
		assertThat(engine.startSimulation(firstKey, firstCommand).get(5, TimeUnit.SECONDS)).isEqualTo(first);
		assertThat(simulations()).isEqualTo(simulations);
		assertThat(sequence(market.simulation())).isEqualTo(closedSequence);
		assertThat(sequence(secondCommand.simulationId())).isEqualTo(secondSequence);
		assertThat(engine.simulationId()).isEqualTo(secondCommand.simulationId());
		// The key used for another request, in a simulation that is no longer current, is a conflict.
		assertThatThrownBy(() -> engine
			.startSimulation(new CommandKey(operator, firstKey.idempotencyKey(), START_ROUTE, hash("Other")), start("Other"))
			.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(IdempotencyConflictException.class);
		// So is the key of another command altogether: the one that opened the first market, whose stored answer is
		// not a start's and is never read as one.
		assertThatThrownBy(() -> engine
			.startSimulation(new CommandKey(operator, firstOpening.idempotencyKey(), START_ROUTE, new byte[32]),
					start("Opened"))
			.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(IdempotencyConflictException.class);
		assertThat(simulations()).isEqualTo(simulations);
		// A name is taken whatever its case, by a simulation however old; the refusal is the current one's command.
		SimulationStart taken = engine
			.startSimulation(key(START_ROUTE), new StartSimulation(UUID.randomUUID(),
					new SimulationName(firstCommand.name().value().toUpperCase(java.util.Locale.ROOT)), operator))
			.get(5, TimeUnit.SECONDS);
		assertThat(taken.status()).isEqualTo(SimulationStart.Status.NAME_TAKEN);
		assertThat(taken.commandSequence()).isEqualTo(secondSequence + 1);
		assertThat(jdbc.queryForObject("""
				SELECT action || ' ' || outcome FROM audit_log WHERE simulation_id = ? AND command_sequence = ?""",
				String.class, secondCommand.simulationId(), secondSequence + 1)).isEqualTo("SIMULATION_START NOT_ALLOWED");
		assertThat(simulations()).isEqualTo(simulations);

		// A key is one operator's. Another operator's start under the very key that started the first simulation is
		// neither that answer nor a conflict with it: it is a command of its own, and starts the next simulation.
		UUID colleague = user("start.colleague", "OPERATOR");
		StartSimulation theirs = new StartSimulation(UUID.randomUUID(),
				new SimulationName("Keyed theirs " + UUID.randomUUID().toString().substring(0, 8)), colleague);
		SimulationStart third = engine
			.startSimulation(new CommandKey(colleague, firstKey.idempotencyKey(), START_ROUTE, hash("Theirs")), theirs)
			.get(5, TimeUnit.SECONDS);
		assertThat(third.status()).isEqualTo(SimulationStart.Status.STARTED);
		assertThat(third.simulationId()).isEqualTo(theirs.simulationId());
		assertThat(simulations()).isEqualTo(simulations + 1);
		assertThat(engine.startSimulation(firstKey, firstCommand).get(5, TimeUnit.SECONDS))
			.as("and the first operator's key still has its own answer")
			.isEqualTo(first);
	}

	/**
	 * A process that takes the market over while the writer before it is starting a new simulation. Its locking read
	 * chose the closed row, the only one there was, and waited for it; when the start commits, that row is retired
	 * and the new simulation is beside it. The takeover must claim the new simulation, so that the old writer, which
	 * moved to it with the epoch it had, is fenced, and must leave the retired row alone.
	 */
	@Test
	void aTakeoverThatWaitsForAStartClaimsTheNewSimulationAndFencesTheOldWriter() throws Exception {
		TradingEngine old = engine();
		old.start();
		long epoch = epoch(closed);
		long closedSequence = sequence(closed);
		StartSimulation command = start("Raced");
		transactions.holdIn(0);
		CompletableFuture<SimulationStart> starting = old.startSimulation(key(START_ROUTE), command);
		assertThat(transactions.held.await(5, TimeUnit.SECONDS)).as("the start holds the closed row").isTrue();

		CommandWriter nextWriter = new CommandWriter("takeover-writer", 1_000);
		try {
			TradingEngine next = new TradingEngine(store, new SpringTransactionRunner(transactionManager), nextWriter,
					Clock.systemUTC(), (account) -> new byte[0], AccountChanges.NONE, WriterTimings.NONE,
					System::nanoTime, new RestingOrders(), 2, (millis) -> {
					});
			CompletableFuture<Void> takeover = CompletableFuture.runAsync(next::start);
			for (int waited = 0; waitingForALock() == 0; waited++) {
				assertThat(waited).as("the takeover never waited for the row").isLessThan(200);
				assertThat(takeover).isNotDone();
				Thread.sleep(50);
			}
			assertThat(takeover).isNotDone();
			transactions.release.countDown();
			assertThat(starting.get(5, TimeUnit.SECONDS).status()).isEqualTo(SimulationStart.Status.STARTED);
			takeover.get(10, TimeUnit.SECONDS);

			assertThat(next.simulationId()).as("the simulation that is current once the row is held")
				.isEqualTo(command.simulationId());
			assertThat(next.simulationIdUnlessClosed()).isEqualTo(command.simulationId());
			assertThat(epoch(closed)).as("the retired row is claimed by nobody").isZero();
			assertThat(epoch(command.simulationId())).as("the start gave it the old writer's epoch; the takeover adds one")
				.isEqualTo(epoch + 1);

			// The old writer moved to the new simulation with the epoch it had: its next command is fenced.
			assertThat(old.simulationId()).isEqualTo(command.simulationId());
			Throwable halted = catchThrowable(() -> old
				.addInstrument(key("POST /api/v1/admin/instruments"),
						new AddInstrument(UUID.randomUUID(), "NOVA", null, Price.parse("100.00"), operator))
				.get(5, TimeUnit.SECONDS));
			assertThat(halted.getCause()).isInstanceOf(WriterHaltException.class);
			assertThat(halted.getCause().getCause()).hasMessageContaining("another writer owns the market");
			assertThat(writer.isAvailable()).isFalse();

			// The new owner's commands are the new simulation's, from its second sequence.
			InstrumentResult added = next
				.addInstrument(key("POST /api/v1/admin/instruments"),
						new AddInstrument(UUID.randomUUID(), "NOVA", null, Price.parse("100.00"), operator))
				.get(5, TimeUnit.SECONDS);
			assertThat(added.status()).isEqualTo(InstrumentResult.Status.CREATED);
			assertThat(added.commandSequence()).isEqualTo(2);
			assertThat(sequence(command.simulationId())).isEqualTo(2);
			assertThat(sequence(closed)).as("the closed simulation took no command").isEqualTo(closedSequence);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM instrument WHERE simulation_id = ?", Long.class, closed))
				.isZero();
		}
		finally {
			transactions.release.countDown();
			nextWriter.close(Duration.ofSeconds(5));
		}
	}

	/**
	 * Opening, halting, resuming and closing are sent with no body, so a key that was answered in one simulation and
	 * comes again in the next is that command sent again, whatever state the next market is in. It gets the answer it
	 * was first given, takes no sequence, leaves no audit row and changes nothing: an old open does not open a market
	 * still being set up, an old halt does not halt the one that trades, and an old close, the one answer a caller is
	 * told to ask for again, does not close it or remove its bots' token files.
	 */
	@Test
	void aTransitionsKeyFromAnEarlierSimulationGetsItsFirstAnswerAndChangesNothing() throws Exception {
		TradingEngine engine = engine();
		engine.start();
		Market first = market(engine, "Transitions first");
		CommandKey firstOpening = opening;
		CommandKey halting = key(HALT_ROUTE);
		MarketTransition halted = engine.control(halting, new MarketControl(MarketControl.Action.HALT, operator))
			.get(5, TimeUnit.SECONDS);
		assertThat(halted.state()).isEqualTo("HALTED");
		engine.control(key("POST /api/v1/admin/market/resume"), new MarketControl(MarketControl.Action.RESUME, operator))
			.get(5, TimeUnit.SECONDS);
		place(engine, first.buyer(), first.nova(), Side.BUY, "90.00", 5);
		CommandKey closing = key(CLOSE_ROUTE);
		MarketClosing closedFirst = engine.close(closing, new MarketControl(MarketControl.Action.CLOSE, operator))
			.get(5, TimeUnit.SECONDS);
		assertThat(closedFirst.status()).isEqualTo(MarketClosing.Status.CLOSED);
		assertThat(closedFirst.canceledOrders()).isEqualTo(1);
		// The closed market is still the current one: its close's key is answered by it, and tidies up again.
		discarded.clear();
		assertThat(engine.close(closing, new MarketControl(MarketControl.Action.CLOSE, operator)).get(5, TimeUnit.SECONDS))
			.isEqualTo(closedFirst);
		assertThat(discarded).hasSize(1);
		long firstSequence = sequence(first.simulation());

		StartSimulation command = start("Transitions second");
		engine.startSimulation(key(START_ROUTE), command).get(5, TimeUnit.SECONDS);
		UUID second = command.simulationId();
		discarded.clear();

		// While the second market is being set up, the key that opened the first does not open it.
		MarketTransition openedAgain = engine
			.control(firstOpening, new MarketControl(MarketControl.Action.OPEN, operator))
			.get(5, TimeUnit.SECONDS);
		assertThat(openedAgain.status()).isEqualTo(MarketTransition.Status.APPLIED);
		assertThat(openedAgain.commandSequence()).as("the first market's open").isLessThan(halted.commandSequence());
		assertThat(state(second)).isEqualTo("SETUP");
		assertThat(sequence(second)).as("a key answered before takes no sequence").isEqualTo(1);

		Market next = setUp(engine, second);
		UUID resting = place(engine, next.seller(), next.nova(), Side.SELL, "101.00", 3).orderId();
		long secondSequence = sequence(second);
		long audited = audited(second);

		// While it trades, the key that halted the first does not halt it, and the key that closed the first does
		// not close it.
		assertThat(engine.control(halting, new MarketControl(MarketControl.Action.HALT, operator)).get(5, TimeUnit.SECONDS))
			.isEqualTo(halted);
		assertThat(state(second)).isEqualTo("OPEN");
		assertThat(engine.close(closing, new MarketControl(MarketControl.Action.CLOSE, operator)).get(5, TimeUnit.SECONDS))
			.as("the first market's close, with what it cancelled")
			.isEqualTo(closedFirst);
		assertThat(state(second)).isEqualTo("OPEN");
		assertThat(engine.simulationIdUnlessClosed()).isEqualTo(second);
		assertThat(jdbc.queryForObject("SELECT state FROM orders WHERE id = ?", String.class, resting)).isEqualTo("OPEN");
		assertThat(discarded).as("no token file of the market that trades is removed").isEmpty();
		assertThat(sequence(second)).isEqualTo(secondSequence);
		assertThat(audited(second)).isEqualTo(audited);
		assertThat(sequence(first.simulation())).isEqualTo(firstSequence);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM command_result WHERE idempotency_key IN (?, ?, ?)",
				Long.class, firstOpening.idempotencyKey(), halting.idempotencyKey(), closing.idempotencyKey()))
			.as("each has the one answer it was first given")
			.isEqualTo(3);

		// The first market's close, sent as another transition of this one, is a conflict and not a halt.
		assertThatThrownBy(() -> engine
			.control(new CommandKey(operator, closing.idempotencyKey(), HALT_ROUTE, new byte[32]),
					new MarketControl(MarketControl.Action.HALT, operator))
			.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(IdempotencyConflictException.class);
		assertThat(state(second)).isEqualTo("OPEN");
		// A key is one operator's: the same one from another operator is a command of this market.
		UUID colleague = user("start.colleague", "OPERATOR");
		MarketTransition theirs = engine
			.control(new CommandKey(colleague, halting.idempotencyKey(), HALT_ROUTE, new byte[32]),
					new MarketControl(MarketControl.Action.HALT, colleague))
			.get(5, TimeUnit.SECONDS);
		assertThat(theirs.status()).isEqualTo(MarketTransition.Status.APPLIED);
		assertThat(theirs.commandSequence()).isEqualTo(secondSequence + 1);
		assertThat(state(second)).isEqualTo("HALTED");

		// A new close closes it, and from then on its own close tidies up as before.
		MarketClosing closedSecond = engine
			.close(key(CLOSE_ROUTE), new MarketControl(MarketControl.Action.CLOSE, operator))
			.get(5, TimeUnit.SECONDS);
		assertThat(closedSecond.status()).isEqualTo(MarketClosing.Status.CLOSED);
		assertThat(closedSecond.canceledOrders()).isEqualTo(1);
		assertThat(state(second)).isEqualTo("CLOSED");
		assertThat(discarded).hasSize(1);
		assertThat(reconciliationProblems(first.simulation())).isEmpty();
		assertThat(reconciliationProblems(second)).isEmpty();
	}

	/** An engine as a process starting on this database has it: nothing in memory yet. */
	private TradingEngine engine() {
		writer = new CommandWriter("start-writer", 1_000);
		BotCredentials credentials = new BotCredentials() {

			@Override
			public byte[] issue(UUID accountId) {
				return ApiTokens.issue().hash();
			}

			@Override
			public void discard(Collection<UUID> accountIds) {
				discarded.add(accountIds);
			}

		};
		return new TradingEngine(store, transactions, writer, Clock.systemUTC(), credentials, AccountChanges.NONE,
				WriterTimings.NONE, System::nanoTime, new RestingOrders(), 2, (millis) -> {
				}, (previous, simulation) -> told.add(Arrays.asList(previous, simulation)));
	}

	/** The process after a stop: the stopped one's writer is gone, and its transactions run to their end again. */
	private TradingEngine restarted() throws InterruptedException {
		writer.close(Duration.ofSeconds(5));
		transactions = new FailingTransactions();
		told.clear();
		return engine();
	}

	/** Starts a simulation by this name and sets it up: see {@link #setUp}. */
	private Market market(TradingEngine engine, String name) throws Exception {
		StartSimulation command = start(name);
		SimulationStart started = engine.startSimulation(key(START_ROUTE), command).get(5, TimeUnit.SECONDS);
		assertThat(started.status()).as(name).isEqualTo(SimulationStart.Status.STARTED);
		return setUp(engine, command.simulationId());
	}

	/**
	 * Sets the simulation the engine has just started up through its commands, the same way each time: NOVA and ORBT,
	 * the same two traders funded with cash and both instruments, and the market opened.
	 */
	private Market setUp(TradingEngine engine, UUID simulation) throws Exception {
		assertThat(engine.simulationId()).isEqualTo(simulation);
		UUID nova = UUID.randomUUID();
		UUID orbt = UUID.randomUUID();
		for (Object[] listed : new Object[][] { { nova, "NOVA", "100.00" }, { orbt, "ORBT", "50.00" } }) {
			InstrumentResult added = engine
				.addInstrument(key("POST /api/v1/admin/instruments"), new AddInstrument((UUID) listed[0],
						(String) listed[1], null, Price.parse((String) listed[2]), operator))
				.get(5, TimeUnit.SECONDS);
			assertThat(added.status()).as("%s is free in a new simulation", listed[1])
				.isEqualTo(InstrumentResult.Status.CREATED);
		}
		List<ProvisionAccount.Holding> holdings = List.of(new ProvisionAccount.Holding(nova, SHARES),
				new ProvisionAccount.Holding(orbt, SHARES));
		List<UUID> accounts = new ArrayList<>();
		for (String username : List.of("start.buyer", "start.seller")) {
			UUID account = UUID.randomUUID();
			AccountResult provisioned = engine
				.provisionAccount(key("POST /api/v1/admin/accounts"),
						new ProvisionAccount(account, user(username, "TRADER"), username, CASH, holdings, operator))
				.get(5, TimeUnit.SECONDS);
			assertThat(provisioned.status()).as("%s has no account in a new simulation", username)
				.isEqualTo(AccountResult.Status.CREATED);
			accounts.add(account);
		}
		opening = key(OPEN_ROUTE);
		MarketTransition opened = engine.control(opening, new MarketControl(MarketControl.Action.OPEN, operator))
			.get(5, TimeUnit.SECONDS);
		assertThat(opened.status()).isEqualTo(MarketTransition.Status.APPLIED);
		return new Market(simulation, nova, orbt, accounts.get(0), accounts.get(1));
	}

	private StartSimulation start(String name) {
		// Names differ, and this class's database is kept between its tests.
		return new StartSimulation(UUID.randomUUID(),
				new SimulationName(name + " " + UUID.randomUUID().toString().substring(0, 8)), operator);
	}

	/** A login with this role, made the first time a test asks for it. */
	private UUID user(String username, String role) {
		return jdbc.queryForList("SELECT id FROM app_user WHERE username = ?", UUID.class, username)
			.stream()
			.findFirst()
			.orElseGet(() -> {
				UUID id = UUID.randomUUID();
				TestUsers.insert(jdbc, id, username, TestBrowser.passwordHash("test-start-engine-password"), role);
				return id;
			});
	}

	private PlacementResult place(TradingEngine engine, UUID account, UUID instrument, Side side, String price,
			long quantity) throws Exception {
		PlacementResult placed = engine
			.place(new CommandKey(account, UUID.randomUUID(), "POST /api/v1/orders", new byte[32]),
					new PlaceOrder(UUID.randomUUID(), account, instrument, side, OrderType.LIMIT, Price.parse(price),
							new Quantity(quantity)))
			.get(5, TimeUnit.SECONDS);
		assertThat(placed.rejection()).as("%s", placed).isNotEqualTo(RejectionCode.MARKET_NOT_OPEN);
		return placed;
	}

	private static List<DepthLevel> depth(TradingEngine engine, UUID instrument, Side side) throws Exception {
		CompletableFuture<List<DepthLevel>> depth = engine.depth(instrument, side);
		return depth.get(5, TimeUnit.SECONDS);
	}

	private CommandKey key(String route) {
		return new CommandKey(operator, UUID.randomUUID(), route, new byte[32]);
	}

	private static byte[] hash(String request) {
		byte[] hash = new byte[32];
		hash[0] = (byte) request.hashCode();
		hash[1] = 1;
		return hash;
	}

	/** Sessions of this database that are waiting for a lock another transaction holds. */
	private long waitingForALock() {
		return jdbc.queryForObject("""
				SELECT count(*) FROM pg_stat_activity
				WHERE datname = current_database() AND wait_event_type = 'Lock'""", Long.class);
	}

	private String state(UUID simulation) {
		return jdbc.queryForObject("SELECT state FROM simulation WHERE id = ?", String.class, simulation);
	}

	private long audited(UUID simulation) {
		return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE simulation_id = ?", Long.class, simulation);
	}

	private long epoch(UUID simulation) {
		return jdbc.queryForObject("SELECT writer_epoch FROM simulation WHERE id = ?", Long.class, simulation);
	}

	private long sequence(UUID simulation) {
		return jdbc.queryForObject("SELECT last_command_sequence FROM simulation WHERE id = ?", Long.class, simulation);
	}

	private long simulations() {
		return jdbc.queryForObject("SELECT count(*) FROM simulation", Long.class);
	}

	private long orders() {
		return jdbc.queryForObject("SELECT count(*) FROM orders", Long.class);
	}

	private List<String> reconciliationProblems(UUID simulation) {
		return new TransactionTemplate(transactionManager).execute((status) -> store.reconciliationProblems(simulation));
	}

}
