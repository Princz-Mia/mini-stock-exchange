package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import com.mia_princz.mse.execution.trading.adapter.JdbcTradingStore;
import com.mia_princz.mse.execution.trading.adapter.SpringTransactionRunner;
import com.mia_princz.mse.execution.trading.application.AccountChanges;
import com.mia_princz.mse.execution.trading.application.CancelOrder;
import com.mia_princz.mse.execution.trading.application.CancelResult;
import com.mia_princz.mse.execution.trading.application.CommandKey;
import com.mia_princz.mse.execution.trading.application.CommandWriter;
import com.mia_princz.mse.execution.trading.application.PlaceOrder;
import com.mia_princz.mse.execution.trading.application.PlacementResult;
import com.mia_princz.mse.execution.trading.application.TradingEngine;
import com.mia_princz.mse.execution.trading.application.WriterTimings;
import com.mia_princz.mse.execution.trading.domain.OrderType;
import com.mia_princz.mse.execution.trading.domain.Price;
import com.mia_princz.mse.execution.trading.domain.Quantity;
import com.mia_princz.mse.execution.trading.domain.Side;

/**
 * The writer's own ceiling with commands sent ahead of it, so that runs form: a seeded sequence of orders and cancels
 * sent with up to 600 waiting, through the real engine, store and writer, committing to PostgreSQL in Testcontainers.
 * The sequence never depends on answers (a cancel names an order its account sent earlier, whatever became of it), so
 * every run sends the same commands, and the digest of the answers shows two runs did the same work whatever their
 * run limit or code.
 *
 * <p>
 * The report splits the writer's time by phase and by kind of statement. Run from a Windows host, the database is
 * reached through Docker Desktop's port forwarding, which adds tens of milliseconds to a statement of more than a few
 * kilobytes while PostgreSQL itself spends a few: a run's statements are large, so there the bench understates what
 * runs gain. Inside Compose the service reaches PostgreSQL on Docker's own network; compare runs there.
 *
 * <p>
 * Not part of the build; run it on its own, with {@code -Dmse.bench.max-batch} (default 32),
 * {@code -Dmse.bench.max-parameters} (the store's statement size, default 30,000) and {@code -Dmse.test.fsync=on} for
 * durable commits, and optionally {@code -Dmse.bench.jfr=on} for a flight recording:
 * {@code mvnw -pl services/execution-service verify -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false
 * -Dit.test=WriterRunsBench}. It writes {@code target/writer-runs-<time>.txt} (and {@code .jfr}).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "mse.test.database=writer-runs")
class WriterRunsBench {

	private static final long SEED = 20261119L;

	private static final int INSTRUMENTS = 5;

	private static final int ACCOUNTS = 200;

	private static final int WARM_UP = 2_000;

	private static final int MEASURED = 20_000;

	private static final int AHEAD = 600;

	private static final long MID_CENTS = 10_000;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	DataSource dataSource;

	@Autowired
	PlatformTransactionManager transactionManager;

	private final List<UUID> instruments = new ArrayList<>();

	private final List<UUID> accounts = new ArrayList<>();

	@Test
	void sendAhead() throws Exception {
		int maxBatch = Integer.getInteger("mse.bench.max-batch", 32);
		new TransactionTemplate(transactionManager).executeWithoutResult((status) -> market());
		List<Integer> runs = new CopyOnWriteArrayList<>();
		Map<String, long[]> timed = new java.util.concurrent.ConcurrentHashMap<>();
		WriterTimings timings = (phase, command, nanos) -> timed
			.computeIfAbsent(phase + " " + command, (ignored) -> new long[2])[0] += nanos;
		CommandWriter writer = new CommandWriter("bench-writer", 1_000, maxBatch, runs::add);
		StatementTimes statements = new StatementTimes();
		DataSource timedSource = statements.wrap(dataSource);
		TradingEngine engine = new TradingEngine(new JdbcTradingStore(JdbcClient.create(timedSource),
				JsonMapper.builder().build(), Integer.getInteger("mse.bench.max-parameters", 30_000)),
				new SpringTransactionRunner(new org.springframework.jdbc.support.JdbcTransactionManager(timedSource)),
				writer,
				Clock.systemUTC(), (account) -> {
					throw new UnsupportedOperationException("no bots here");
				}, AccountChanges.NONE, timings, System::nanoTime);
		engine.start();
		jdk.jfr.Recording recording = null;
		Path base = Path.of("target", "writer-runs-" + Instant.now().toString().replace(':', '-'));
		if ("on".equals(System.getProperty("mse.bench.jfr"))) {
			recording = new jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile"));
			recording.setDestination(Path.of(base + ".jfr"));
		}
		try {
			Random random = new Random(SEED);
			Map<UUID, Deque<UUID>> sent = new HashMap<>();
			accounts.forEach((account) -> sent.put(account, new ArrayDeque<>()));
			List<CompletableFuture<?>> answers = new ArrayList<>(WARM_UP + MEASURED);
			long started = 0;
			int runsBefore = 0;
			for (int i = 0; i < WARM_UP + MEASURED; i++) {
				if (i >= AHEAD) {
					answers.get(i - AHEAD).handle((result, failure) -> null).join();
				}
				if (i == WARM_UP) {
					// Every warm-up command answered, so the measured ones start on an idle writer.
					answers.forEach((answer) -> answer.handle((result, failure) -> null).join());
					started = System.nanoTime();
					runsBefore = runs.size();
					timed.clear();
					statements.clear();
					if (recording != null) {
						recording.start();
					}
				}
				answers.add(send(engine, i, random, sent));
			}
			answers.forEach((answer) -> answer.handle((result, failure) -> null).join());
			long wall = System.nanoTime() - started;
			if (recording != null) {
				recording.stop();
			}
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			int[] counts = new int[4];
			for (CompletableFuture<?> answer : answers) {
				digest.update(answer(answer, counts).getBytes(StandardCharsets.UTF_8));
			}
			List<Integer> measuredRuns = runs.subList(runsBefore, runs.size());
			String report = report(maxBatch, wall, counts, measuredRuns, timed,
					HexFormat.of().formatHex(digest.digest())) + statements.report(MEASURED);
			System.out.println(report);
			Files.writeString(Path.of(base + ".txt"), report);
			assertThat(counts[3]).as("commands that failed outright").isZero();
		}
		finally {
			if (recording != null) {
				recording.close();
			}
			writer.close(Duration.ofSeconds(5));
		}
	}

	/** One command, chosen by the seeded generator alone. */
	private CompletableFuture<?> send(TradingEngine engine, int index, Random random, Map<UUID, Deque<UUID>> sent) {
		int kind = random.nextInt(100);
		UUID account = accounts.get(random.nextInt(ACCOUNTS));
		Deque<UUID> mine = sent.get(account);
		if (kind < 30 && !mine.isEmpty()) {
			// Usually the account's oldest order not yet cancelled, sometimes its newest.
			UUID target = random.nextInt(3) == 0 ? mine.pollLast() : mine.pollFirst();
			return engine.cancel(key(account, index), new CancelOrder(target, account));
		}
		Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
		OrderType type = kind >= 90 ? OrderType.MARKET_PROTECTED : OrderType.LIMIT;
		long ticks = type == OrderType.MARKET_PROTECTED ? 20 : random.nextInt(21) - 10;
		UUID instrument = instruments.get(random.nextInt(INSTRUMENTS));
		PlaceOrder order = new PlaceOrder(new UUID(SEED, index), account, instrument, side, type,
				new Price(side == Side.BUY ? MID_CENTS + ticks : MID_CENTS - ticks),
				new Quantity(1 + random.nextInt(10)));
		if (type == OrderType.LIMIT) {
			mine.addLast(order.orderId());
		}
		return engine.place(key(account, index), order);
	}

	/** The answer in a stable form for the digest. */
	private static String answer(CompletableFuture<?> answer, int[] counts) throws InterruptedException {
		try {
			Object result = answer.get();
			if (result instanceof PlacementResult placed) {
				counts[placed.rejected() ? 2 : 0]++;
				return "P" + placed.state() + placed.rejection() + placed.filled() + "/" + placed.working() + "/"
						+ placed.canceled() + "@" + placed.commandSequence();
			}
			CancelResult canceled = (CancelResult) result;
			counts[1]++;
			return "C" + canceled.status() + canceled.canceledQuantity() + canceled.commandSequence();
		}
		catch (ExecutionException ex) {
			counts[3]++;
			return "F" + ex.getCause().getClass().getSimpleName();
		}
	}

	private static CommandKey key(UUID account, int index) {
		byte[] hash = new byte[32];
		hash[0] = (byte) index;
		hash[1] = (byte) (index >> 8);
		return new CommandKey(account, new UUID(~SEED, index), "bench", hash);
	}

	private String report(int maxBatch, long wall, int[] counts, List<Integer> runs, Map<String, long[]> timed,
			String digest) {
		int[] sizes = runs.stream().mapToInt(Integer::intValue).sorted().toArray();
		double perCommand = (double) wall / MEASURED / 1e6;
		String durability = "fsync=" + setting("fsync") + ", synchronous_commit=" + setting("synchronous_commit");
		StringBuilder phases = new StringBuilder();
		new java.util.TreeMap<>(timed).forEach((name, total) -> phases
			.append("    %-18s %8.1f ms in all, %.3f ms a command%n".formatted(name, total[0] / 1e6,
					total[0] / 1e6 / MEASURED)));
		return """
				Writer runs: %d warm-up then %d measured commands, up to %d waiting, run limit %d
				  placed %d, rejected %d, cancels %d, failed %d
				  throughput %.0f commands/s (%.3f ms a command)
				  runs %d, mean size %.1f, median %d, largest %d
				  writer time by phase:
				%s  answers digest %s
				  PostgreSQL: %s
				""".formatted(WARM_UP, MEASURED, AHEAD, maxBatch, counts[0], counts[2], counts[1], counts[3],
				MEASURED / (wall / 1e9), perCommand, sizes.length, Arrays.stream(sizes).average().orElse(0),
				sizes.length == 0 ? 0 : sizes[sizes.length / 2], sizes.length == 0 ? 0 : sizes[sizes.length - 1],
				phases, digest, durability);
	}

	/**
	 * Time spent in each kind of statement on the writer's connections, kinds told apart by their text with every
	 * VALUES list, IN list and number collapsed, so a statement of 3 rows and one of 30 count as one kind.
	 */
	private static final class StatementTimes {

		private final Map<String, long[]> byKind = new java.util.concurrent.ConcurrentHashMap<>();

		DataSource wrap(DataSource target) {
			return (DataSource) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
					new Class<?>[] { DataSource.class }, (proxy, method, args) -> {
						Object answer = invoke(method, target, args);
						return answer instanceof java.sql.Connection connection ? connection(connection) : answer;
					});
		}

		private java.sql.Connection connection(java.sql.Connection real) {
			return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
					new Class<?>[] { java.sql.Connection.class }, (proxy, method, args) -> {
						Object answer = invoke(method, real, args);
						if (answer instanceof java.sql.PreparedStatement statement
								&& method.getName().equals("prepareStatement")) {
							return statement(statement, kind((String) args[0]));
						}
						return answer;
					});
		}

		private Object statement(java.sql.PreparedStatement real, String kind) {
			return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
					new Class<?>[] { java.sql.PreparedStatement.class }, (proxy, method, args) -> {
						if (!method.getName().startsWith("execute")) {
							return invoke(method, real, args);
						}
						long start = System.nanoTime();
						try {
							return invoke(method, real, args);
						}
						finally {
							long[] totals = byKind.computeIfAbsent(kind, (ignored) -> new long[2]);
							synchronized (totals) {
								totals[0]++;
								totals[1] += System.nanoTime() - start;
							}
						}
					});
		}

		private static String kind(String sql) {
			String flat = sql.replaceAll("\\s+", " ")
				.replaceAll("\\((\\?(::\\w+(\\[\\])?)?, ?)*\\?(::\\w+(\\[\\])?)?\\)(, ?\\((\\?(::\\w+(\\[\\])?)?, ?)*\\?(::\\w+(\\[\\])?)?\\))*", "(...)")
				.trim();
			return flat.length() > 90 ? flat.substring(0, 90) : flat;
		}

		void clear() {
			byKind.clear();
		}

		String report(int commands) {
			StringBuilder report = new StringBuilder("  statements by kind (count, ms in all, ms a command):\n");
			byKind.entrySet()
				.stream()
				.sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]))
				.forEach((entry) -> report.append("    %7d %9.1f %7.3f  %s%n".formatted(entry.getValue()[0],
						entry.getValue()[1] / 1e6, entry.getValue()[1] / 1e6 / commands, entry.getKey())));
			return report.toString();
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

	private String setting(String name) {
		return jdbc.queryForObject("SHOW " + name, String.class);
	}

	/** An open market with five instruments at 100.00 and 200 bot accounts, each with cash and shares of every one. */
	private void market() {
		UUID simulation = UUID.randomUUID();
		jdbc.update("INSERT INTO simulation (id, state, opened_at) VALUES (?, 'OPEN', now())", simulation);
		UUID issuance = UUID.randomUUID();
		jdbc.update("INSERT INTO account (id, simulation_id, kind, display_name) VALUES (?, ?, 'ISSUANCE', 'Issuance')",
				issuance, simulation);
		for (int i = 0; i < INSTRUMENTS; i++) {
			UUID instrument = new UUID(SEED, -1 - i);
			instruments.add(instrument);
			jdbc.update("INSERT INTO instrument (id, simulation_id, symbol, reference_price_cents) VALUES (?, ?, ?, ?)",
					instrument, simulation, "SYM" + (char) ('A' + i), MID_CENTS);
		}
		String post = "INSERT INTO ledger_entry (batch_id, account_id, instrument_id, bucket, delta) VALUES (?, ?, ?, 'AVAILABLE', ?)";
		for (int a = 0; a < ACCOUNTS; a++) {
			UUID account = new UUID(~SEED, -1 - a);
			accounts.add(account);
			jdbc.update("INSERT INTO account (id, simulation_id, kind, display_name) VALUES (?, ?, 'BOT', ?)", account,
					simulation, "bot-%04d".formatted(a + 1));
			UUID batch = UUID.randomUUID();
			jdbc.update("INSERT INTO journal_batch (id, simulation_id, cause, command_sequence) VALUES (?, ?, 'GENESIS', 1)",
					batch, simulation);
			jdbc.update(post, batch, issuance, null, -100_000_000L);
			jdbc.update(post, batch, account, null, 100_000_000L);
			jdbc.update("""
					INSERT INTO cash_balance (account_id, account_kind, total_cents, reserved_cents)
					VALUES (?, 'BOT', 100000000, 0)""", account);
			for (UUID instrument : instruments) {
				jdbc.update(post, batch, issuance, instrument, -10_000);
				jdbc.update(post, batch, account, instrument, 10_000);
				jdbc.update("""
						INSERT INTO position (account_id, account_kind, instrument_id, total_quantity, reserved_quantity)
						VALUES (?, 'BOT', ?, 10000, 0)""", account, instrument);
			}
		}
	}

}
