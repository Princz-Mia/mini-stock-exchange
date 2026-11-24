package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

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

import tools.jackson.databind.json.JsonMapper;

import com.mia_princz.mse.execution.identity.ApiTokens;
import com.mia_princz.mse.execution.trading.adapter.JdbcTradingStore;
import com.mia_princz.mse.execution.trading.adapter.SpringTransactionRunner;
import com.mia_princz.mse.execution.trading.application.AccountChanges;
import com.mia_princz.mse.execution.trading.application.AddInstrument;
import com.mia_princz.mse.execution.trading.application.BotCredentials;
import com.mia_princz.mse.execution.trading.application.BotJob;
import com.mia_princz.mse.execution.trading.application.BotJobResult;
import com.mia_princz.mse.execution.trading.application.BotJobRunner;
import com.mia_princz.mse.execution.trading.application.BotJobWorker;
import com.mia_princz.mse.execution.trading.application.CloseSchedule;
import com.mia_princz.mse.execution.trading.application.CommandKey;
import com.mia_princz.mse.execution.trading.application.CommandWriter;
import com.mia_princz.mse.execution.trading.application.InstrumentResult;
import com.mia_princz.mse.execution.trading.application.ProvisionAccount;
import com.mia_princz.mse.execution.trading.application.SimulationChanges;
import com.mia_princz.mse.execution.trading.application.StartBotJob;
import com.mia_princz.mse.execution.trading.application.StopBotJob;
import com.mia_princz.mse.execution.trading.application.TradingEngine;
import com.mia_princz.mse.execution.trading.application.TradingStore;
import com.mia_princz.mse.execution.trading.application.WriterTimings;
import com.mia_princz.mse.execution.trading.domain.Price;
import com.mia_princz.mse.execution.trading.domain.RestingOrders;

/**
 * Bot jobs where they do not go through in one piece, against real PostgreSQL, with engines and runners the test
 * builds so that it can stop one where a process would stop: a restart in the middle of a job, a part sent twice, a
 * part that keeps failing, other commands between parts, and a job of a simulation before the current one. The story
 * over HTTP is in {@link BotJobIT}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "mse.test.database=bot-job-engine")
class BotJobEngineIT {

	private static final String JOB_ROUTE = "POST /api/v1/admin/bot-jobs";

	/** Parts of four, so that a job of a couple of dozen bots takes several. */
	private static final int PART = 4;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	DataSource dataSource;

	@Autowired
	PlatformTransactionManager transactionManager;

	private TradingStore store;

	private CommandWriter writer;

	private BotJobWorker worker;

	private UUID operator;

	private UUID simulation;

	private UUID nova;

	/** Every token file the credentials were asked for and then told to remove, by account. */
	private final List<UUID> issued = new CopyOnWriteArrayList<>();

	private final List<UUID> discarded = new CopyOnWriteArrayList<>();

	/** While set, handing tokens over fails, as a full disk would. */
	private final AtomicBoolean handoverFails = new AtomicBoolean();

	/** How many more hand-overs fail, as a disk that is full for a moment would; then they go through again. */
	private final AtomicInteger handoversToFail = new AtomicInteger();

	/** Hand-overs that failed, each one a part's attempt. */
	private final AtomicInteger failedHandovers = new AtomicInteger();

	private final BotCredentials credentials = new BotCredentials() {

		@Override
		public byte[] issue(UUID accountId) {
			if (handoverFails.get() || handoversToFail.getAndUpdate((left) -> Math.max(0, left - 1)) > 0) {
				failedHandovers.incrementAndGet();
				throw new IllegalStateException("No space left for the token of bot account " + accountId);
			}
			issued.add(accountId);
			return ApiTokens.issue().hash();
		}

		@Override
		public void discard(Collection<UUID> accountIds) {
			discarded.addAll(accountIds);
		}

	};

	@BeforeEach
	void anEmptyMarketInSetup() throws Exception {
		store = new JdbcTradingStore(JdbcClient.create(dataSource), JsonMapper.builder().build());
		jdbc.update("""
				UPDATE simulation SET state = 'CLOSED', opened_at = coalesce(opened_at, now()), closed_at = now(),
				    writer_epoch = 0 WHERE state <> 'CLOSED' OR writer_epoch <> 0""");
		operator = UUID.randomUUID();
		TestUsers.insert(jdbc, operator, "jobs-" + operator.toString().substring(0, 8), "unused", "OPERATOR");
		simulation = UUID.randomUUID();
		jdbc.update("INSERT INTO simulation (id, state, writer_epoch, name) VALUES (?, 'SETUP', 0, ?)", simulation,
				"Jobs " + simulation);
		jdbc.update("INSERT INTO account (id, simulation_id, kind, display_name) VALUES (?, ?, 'ISSUANCE', 'Issuance')",
				UUID.randomUUID(), simulation);
	}

	@AfterEach
	void stop() throws InterruptedException {
		if (worker != null) {
			worker.close();
		}
		if (writer != null) {
			writer.close(Duration.ofSeconds(5));
		}
	}

	/**
	 * The process stops after a job's second part: its runner is closed through its listener, then its writer. The
	 * next process's engine takes the job up from its third part, under the same keys, and every bot is made once:
	 * exactly the counts asked for, named without a gap or a repeat, each funded once.
	 */
	@Test
	void aRestartInTheMiddleOfAJobGoesOnFromItsNextPartAndMakesNoBotTwice() throws Exception {
		AtomicReference<BotJobWorker> first = new AtomicReference<>();
		CountDownLatch stopped = new CountDownLatch(1);
		TradingEngine engine = engine((job) -> {
			if (job.partsDone() == 2) {
				first.get().close();
				stopped.countDown();
			}
		});
		first.set(worker);
		engine.start();
		instrument(engine);
		UUID jobKey = UUID.randomUUID();
		BotJobResult started = start(engine, jobKey, new BotJob.Counts(5, 15, 5));
		assertThat(started.status()).isEqualTo(BotJobResult.Status.STARTED);
		assertThat(stopped.await(30, TimeUnit.SECONDS)).isTrue();
		awaitTrue(() -> job(started.job().id()).partsDone() == 2);
		BotJob left = job(started.job().id());
		assertThat(left.state()).isEqualTo(BotJob.State.RUNNING);
		assertThat(left.createdTotal()).isEqualTo(8);
		assertThat(bots()).hasSize(8);
		writer.close(Duration.ofSeconds(5));

		List<BotJob> made = new CopyOnWriteArrayList<>();
		TradingEngine next = engine(made::add);
		next.start();
		BotJob done = awaitState(started.job().id(), BotJob.State.DONE);

		assertThat(done.created()).isEqualTo(new BotJob.Counts(5, 15, 5));
		assertThat(done.resumed()).isEqualTo(1);
		assertThat(done.createdAtRunStart()).as("taken up with the 8 made before the stop").isEqualTo(8);
		assertThat(done.partsDone()).isEqualTo(8);
		assertThat(made).extracting(BotJob::partsDone).as("only the parts after the restart")
			.containsExactly(3, 4, 5, 6, 7, 8);
		List<String> names = bots();
		assertThat(names).hasSize(25).doesNotHaveDuplicates();
		for (int i = 0; i < names.size(); i++) {
			assertThat(names.get(i)).isEqualTo(String.format("bot-%04d", i + 1));
		}
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM account a WHERE a.simulation_id = ? AND a.kind = 'BOT'
				    AND (SELECT count(DISTINCT journal_batch_id) FROM initial_allocation WHERE account_id = a.id) = 1""",
				Long.class, simulation)).as("each bot funded once").isEqualTo(25);
		assertThat(jdbc.queryForList("""
				SELECT bot_strategy || ' ' || count(*) FROM account WHERE simulation_id = ? AND kind = 'BOT'
				GROUP BY bot_strategy ORDER BY 1""", String.class, simulation))
			.containsExactly("MARKET_MAKER 5", "MOMENTUM 5", "NOISE 15");
		for (int number = 0; number < 8; number++) {
			assertThat(jdbc.queryForObject("SELECT count(*) FROM command_result WHERE simulation_id = ? AND idempotency_key = ?",
					Long.class, simulation, BotJob.partKey(jobKey, number))).as("part %d", number).isEqualTo(1);
		}
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM audit_log WHERE simulation_id = ? AND action = 'BOT_PROVISION' AND outcome = 'APPLIED'""",
				Long.class, simulation)).isEqualTo(8);
		assertThat(store.reconciliationProblems(simulation)).isEmpty();
	}

	/**
	 * A part asked for again, by a runner that did not hear its answer, is answered from its stored answer: no bot is
	 * made twice, and the second set of tokens is removed before the answer. A part that is not the next one is a
	 * fault, rolls back, and keeps no token.
	 */
	@Test
	void aPartSentAgainIsAnsweredFromItsStoredAnswer() throws Exception {
		TradingEngine engine = engine(BotJobRunner.NONE, BotJobWorker.Listener.NONE);
		engine.start();
		instrument(engine);
		BotJob job = start(engine, UUID.randomUUID(), new BotJob.Counts(0, 10, 0)).job();

		BotJobResult part = engine.botJobPart(job).get(10, TimeUnit.SECONDS);
		assertThat(part.status()).isEqualTo(BotJobResult.Status.PART_CREATED);
		assertThat(part.job().createdTotal()).isEqualTo(PART);
		long sequence = sequence();

		BotJobResult again = engine.botJobPart(job).get(10, TimeUnit.SECONDS);
		assertThat(again).as("the stored answer").isEqualTo(part);
		assertThat(bots()).hasSize(PART);
		assertThat(sequence()).as("a stored answer takes no sequence").isEqualTo(sequence);
		assertThat(discarded).as("the second part's tokens").hasSize(PART).doesNotContainAnyElementsOf(issued.subList(0,
				PART));

		BotJob ahead = new BotJob(job.id(), job.simulationId(), job.startedBy(), job.idempotencyKey(), job.state(),
				job.partSize(), job.requested(), new BotJob.Counts(0, 8, 0), 2, job.cashCents(), job.holdings(),
				job.startedAt(), job.runStartedAt(), 0, null, null, null, 0);
		Throwable outOfStep = catchThrowable(() -> engine.botJobPart(ahead).get(10, TimeUnit.SECONDS));
		assertThat(outOfStep).isInstanceOf(ExecutionException.class);
		assertThat(outOfStep.getCause()).hasMessageContaining("is not the next one");
		assertThat(bots()).hasSize(PART);
		assertThat(discarded).as("and the two of the part out of step").hasSize(PART + 2);
		assertThat(job(job.id()).partsDone()).isEqualTo(1);
	}

	/**
	 * Parts are commands of their own: a command sent while a job runs is taken between two of its parts, and its
	 * sequence lies between theirs.
	 */
	@Test
	void otherCommandsAreTakenBetweenParts() throws Exception {
		CountDownLatch held = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		TradingEngine engine = engine((job) -> {
			if (job.partsDone() == 1) {
				held.countDown();
				try {
					release.await(30, TimeUnit.SECONDS);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}
		});
		engine.start();
		instrument(engine);
		UUID jobKey = UUID.randomUUID();
		BotJob job = start(engine, jobKey, new BotJob.Counts(4, 4, 4)).job();
		assertThat(held.await(30, TimeUnit.SECONDS)).isTrue();

		InstrumentResult between = engine
			.addInstrument(key("POST /api/v1/admin/instruments"),
					new AddInstrument(UUID.randomUUID(), "ORBT", null, Price.parse("50.00"), operator))
			.get(10, TimeUnit.SECONDS);
		assertThat(between.status()).isEqualTo(InstrumentResult.Status.CREATED);
		release.countDown();
		awaitState(job.id(), BotJob.State.DONE);

		long firstPart = partSequence(jobKey, 0);
		long secondPart = partSequence(jobKey, 1);
		assertThat(between.commandSequence()).isGreaterThan(firstPart).isLessThan(secondPart);
	}

	/**
	 * A part that fails every time it is tried fails the job, with the reason, after its attempts; the bots made
	 * before stay, and the failure is recorded once, under its own key, and audited.
	 */
	@Test
	void aPartThatKeepsFailingFailsTheJobAndKeepsWhatWasMade() throws Exception {
		TradingEngine engine = engine((job) -> {
			if (job.partsDone() == 2) {
				handoverFails.set(true);
			}
		});
		engine.start();
		instrument(engine);
		UUID jobKey = UUID.randomUUID();
		BotJob job = start(engine, jobKey, new BotJob.Counts(0, 20, 0)).job();

		BotJob failed = awaitState(job.id(), BotJob.State.FAILED);

		assertThat(failedHandovers).as("the third part, tried three times in all").hasValue(3);
		assertThat(failed.createdTotal()).isEqualTo(8);
		assertThat(failed.failure()).startsWith("Bots could not be made: No space left for the token of bot account ");
		assertThat(failed.finishedAt()).isNotNull();
		assertThat(bots()).hasSize(8);
		assertThat(jdbc.queryForList("""
				SELECT action || ' ' || outcome FROM audit_log WHERE simulation_id = ? AND action LIKE 'BOT_JOB_%'
				ORDER BY command_sequence""", String.class, simulation))
			.containsExactly("BOT_JOB_START APPLIED", "BOT_JOB_FAIL APPLIED");
	}

	/**
	 * A part that fails once, as a disk full for a moment would make it, is tried again under the same key and goes
	 * through: the job ends DONE with exactly what was asked, and no failure is recorded. One bad moment never fails a
	 * job of thousands of bots.
	 */
	@Test
	void aPartThatFailsOnceIsTriedAgainAndTheJobGoesOn() throws Exception {
		TradingEngine engine = engine((job) -> {
			if (job.partsDone() == 2) {
				handoversToFail.set(1);
			}
		});
		engine.start();
		instrument(engine);
		BotJob job = start(engine, UUID.randomUUID(), new BotJob.Counts(4, 12, 4)).job();

		BotJob done = awaitState(job.id(), BotJob.State.DONE);

		assertThat(failedHandovers).as("the third part failed once").hasValue(1);
		assertThat(done.created()).isEqualTo(new BotJob.Counts(4, 12, 4));
		assertThat(done.failure()).isNull();
		assertThat(bots()).hasSize(20).doesNotHaveDuplicates();
		assertThat(jdbc.queryForList("""
				SELECT action || ' ' || outcome FROM audit_log WHERE simulation_id = ? AND action LIKE 'BOT_JOB_%'
				ORDER BY command_sequence""", String.class, simulation))
			.containsExactly("BOT_JOB_START APPLIED");
		assertThat(store.reconciliationProblems(simulation)).isEmpty();
	}

	/**
	 * Once a part is answered that the job was stopped, its runner lets the job go: it hands over no more tokens for
	 * it, and a job started after it is made. A runner that kept asking would hold its one thread for ever, writing
	 * and deleting a part's tokens on each pass, and no later job would ever be made.
	 */
	@Test
	void aRunnerLetsAStoppedJobGoAndMakesTheNextOne() throws Exception {
		AtomicReference<TradingEngine> built = new AtomicReference<>();
		UUID firstKey = UUID.randomUUID();
		CountDownLatch stopped = new CountDownLatch(1);
		TradingEngine engine = engine((job) -> {
			if (job.partsDone() == 1 && job.idempotencyKey().equals(firstKey)) {
				try {
					BotJobResult stop = built.get()
						.stopBotJob(key("POST /api/v1/admin/bot-jobs/{jobId}/stop"), new StopBotJob(job.id(), operator))
						.get(10, TimeUnit.SECONDS);
					assertThat(stop.status()).isEqualTo(BotJobResult.Status.STOPPED);
					stopped.countDown();
				}
				catch (Exception ex) {
					throw new IllegalStateException(ex);
				}
			}
		});
		built.set(engine);
		engine.start();
		instrument(engine);
		BotJob first = start(engine, firstKey, new BotJob.Counts(0, 20, 0)).job();
		assertThat(stopped.await(30, TimeUnit.SECONDS)).isTrue();
		// The part after the stop is answered as stopped, under its own key, and its tokens are removed.
		awaitTrue(() -> jdbc.queryForObject("SELECT count(*) FROM command_result WHERE idempotency_key = ?", Long.class,
				BotJob.partKey(firstKey, 1)) == 1);
		awaitTrue(() -> discarded.size() == PART);
		int handedOver = issued.size();
		assertThat(handedOver).as("the part made and the part refused").isEqualTo(2 * PART);

		Thread.sleep(500);
		assertThat(issued).as("no more tokens for a stopped job").hasSize(handedOver);
		BotJob next = start(engine, UUID.randomUUID(), new BotJob.Counts(0, 6, 0)).job();
		BotJob done = awaitState(next.id(), BotJob.State.DONE);
		assertThat(done.createdTotal()).isEqualTo(6);
		assertThat(job(first.id()).state()).isEqualTo(BotJob.State.STOPPED);
		assertThat(bots()).hasSize(PART + 6);
	}

	/**
	 * A job left running in a simulation before the current one, which no lifecycle allows but a database could
	 * hold, is never taken up: the engine starts on the current simulation and runs nothing, and a part of it asked
	 * for directly commits nothing.
	 */
	@Test
	void aJobOfAnotherSimulationIsNeverTakenUp() throws Exception {
		UUID before = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO simulation (id, state, opened_at, closed_at, writer_epoch, name, created_at)
				VALUES (?, 'CLOSED', now(), now(), 0, ?, now() - interval '1 hour')""", before, "Before " + before);
		BotJob stale = BotJob.start(UUID.randomUUID(), before, operator, UUID.randomUUID(), PART,
				new BotJob.Counts(0, 10, 0), 100, List.of(), java.time.Instant.now());
		store.insertBotJob(stale);
		List<BotJob> run = new CopyOnWriteArrayList<>();

		TradingEngine engine = engine(BotJobRunner.NONE, run::add);
		engine.start();
		long sequence = sequence();

		assertThat(run).isEmpty();
		BotJobResult part = engine.botJobPart(stale).get(10, TimeUnit.SECONDS);
		assertThat(part.status()).isEqualTo(BotJobResult.Status.NOT_FOUND);
		assertThat(sequence()).isEqualTo(sequence);
		assertThat(jdbc.queryForObject("SELECT state || ' ' || parts_done FROM bot_job WHERE id = ?", String.class,
				stale.id())).isEqualTo("RUNNING 0");
		assertThat(bots()).isEmpty();
		assertThat(discarded).hasSize(PART);
	}

	/** An engine as a process starting on this database has it, with a runner whose listener hears each part. */
	private TradingEngine engine(BotJobWorker.Listener listener) {
		return engine(null, listener);
	}

	/**
	 * With {@code runner} given, the engine has that one and no worker; {@code observer} hears what it is asked to
	 * run.
	 */
	private TradingEngine engine(BotJobRunner runner, BotJobWorker.Listener observer) {
		writer = new CommandWriter("job-writer", 1_000);
		AtomicReference<TradingEngine> built = new AtomicReference<>();
		BotJobRunner jobs;
		if (runner == null) {
			worker = new BotJobWorker(built::get, Duration.ofSeconds(5), (millis) -> {
			}, observer);
			jobs = worker;
		}
		else {
			jobs = (job) -> {
				observer.partMade(job);
				runner.run(job);
			};
		}
		TradingEngine engine = new TradingEngine(store, new SpringTransactionRunner(transactionManager), writer,
				Clock.systemUTC(), credentials, AccountChanges.NONE, WriterTimings.NONE, System::nanoTime,
				new RestingOrders(), 5_000, (millis) -> {
				}, SimulationChanges.NONE, CloseSchedule.NONE, PART, jobs);
		built.set(engine);
		return engine;
	}

	private void instrument(TradingEngine engine) throws Exception {
		nova = engine
			.addInstrument(key("POST /api/v1/admin/instruments"),
					new AddInstrument(UUID.randomUUID(), "NOVA", null, Price.parse("100.00"), operator))
			.get(5, TimeUnit.SECONDS)
			.instrumentId();
	}

	private BotJobResult start(TradingEngine engine, UUID jobKey, BotJob.Counts counts) throws Exception {
		return engine
			.startBotJob(new CommandKey(operator, jobKey, JOB_ROUTE, new byte[32]),
					new StartBotJob(UUID.randomUUID(), counts, 100_00, List.of(new ProvisionAccount.Holding(nova, 5)),
							operator))
			.get(5, TimeUnit.SECONDS);
	}

	private CommandKey key(String route) {
		return new CommandKey(operator, UUID.randomUUID(), route, new byte[32]);
	}

	private BotJob job(UUID id) {
		return store.botJob(simulation, id).orElseThrow();
	}

	private BotJob awaitState(UUID id, BotJob.State state) throws InterruptedException {
		awaitTrue(() -> job(id).state() == state);
		return job(id);
	}

	private List<String> bots() {
		return new ArrayList<>(jdbc.queryForList(
				"SELECT display_name FROM account WHERE simulation_id = ? AND kind = 'BOT' ORDER BY created_at, id",
				String.class, simulation));
	}

	private long sequence() {
		return jdbc.queryForObject("SELECT last_command_sequence FROM simulation WHERE id = ?", Long.class, simulation);
	}

	private long partSequence(UUID jobKey, int number) {
		return jdbc.queryForObject("SELECT command_sequence FROM command_result WHERE idempotency_key = ?", Long.class,
				BotJob.partKey(jobKey, number));
	}

	private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
		long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (!condition.getAsBoolean()) {
			assertThat(System.nanoTime()).as("waited 30 s").isLessThan(until);
			Thread.sleep(20);
		}
	}

}
