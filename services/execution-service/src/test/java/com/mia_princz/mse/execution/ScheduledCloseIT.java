package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

import com.mia_princz.mse.execution.trading.adapter.JdbcTradingStore;
import com.mia_princz.mse.execution.trading.adapter.ScheduledClose;
import com.mia_princz.mse.execution.trading.adapter.SpringTransactionRunner;
import com.mia_princz.mse.execution.trading.application.AccountChanges;
import com.mia_princz.mse.execution.trading.application.AddInstrument;
import com.mia_princz.mse.execution.trading.application.BotCredentials;
import com.mia_princz.mse.execution.trading.application.BotRunResult;
import com.mia_princz.mse.execution.trading.application.CancelOrder;
import com.mia_princz.mse.execution.trading.application.CancelResult;
import com.mia_princz.mse.execution.trading.application.CloseSchedule;
import com.mia_princz.mse.execution.trading.application.CommandKey;
import com.mia_princz.mse.execution.trading.application.CommandWriter;
import com.mia_princz.mse.execution.trading.application.CommandWriter.WriterStoppedException;
import com.mia_princz.mse.execution.trading.application.InstrumentResult;
import com.mia_princz.mse.execution.trading.application.MarketClosing;
import com.mia_princz.mse.execution.trading.application.MarketControl;
import com.mia_princz.mse.execution.trading.application.MarketTransition;
import com.mia_princz.mse.execution.trading.application.PlaceOrder;
import com.mia_princz.mse.execution.trading.application.PlacementResult;
import com.mia_princz.mse.execution.trading.application.SimulationChanges;
import com.mia_princz.mse.execution.trading.application.SimulationStart;
import com.mia_princz.mse.execution.trading.application.StartBotRun;
import com.mia_princz.mse.execution.trading.application.StartSimulation;
import com.mia_princz.mse.execution.trading.application.TradingEngine;
import com.mia_princz.mse.execution.trading.application.TradingStore;
import com.mia_princz.mse.execution.trading.application.TransactionRunner;
import com.mia_princz.mse.execution.trading.application.WriterTimings;
import com.mia_princz.mse.execution.trading.domain.OrderType;
import com.mia_princz.mse.execution.trading.domain.Price;
import com.mia_princz.mse.execution.trading.domain.Quantity;
import com.mia_princz.mse.execution.trading.domain.RejectionCode;
import com.mia_princz.mse.execution.trading.domain.RestingOrders;
import com.mia_princz.mse.execution.trading.domain.SimulationName;
import com.mia_princz.mse.execution.trading.domain.Side;

/**
 * A market opened with a duration, against real PostgreSQL, with engines the test builds and a clock it moves: the
 * close the exchange makes at the close time, what is refused before it runs, and what a restart does on either side
 * of the time. Each test has a market of its own, in SETUP with two instruments, that the test opens; the one before
 * it is closed. A close here cancels two orders per transaction, so a handful of orders takes several. The schedule
 * looks at the clock every 20 ms, so a moved clock is seen at once.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "mse.test.database=scheduled-close")
@ExtendWith(OutputCaptureExtension.class)
class ScheduledCloseIT {

	private static final Instant NOW = Instant.parse("2031-03-04T05:06:07.123456Z");

	private static final Duration HOUR = Duration.ofHours(1);

	private static final long CASH = 10_000_000;

	private static final long SHARES = 1_000;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	DataSource dataSource;

	@Autowired
	PlatformTransactionManager transactionManager;

	private final MovableClock clock = new MovableClock(NOW);

	/** Whether the process the test runs owns the writer, as the schedule asks before it closes. */
	private final AtomicBoolean owns = new AtomicBoolean(true);

	/** How often the schedule asked the engine for a close. */
	private final AtomicInteger asked = new AtomicInteger();

	private TradingStore store;

	private CountingTransactions transactions;

	private CommandWriter writer;

	private ScheduledClose schedule;

	/** The engine the schedule asks; the newest one the test built. */
	private volatile TradingEngine current;

	/** The operator who opens the market, and so the one its scheduled close is recorded under. */
	private UUID opener;

	/** Another operator, who halts, resumes or closes. */
	private UUID operator;

	private UUID simulation;

	private UUID issuance;

	private UUID first;

	private UUID second;

	/** A process, or its database, that stopped: no transaction after it starts. Not a failure the engine knows. */
	private static final class ProcessStopped extends RuntimeException {

		private static final long serialVersionUID = 1L;

	}

	/**
	 * Real transactions, counted, with an action that can be run just before a chosen one: from inside a start, before
	 * its close's transaction, say. From {@link #stopAfter} on none starts.
	 */
	private final class CountingTransactions implements TransactionRunner {

		private final TransactionRunner real = new SpringTransactionRunner(transactionManager);

		private final AtomicInteger asked = new AtomicInteger();

		private volatile int beforeAt = -1;

		private volatile Runnable before;

		private volatile int stopFrom = Integer.MAX_VALUE;

		void before(int transactions, Runnable action) {
			before = action;
			beforeAt = asked.get() + transactions;
		}

		void stopAfter(int transactions) {
			stopFrom = asked.get() + transactions;
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
			return real.inTransaction(work);
		}

	}

	@BeforeEach
	void market() {
		store = new JdbcTradingStore(JdbcClient.create(dataSource), JsonMapper.builder().build());
		transactions = new CountingTransactions();
		opener = user("schedule.opener");
		operator = user("schedule.operator");
		newSimulation();
	}

	@AfterEach
	void stop() throws InterruptedException {
		if (schedule != null) {
			schedule.close();
		}
		if (writer != null) {
			writer.close(Duration.ofSeconds(5));
		}
	}

	/**
	 * Opened for an hour, with orders resting on both instruments and a halt by another operator. A microsecond before
	 * the hour is up nothing has happened and an order is still taken; at the hour the exchange closes the market by
	 * itself: every order cancelled in three transactions, at the close time on the service's clock, recorded as a
	 * scheduled close under the operator who opened it, with the key made from the simulation and the time.
	 */
	@Test
	void aMarketOpenedForAnHourClosesAtItsTimeWithItsOrdersResting(CapturedOutput output) throws Exception {
		UUID a = funded();
		UUID b = funded();
		TradingEngine engine = engine();
		engine.start();
		assertThat(schedule.closesAt()).as("nothing to close before it opens").isNull();
		MarketTransition opened = open(engine, HOUR);
		Instant closesAt = NOW.plus(HOUR);
		assertThat(opened).isEqualTo(new MarketTransition(MarketTransition.Status.APPLIED, "OPEN", 1, closesAt));
		assertThat(closesAt()).as("the service's clock, not the database's").isEqualTo(closesAt);
		assertThat(schedule.closesAt()).isEqualTo(closesAt);
		List<UUID> orders = new ArrayList<>(fiveResting(engine, a, b));
		control(engine, operator, MarketControl.Action.HALT);
		control(engine, operator, MarketControl.Action.RESUME);

		clock.set(closesAt.minusNanos(1_000));
		Thread.sleep(200);
		assertThat(state()).isEqualTo("OPEN");
		assertThat(asked.get()).isZero();
		orders.add(place(engine, b, second, Side.BUY, "40.00", 1).orderId());
		long before = sequence();

		clock.set(closesAt);
		await(() -> "CLOSED".equals(state()) && resting() == 0);
		long close = before + 1;
		assertThat(sequence()).as("the close and two more parts").isEqualTo(close + 2);
		assertThat(jdbc.queryForObject("SELECT closed_at FROM simulation WHERE id = ?", Timestamp.class, simulation)
			.toInstant()).isEqualTo(closesAt);
		UUID key = scheduledKey(simulation, closesAt);
		assertThat(audit(close)).as("under the opener, not the operator who halted last")
			.containsExactly("MARKET_CLOSE_SCHEDULED APPLIED " + opener + " " + key,
					"MARKET_CLOSE_SWEEP APPLIED " + opener, "MARKET_CLOSE_SWEEP APPLIED " + opener);
		assertThat(jdbc.queryForObject("""
				SELECT route || ' ' || response_status || ' ' || (response_body ->> 'canceledOrders') FROM command_result
				WHERE simulation_id = ? AND principal_id = ? AND idempotency_key = ?""", String.class, simulation, opener,
				key)).isEqualTo("MARKET_CLOSE_SCHEDULED 200 6");
		assertThat(states(orders)).as("every order cancelled by the close").containsOnly("CANCELED MARKET_CLOSED");
		assertThat(reserved()).isZero();
		assertThat(reconciliationProblems()).isEmpty();
		assertThat(appliedCloses()).isEqualTo(1);
		// Logged once the close has answered, after its last part and the read of its bots' token files.
		await(() -> output.getAll()
			.contains("The market closed at its close time, " + closesAt + ", at sequence " + close));
		assertThat(schedule.closesAt()).isNull();
		Thread.sleep(100);
		assertThat(asked.get()).as("asked once").isEqualTo(1);
		assertThat(place(engine, a, first, Side.BUY, "90.00", 1).rejection()).isEqualTo(RejectionCode.MARKET_CLOSED);
	}

	/**
	 * Asked twice at the close time, and an operator's close sent right after, all before the first has run: one close.
	 * The second ask finds the market closed and makes no command; the operator's close is refused as for a closed
	 * market, under its own key.
	 */
	@Test
	void aSecondAskAndAnOperatorsCloseRightAfterItMakeOneClose() throws Exception {
		UUID a = funded();
		UUID b = funded();
		TradingEngine engine = engine(CloseSchedule.NONE);
		engine.start();
		open(engine, HOUR);
		fiveResting(engine, a, b);
		Instant closesAt = NOW.plus(HOUR);
		clock.set(closesAt);
		long before = sequence();

		CompletableFuture<Optional<MarketClosing>> firstAsk = engine.closeOnSchedule(simulation, closesAt);
		CompletableFuture<Optional<MarketClosing>> secondAsk = engine.closeOnSchedule(simulation, closesAt);
		CommandKey manualKey = key(operator, "POST /api/v1/admin/market/close");
		CompletableFuture<MarketClosing> manual = engine.close(manualKey, closeBy(operator));

		long close = before + 1;
		assertThat(firstAsk.get(20, TimeUnit.SECONDS))
			.contains(new MarketClosing(MarketClosing.Status.CLOSED, "CLOSED", close, 5, 0));
		assertThat(secondAsk.get(20, TimeUnit.SECONDS)).isEmpty();
		assertThat(manual.get(20, TimeUnit.SECONDS))
			.isEqualTo(new MarketClosing(MarketClosing.Status.NOT_ALLOWED, "CLOSED", close + 3, 0, 0));
		assertThat(appliedCloses()).isEqualTo(1);
		assertThat(audit(close)).containsExactly("MARKET_CLOSE_SCHEDULED APPLIED " + opener + " "
				+ scheduledKey(simulation, closesAt), "MARKET_CLOSE_SWEEP APPLIED " + opener,
				"MARKET_CLOSE_SWEEP APPLIED " + opener, "MARKET_CLOSE NOT_ALLOWED " + operator + " "
						+ manualKey.idempotencyKey());
		assertThat(resting()).isZero();
		assertThat(reconciliationProblems()).isEmpty();
	}

	/**
	 * An operator's close that comes first stands: the ask at the close time, queued right behind it, makes no command,
	 * and the close is not recorded as scheduled. Nor does an ask after a restart.
	 */
	@Test
	void anOperatorsCloseThatComesFirstStandsAndTheScheduleMakesNone() throws Exception {
		UUID a = funded();
		UUID b = funded();
		TradingEngine engine = engine(CloseSchedule.NONE);
		engine.start();
		open(engine, HOUR);
		fiveResting(engine, a, b);
		Instant closesAt = NOW.plus(HOUR);
		clock.set(closesAt.plusSeconds(1));
		long before = sequence();

		CompletableFuture<MarketClosing> manual = engine.close(key(operator, "POST /api/v1/admin/market/close"),
				closeBy(operator));
		CompletableFuture<Optional<MarketClosing>> ask = engine.closeOnSchedule(simulation, closesAt);
		assertThat(manual.get(20, TimeUnit.SECONDS).status()).isEqualTo(MarketClosing.Status.CLOSED);
		assertThat(ask.get(20, TimeUnit.SECONDS)).isEmpty();
		assertThat(sequence()).as("the close and its two parts, nothing more").isEqualTo(before + 3);
		assertThat(audit(before + 1).getFirst()).startsWith("MARKET_CLOSE APPLIED " + operator);
		assertThat(appliedCloses()).isEqualTo(1);

		TradingEngine next = restarted(CloseSchedule.NONE);
		next.start();
		assertThat(next.closeOnSchedule(simulation, closesAt).get(5, TimeUnit.SECONDS)).isEmpty();
		assertThat(sequence()).isEqualTo(before + 3);
	}

	/**
	 * From the close time on, before the close has run: an order is rejected MARKET_CLOSED (stored, so its key repeats
	 * the answer), a bot run does not start, and a cancel is still taken. Halting and resuming are let through; an order
	 * in the halted market is MARKET_CLOSED as well. A microsecond before the time an order is still taken.
	 */
	@Test
	void fromTheCloseTimeOrdersAndRunStartsAreRefusedBeforeTheCloseHasRun() throws Exception {
		UUID a = funded();
		TradingEngine engine = engine(CloseSchedule.NONE);
		engine.start();
		open(engine, Duration.ofMinutes(30));
		Instant closesAt = NOW.plus(Duration.ofMinutes(30));
		UUID resting = place(engine, a, first, Side.BUY, "90.00", 1).orderId();
		clock.set(closesAt.minusNanos(1_000));
		assertThat(place(engine, a, first, Side.BUY, "91.00", 1).state()).isEqualTo(PlacementResult.OrderState.OPEN);

		clock.set(closesAt);
		CommandKey late = key(a, "POST /api/v1/orders");
		PlaceOrder order = order(a, first, Side.BUY, "92.00", 1);
		PlacementResult refused = engine.place(late, order).get(5, TimeUnit.SECONDS);
		assertThat(refused.state()).isEqualTo(PlacementResult.OrderState.REJECTED);
		assertThat(refused.rejection()).isEqualTo(RejectionCode.MARKET_CLOSED);
		assertThat(engine.place(late, order).get(5, TimeUnit.SECONDS)).isEqualTo(refused);
		assertThat(states(List.of(order.orderId()))).containsExactly("REJECTED MARKET_CLOSED");
		BotRunResult run = engine.startBotRun(key(operator, "POST /api/v1/admin/bot-runs"),
				new StartBotRun(UUID.randomUUID(), 0, 1, 0, 1, 100, 10, 100, null, List.of(), operator))
			.get(5, TimeUnit.SECONDS);
		assertThat(run.status()).isEqualTo(BotRunResult.Status.MARKET_CLOSED);
		assertThat(engine.cancel(key(a, "POST /api/v1/orders/{orderId}/cancel"), new CancelOrder(resting, a))
			.get(5, TimeUnit.SECONDS)
			.status()).isEqualTo(CancelResult.Status.CANCELED);

		assertThat(control(engine, operator, MarketControl.Action.HALT).status())
			.isEqualTo(MarketTransition.Status.APPLIED);
		assertThat(place(engine, a, first, Side.BUY, "93.00", 1).rejection()).isEqualTo(RejectionCode.MARKET_CLOSED);
		assertThat(control(engine, operator, MarketControl.Action.RESUME).status())
			.isEqualTo(MarketTransition.Status.APPLIED);
		assertThat(state()).as("not closed yet: only refused as if it were").isEqualTo("OPEN");
		assertThat(appliedCloses()).isZero();
	}

	/**
	 * The process stops with the market open and orders resting, and the next one starts after the close time. It
	 * closes the market while it starts, with the scheduled close and its key, before it takes a command: an order sent
	 * from inside the start, just before the close's transaction, is refused. A third start closes nothing more.
	 */
	@Test
	void aRestartAcrossTheCloseTimeClosesWhileItStartsAndNotTwice(CapturedOutput output) throws Exception {
		UUID a = funded();
		UUID b = funded();
		TradingEngine engine = engine(CloseSchedule.NONE);
		engine.start();
		open(engine, HOUR);
		Instant closesAt = NOW.plus(HOUR);
		fiveResting(engine, a, b);
		long before = sequence();
		clock.set(closesAt.plus(Duration.ofMinutes(10)));

		TradingEngine next = restarted();
		List<Throwable> duringStart = new CopyOnWriteArrayList<>();
		// The start's own transaction, the read of who opened the market, then the close's.
		transactions.before(2, () -> {
			try {
				next.place(key(a, "POST /api/v1/orders"), order(a, first, Side.BUY, "90.00", 1));
			}
			catch (RuntimeException ex) {
				duringStart.add(ex);
			}
		});
		next.start();
		assertThat(duringStart).singleElement()
			.isInstanceOfSatisfying(WriterStoppedException.class,
					(refused) -> assertThat(refused.getMessage()).isEqualTo("Trading has not started"));
		assertThat(state()).isEqualTo("CLOSED");
		long close = before + 1;
		assertThat(sequence()).isEqualTo(close + 2);
		assertThat(resting()).isZero();
		assertThat(reconciliationProblems()).isEmpty();
		assertThat(audit(close).getFirst())
			.isEqualTo("MARKET_CLOSE_SCHEDULED APPLIED " + opener + " " + scheduledKey(simulation, closesAt));
		assertThat(jdbc.queryForObject("SELECT closed_at FROM simulation WHERE id = ?", Timestamp.class, simulation)
			.toInstant()).as("when it was closed, at the start").isEqualTo(closesAt.plus(Duration.ofMinutes(10)));
		assertThat(output.getAll()).contains("Closed the market at startup: its close time, " + closesAt);
		assertThat(schedule.closesAt()).isNull();
		assertThat(asked.get()).as("the start closed it, not the schedule").isZero();

		TradingEngine third = restarted();
		third.start();
		assertThat(sequence()).isEqualTo(close + 2);
		assertThat(appliedCloses()).isEqualTo(1);
		assertThat(third.closeOnSchedule(simulation, closesAt).get(5, TimeUnit.SECONDS)).isEmpty();
		assertThat(schedule.closesAt()).isNull();
	}

	/** A start before the close time arms it: the market trades on, and closes at the time without another start. */
	@Test
	void aRestartBeforeTheCloseTimeArmsIt() throws Exception {
		UUID a = funded();
		TradingEngine engine = engine(CloseSchedule.NONE);
		engine.start();
		open(engine, HOUR);
		Instant closesAt = NOW.plus(HOUR);
		place(engine, a, first, Side.BUY, "90.00", 1);
		clock.set(NOW.plus(Duration.ofMinutes(30)));

		TradingEngine next = restarted();
		next.start();
		assertThat(state()).isEqualTo("OPEN");
		assertThat(schedule.closesAt()).isEqualTo(closesAt);
		assertThat(place(next, a, first, Side.BUY, "91.00", 1).state()).isEqualTo(PlacementResult.OrderState.OPEN);
		long before = sequence();

		clock.set(closesAt);
		await(() -> "CLOSED".equals(state()) && resting() == 0);
		assertThat(audit(before + 1).getFirst()).startsWith("MARKET_CLOSE_SCHEDULED APPLIED " + opener);
	}

	/**
	 * A market closed by an operator before its time, and a new simulation opened with a duration of its own: the
	 * schedule holds the new one's time and not the old one's. At the old time nothing happens, and asking for the old
	 * close is refused as stale; at the new time the new market closes, under its own opener.
	 */
	@Test
	void aNewSimulationsCloseTimeIsArmedAndTheClosedOnesIsNot() throws Exception {
		TradingEngine engine = engine();
		engine.start();
		open(engine, HOUR);
		UUID old = simulation;
		Instant oldCloseAt = NOW.plus(HOUR);
		clock.advance(Duration.ofMinutes(5));
		engine.close(key(operator, "POST /api/v1/admin/market/close"), closeBy(operator)).get(20, TimeUnit.SECONDS);
		await(() -> schedule.closesAt() == null);

		UUID newer = user("schedule.newer");
		SimulationStart started = engine
			.startSimulation(key(operator, "POST /api/v1/admin/simulations"), new StartSimulation(UUID.randomUUID(),
					new SimulationName("Timed " + UUID.randomUUID().toString().substring(0, 8)), operator))
			.get(5, TimeUnit.SECONDS);
		simulation = started.simulationId();
		assertThat(schedule.closesAt()).isNull();
		assertThat(engine.addInstrument(key(operator, "POST /api/v1/admin/instruments"),
				new AddInstrument(UUID.randomUUID(), "NEWR", null, Price.parse("10.00"), operator))
			.get(5, TimeUnit.SECONDS)
			.status()).isEqualTo(InstrumentResult.Status.CREATED);
		MarketTransition opened = engine
			.control(key(newer, "POST /api/v1/admin/market/open"),
					new MarketControl(MarketControl.Action.OPEN, newer, Duration.ofHours(2)))
			.get(5, TimeUnit.SECONDS);
		Instant newCloseAt = NOW.plus(Duration.ofMinutes(5)).plus(Duration.ofHours(2));
		assertThat(opened.closesAt()).isEqualTo(newCloseAt);
		assertThat(schedule.closesAt()).isEqualTo(newCloseAt);

		clock.set(oldCloseAt.plusSeconds(60));
		Thread.sleep(200);
		assertThat(state()).isEqualTo("OPEN");
		assertThat(engine.closeOnSchedule(old, oldCloseAt).get(5, TimeUnit.SECONDS)).as("not the current one's").isEmpty();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE simulation_id = ? AND action LIKE 'MARKET_CLOSE%'",
				Long.class, old)).as("the old market's one close, by its operator").isEqualTo(1);
		long newSequence = sequence();

		clock.set(newCloseAt);
		await(() -> "CLOSED".equals(state()));
		assertThat(audit(newSequence + 1)).containsExactly("MARKET_CLOSE_SCHEDULED APPLIED " + newer + " "
				+ scheduledKey(simulation, newCloseAt));
	}

	/**
	 * A scheduled close that stops between two parts halts the writer as an operator's close does, and the schedule
	 * does not ask again: nothing would answer but the halt. The next start finishes the sweep under the operator who
	 * opened the market, before it takes a command, and closes nothing twice.
	 */
	@Test
	void aScheduledCloseThatCannotFinishHaltsAndTheNextStartFinishesIt(CapturedOutput output) throws Exception {
		UUID a = funded();
		UUID b = funded();
		TradingEngine engine = engine();
		engine.start();
		open(engine, HOUR);
		Instant closesAt = NOW.plus(HOUR);
		fiveResting(engine, a, b);
		long close = sequence() + 1;
		// The read of who opened the market, then the close itself; no part of the sweep after it.
		transactions.stopAfter(2);

		clock.set(closesAt);
		await(() -> output.getAll().contains("The market's close at its close time, " + closesAt
				+ ", halted the writer; the next start closes the market"));
		assertThat(state()).isEqualTo("CLOSED");
		assertThat(sequence()).isEqualTo(close);
		assertThat(resting()).as("two cancelled by the close itself").isEqualTo(3);
		assertThat(reconciliationProblems()).as("between two parts").isEmpty();
		assertThat(writer.isAvailable()).as("no writer goes on with a market half closed").isFalse();
		assertThat(audit(close)).containsExactly("MARKET_CLOSE_SCHEDULED APPLIED " + opener + " "
				+ scheduledKey(simulation, closesAt));
		assertThat(schedule.closesAt()).isNull();
		Thread.sleep(200);
		assertThat(asked.get()).as("not asked again").isEqualTo(1);
		assertThat(output.getAll()).doesNotContain("failed; asking again");

		TradingEngine next = restarted();
		next.start();
		assertThat(sequence()).as("two more parts").isEqualTo(close + 2);
		assertThat(resting()).isZero();
		assertThat(reserved()).isZero();
		assertThat(reconciliationProblems()).isEmpty();
		assertThat(audit(close)).as("the parts a start cancels are the opener's, as the close is")
			.containsExactly("MARKET_CLOSE_SCHEDULED APPLIED " + opener + " " + scheduledKey(simulation, closesAt),
					"MARKET_CLOSE_SWEEP APPLIED " + opener, "MARKET_CLOSE_SWEEP APPLIED " + opener);
		assertThat(appliedCloses()).isEqualTo(1);
		assertThat(schedule.closesAt()).isNull();
		assertThat(next.closeOnSchedule(simulation, closesAt).get(5, TimeUnit.SECONDS)).isEmpty();
		assertThat(sequence()).isEqualTo(close + 2);
		assertThat(asked.get()).isEqualTo(1);
	}

	/**
	 * Only the close time the engine holds, of the simulation it holds, is closed: an ask for a second either side of
	 * it, or for another simulation, makes no command, though the time has come. A changed close time is to be refused
	 * this way when an ask for the old one comes late.
	 */
	@Test
	void anAskForAnotherCloseTimeOrSimulationMakesNoCommand() throws Exception {
		TradingEngine engine = engine(CloseSchedule.NONE);
		engine.start();
		open(engine, HOUR);
		Instant closesAt = NOW.plus(HOUR);
		clock.set(closesAt.plusSeconds(5));
		long before = sequence();

		assertThat(engine.closeOnSchedule(simulation, closesAt.minusSeconds(1)).get(5, TimeUnit.SECONDS)).isEmpty();
		assertThat(engine.closeOnSchedule(simulation, closesAt.plusSeconds(1)).get(5, TimeUnit.SECONDS)).isEmpty();
		assertThat(engine.closeOnSchedule(UUID.randomUUID(), closesAt).get(5, TimeUnit.SECONDS)).isEmpty();
		assertThat(sequence()).as("no sequence taken").isEqualTo(before);
		assertThat(state()).isEqualTo("OPEN");
		assertThat(appliedCloses()).isZero();

		assertThat(engine.closeOnSchedule(simulation, closesAt).get(20, TimeUnit.SECONDS)).isPresent();
		assertThat(audit(before + 1)).containsExactly("MARKET_CLOSE_SCHEDULED APPLIED " + opener + " "
				+ scheduledKey(simulation, closesAt));
	}

	/**
	 * A clock finer than the database's: the close time is kept to the microsecond, in the opening's answer, in the row
	 * and in memory alike, so the process after a restart makes its close under the key the one before would have.
	 */
	@Test
	void theCloseTimeIsKeptToTheMicrosecondSoARestartMakesTheSameKey() throws Exception {
		clock.set(Instant.parse("2031-03-04T05:06:07.123456789Z"));
		TradingEngine engine = engine(CloseSchedule.NONE);
		engine.start();
		Instant closesAt = Instant.parse("2031-03-04T06:06:07.123456Z");
		assertThat(open(engine, HOUR).closesAt()).isEqualTo(closesAt);
		assertThat(closesAt()).isEqualTo(closesAt);
		assertThat(jdbc.queryForObject("""
				SELECT response_body ->> 'closesAt' FROM command_result
				WHERE simulation_id = ? AND route = 'POST /api/v1/admin/market/open'""", String.class, simulation))
			.as("the stored answer").isEqualTo(closesAt.toString());
		long before = sequence();

		clock.set(closesAt.plusSeconds(1));
		TradingEngine next = restarted();
		next.start();
		assertThat(state()).isEqualTo("CLOSED");
		assertThat(audit(before + 1)).containsExactly("MARKET_CLOSE_SCHEDULED APPLIED " + opener + " "
				+ scheduledKey(simulation, closesAt));
	}

	/**
	 * A process that does not own the writer never asks for the close, though its time has come; orders are refused all
	 * the same, since the time is the market's. Once it owns the writer, the close is made.
	 */
	@Test
	void aProcessThatDoesNotOwnTheWriterDoesNotClose() throws Exception {
		UUID a = funded();
		owns.set(false);
		TradingEngine engine = engine();
		engine.start();
		open(engine, HOUR);
		place(engine, a, first, Side.BUY, "90.00", 1);
		clock.set(NOW.plus(HOUR).plusSeconds(1));
		Thread.sleep(300);
		assertThat(asked.get()).isZero();
		assertThat(state()).isEqualTo("OPEN");
		assertThat(place(engine, a, first, Side.BUY, "91.00", 1).rejection()).isEqualTo(RejectionCode.MARKET_CLOSED);

		owns.set(true);
		await(() -> "CLOSED".equals(state()) && resting() == 0);
		assertThat(asked.get()).isEqualTo(1);
	}

	/** The key of the close made at a simulation's close time, worked out here as the contract describes it. */
	private static UUID scheduledKey(UUID simulation, Instant closesAt) {
		return UUID.nameUUIDFromBytes(("close:" + simulation + ":" + closesAt).getBytes(StandardCharsets.UTF_8));
	}

	/** A new market beside the closed ones: in SETUP, with two instruments. Whatever was not closed before it is, first. */
	private void newSimulation() {
		jdbc.update("""
				UPDATE simulation SET state = 'CLOSED', opened_at = coalesce(opened_at, now()), closed_at = now()
				WHERE state <> 'CLOSED'""");
		simulation = UUID.randomUUID();
		issuance = UUID.randomUUID();
		first = UUID.randomUUID();
		second = UUID.randomUUID();
		jdbc.update("INSERT INTO simulation (id, state) VALUES (?, 'SETUP')", simulation);
		jdbc.update("INSERT INTO account (id, simulation_id, kind, display_name) VALUES (?, ?, 'ISSUANCE', 'Issuance')",
				issuance, simulation);
		jdbc.update("INSERT INTO instrument (id, simulation_id, symbol, reference_price_cents) VALUES (?, ?, 'AAA', 10000)",
				first, simulation);
		jdbc.update("INSERT INTO instrument (id, simulation_id, symbol, reference_price_cents) VALUES (?, ?, 'BBB', 5000)",
				second, simulation);
	}

	private TradingEngine engine() {
		schedule = new ScheduledClose(clock, owns::get, Duration.ofMillis(20), (simulation, closesAt) -> {
			asked.incrementAndGet();
			return current.closeOnSchedule(simulation, closesAt);
		});
		return engine(schedule);
	}

	/** An engine as a process starting on this database has it: nothing in memory yet. */
	private TradingEngine engine(CloseSchedule closeSchedule) {
		writer = new CommandWriter("schedule-writer", 1_000);
		BotCredentials credentials = new BotCredentials() {

			@Override
			public byte[] issue(UUID accountId) {
				throw new UnsupportedOperationException("no bots are made in these tests");
			}

			@Override
			public void discard(Collection<UUID> accountIds) {
			}

		};
		current = new TradingEngine(store, transactions, writer, clock, credentials, AccountChanges.NONE,
				WriterTimings.NONE, System::nanoTime, new RestingOrders(), 2, (millis) -> {
				}, SimulationChanges.NONE, closeSchedule);
		return current;
	}

	/** The process after a stop, with a schedule: the stopped one's writer and schedule are gone. */
	private TradingEngine restarted() throws InterruptedException {
		stop();
		transactions = new CountingTransactions();
		return engine();
	}

	private TradingEngine restarted(CloseSchedule closeSchedule) throws InterruptedException {
		stop();
		transactions = new CountingTransactions();
		return engine(closeSchedule);
	}

	private MarketTransition open(TradingEngine engine, Duration duration) throws Exception {
		return engine.control(key(opener, "POST /api/v1/admin/market/open"),
				new MarketControl(MarketControl.Action.OPEN, opener, duration))
			.get(5, TimeUnit.SECONDS);
	}

	private MarketTransition control(TradingEngine engine, UUID actor, MarketControl.Action action) throws Exception {
		return engine.control(key(actor, "POST /api/v1/admin/market/" + action.name().toLowerCase()),
				new MarketControl(action, actor))
			.get(5, TimeUnit.SECONDS);
	}

	private static MarketControl closeBy(UUID actor) {
		return new MarketControl(MarketControl.Action.CLOSE, actor);
	}

	/** Five resting orders of two accounts on both instruments: three transactions of a close. */
	private List<UUID> fiveResting(TradingEngine engine, UUID a, UUID b) throws Exception {
		List<UUID> orders = new ArrayList<>();
		for (String price : new String[] { "90.00", "91.00", "92.00" }) {
			orders.add(place(engine, a, first, Side.BUY, price, 1).orderId());
		}
		orders.add(place(engine, b, first, Side.SELL, "120.00", 7).orderId());
		orders.add(place(engine, b, second, Side.SELL, "60.00", 3).orderId());
		return orders;
	}

	/** A user who may operate the market, made the first time a test asks for it. */
	private UUID user(String username) {
		return jdbc.queryForList("SELECT id FROM app_user WHERE username = ?", UUID.class, username)
			.stream()
			.findFirst()
			.orElseGet(() -> {
				UUID id = UUID.randomUUID();
				TestUsers.insert(jdbc, id, username, TestBrowser.passwordHash("test-schedule-password"), "OPERATOR");
				return id;
			});
	}

	/** An account funded with cash and both instruments by a balanced genesis batch. */
	private UUID funded() {
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
			jdbc.update("INSERT INTO cash_balance (account_id, account_kind, total_cents, reserved_cents) VALUES (?, 'BOT', ?, 0)",
					account, CASH);
			for (UUID instrument : List.of(first, second)) {
				jdbc.update(post, batch, issuance, instrument, -SHARES);
				jdbc.update(post, batch, account, instrument, SHARES);
				jdbc.update("""
						INSERT INTO position (account_id, account_kind, instrument_id, total_quantity, reserved_quantity)
						VALUES (?, 'BOT', ?, ?, 0)""", account, instrument, SHARES);
			}
			return account;
		});
	}

	private static PlaceOrder order(UUID account, UUID instrument, Side side, String price, long quantity) {
		return new PlaceOrder(UUID.randomUUID(), account, instrument, side, OrderType.LIMIT, Price.parse(price),
				new Quantity(quantity));
	}

	private PlacementResult place(TradingEngine engine, UUID account, UUID instrument, Side side, String price,
			long quantity) throws Exception {
		return engine.place(key(account, "POST /api/v1/orders"), order(account, instrument, side, price, quantity))
			.get(5, TimeUnit.SECONDS);
	}

	private static CommandKey key(UUID principal, String route) {
		return new CommandKey(principal, UUID.randomUUID(), route, new byte[32]);
	}

	private String state() {
		return jdbc.queryForObject("SELECT state FROM simulation WHERE id = ?", String.class, simulation);
	}

	private Instant closesAt() {
		return jdbc.queryForObject("SELECT closes_at FROM simulation WHERE id = ?", Timestamp.class, simulation)
			.toInstant();
	}

	private long sequence() {
		return jdbc.queryForObject("SELECT last_command_sequence FROM simulation WHERE id = ?", Long.class, simulation);
	}

	/** This market's audit rows from this sequence on: action, outcome, actor, and for a close its correlation ID. */
	private List<String> audit(long fromSequence) {
		return jdbc.queryForList("""
				SELECT action || ' ' || outcome || ' ' || actor_user_id
				    || CASE WHEN action = 'MARKET_CLOSE_SWEEP' THEN '' ELSE ' ' || correlation_id END
				FROM audit_log WHERE simulation_id = ? AND command_sequence >= ? ORDER BY command_sequence""",
				String.class, simulation, fromSequence);
	}

	/** How many closes of this market applied, sent or scheduled. */
	private long appliedCloses() {
		return jdbc.queryForObject("""
				SELECT count(*) FROM audit_log WHERE simulation_id = ? AND outcome = 'APPLIED'
				    AND action IN ('MARKET_CLOSE', 'MARKET_CLOSE_SCHEDULED')""", Long.class, simulation);
	}

	private long resting() {
		return jdbc.queryForObject("""
				SELECT count(*) FROM orders WHERE simulation_id = ? AND state IN ('OPEN', 'PARTIALLY_FILLED')""",
				Long.class, simulation);
	}

	private List<String> states(List<UUID> orders) {
		Map<UUID, String> states = new HashMap<>();
		jdbc.query("SELECT id, state || ' ' || coalesce(reason, 'null') FROM orders WHERE simulation_id = ?", (row) -> {
			states.put(row.getObject(1, UUID.class), row.getString(2));
		}, simulation);
		return orders.stream().map(states::get).toList();
	}

	private long reserved() {
		return jdbc.queryForObject("""
				SELECT (SELECT coalesce(sum(b.reserved_cents), 0) FROM cash_balance b JOIN account a ON a.id = b.account_id
				        WHERE a.simulation_id = ?)
				    + (SELECT coalesce(sum(p.reserved_quantity), 0) FROM position p JOIN account a ON a.id = p.account_id
				        WHERE a.simulation_id = ?)""", Long.class, simulation, simulation);
	}

	private List<String> reconciliationProblems() {
		return new TransactionTemplate(transactionManager).execute((status) -> store.reconciliationProblems(simulation));
	}

	private static void await(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
		while (!condition.getAsBoolean()) {
			assertThat(System.nanoTime()).as("waited twenty seconds").isLessThan(deadline);
			Thread.sleep(10);
		}
	}

}
