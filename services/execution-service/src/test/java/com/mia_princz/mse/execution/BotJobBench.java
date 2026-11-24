package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.mia_princz.mse.execution.trading.application.TradingStore;

/**
 * Creating 20,000 bots with one job, end to end over HTTP against PostgreSQL in Testcontainers, with the real token
 * files: how long the job takes, the rate, how long one part holds the writer, and how long other setup commands sent
 * meanwhile wait for their answers. Every bot is checked afterwards: the counts per strategy, one token file each, and
 * the ledger reconciled.
 * <p>
 * Testcontainers runs PostgreSQL with fsync off unless {@code -Dmse.test.fsync=on} is given; the report says which.
 * The token files go to the temporary directory of the machine running the test.
 * <p>
 * Not part of the build; run it on its own:
 * {@code mvnw -pl services/execution-service verify -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false
 * -Dit.test=BotJobBench}, with {@code -Dmse.bench.jobs.bots=} for another size. It writes
 * {@code target/bot-job-<time>.txt}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=bot-job-bench", "mse.dev-users.trader-password=" + BotJobBench.PASSWORD,
				"mse.dev-users.operator-password=" + BotJobBench.PASSWORD })
class BotJobBench {

	static final String PASSWORD = "bench-bot-job-password";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final int BOTS = Integer.getInteger("mse.bench.jobs.bots", 20_000);

	private static Path tokens;

	@DynamicPropertySource
	static void tokenDirectory(DynamicPropertyRegistry registry) throws IOException {
		tokens = Files.createTempDirectory("bot-job-bench-").resolve("secrets");
		registry.add("mse.bots.token-directory", tokens::toString);
	}

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MeterRegistry meters;

	@Autowired
	TradingStore store;

	@Test
	void twentyThousandBots() throws Exception {
		TestBrowser operator = TestBrowser.loggedIn(port, "operator", PASSWORD);
		HttpResponse<String> added = operator.post("/api/v1/admin/instruments",
				"{\"symbol\":\"NOVA\",\"referencePrice\":\"100.00\"}", "Idempotency-Key", UUID.randomUUID().toString());
		String nova = JSON.readTree(added.body()).get("instrumentId").asString();
		int makers = BOTS / 5;
		int momentum = BOTS / 5;
		int noise = BOTS - makers - momentum;
		Timer work = meters.get("mse.writer.work").tag("command", "other").timer();
		Timer commit = meters.get("mse.writer.commit").tag("command", "other").timer();
		long countBefore = work.count();
		double workBefore = work.totalTime(TimeUnit.MILLISECONDS);
		double commitBefore = commit.totalTime(TimeUnit.MILLISECONDS);

		long start = System.nanoTime();
		HttpResponse<String> started = operator.post("/api/v1/admin/bot-jobs", """
				{"count":%d,"strategies":{"marketMakers":%d,"noise":%d,"momentum":%d},"cash":"100000.00",
				 "holdings":[{"instrumentId":"%s","quantity":1000}]}""".formatted(BOTS, makers, noise, momentum, nova),
				"Idempotency-Key", UUID.randomUUID().toString());
		long answered = System.nanoTime();
		assertThat(started.statusCode()).as(started.body()).isEqualTo(202);

		// Other setup commands meanwhile, one every 100 ms: how long each waits for its answer.
		AtomicBoolean running = new AtomicBoolean(true);
		List<Long> waits = Collections.synchronizedList(new ArrayList<>());
		Thread other = Thread.ofPlatform().start(() -> {
			int n = 0;
			while (running.get()) {
				try {
					long sent = System.nanoTime();
					HttpResponse<String> instrument = operator.post("/api/v1/admin/instruments",
							"{\"symbol\":\"%s\",\"referencePrice\":\"10.00\"}".formatted(symbol(n++)), "Idempotency-Key",
							UUID.randomUUID().toString());
					if (instrument.statusCode() == 201) {
						waits.add((System.nanoTime() - sent) / 1_000_000);
					}
					Thread.sleep(100);
				}
				catch (Exception ex) {
					return;
				}
			}
		});
		JsonNode job;
		List<String> samples = new ArrayList<>();
		long nextSample = System.nanoTime();
		do {
			Thread.sleep(200);
			job = JSON.readTree(operator.get("/api/v1/admin/bot-jobs/current").body());
			if (System.nanoTime() >= nextSample) {
				samples.add(String.format(Locale.ROOT, "  %5.1f s: %,d made, %s s left", (System.nanoTime() - start) / 1e9,
						job.get("created").asInt(), job.get("secondsLeft").isNull() ? "?" : job.get("secondsLeft").asLong()));
				nextSample = System.nanoTime() + 2_000_000_000L;
			}
		}
		while ("RUNNING".equals(job.get("state").asString()));
		long finished = System.nanoTime();
		running.set(false);
		other.join();

		assertThat(job.get("state").asString()).as(job.toString()).isEqualTo("DONE");
		long parts = work.count() - countBefore - waits.size();
		double seconds = (finished - start) / 1e9;
		List<Long> sorted = new ArrayList<>(waits);
		Collections.sort(sorted);
		StringBuilder report = new StringBuilder();
		report.append(String.format(Locale.ROOT, "Bot job of %,d bots (%,d market makers, %,d noise, %,d momentum), parts of %d%n",
				BOTS, makers, noise, momentum, job.get("partSize").asInt()));
		report.append("PostgreSQL fsync: ").append(jdbc.queryForObject("SHOW fsync", String.class)).append('\n');
		report.append(String.format(Locale.ROOT, "Start answered in %d ms%n", (answered - start) / 1_000_000));
		report.append(String.format(Locale.ROOT, "Done in %.1f s: %,.0f bots a second, %d parts%n", seconds, BOTS / seconds,
				job.get("partsDone").asInt()));
		report.append(String.format(Locale.ROOT, "Writer, mean per part: statements %.0f ms, commit %.1f ms (over %d transactions, "
				+ "the interleaved commands included); longest statements %.0f ms%n",
				(work.totalTime(TimeUnit.MILLISECONDS) - workBefore) / (parts + waits.size()),
				(commit.totalTime(TimeUnit.MILLISECONDS) - commitBefore) / (parts + waits.size()), parts + waits.size(),
				work.max(TimeUnit.MILLISECONDS)));
		if (!sorted.isEmpty()) {
			report.append(String.format(Locale.ROOT, "Other commands meanwhile: %d, answered in median %d ms, p95 %d ms, max %d ms%n",
					sorted.size(), sorted.get(sorted.size() / 2), sorted.get((int) (sorted.size() * 0.95)),
					sorted.getLast()));
		}
		report.append("Progress as the screen read it:\n");
		samples.forEach((line) -> report.append(line).append('\n'));

		assertThat(jdbc.queryForList("""
				SELECT bot_strategy || ' ' || count(*) FROM account WHERE kind = 'BOT' GROUP BY bot_strategy ORDER BY 1""",
				String.class))
			.containsExactly("MARKET_MAKER " + makers, "MOMENTUM " + momentum, "NOISE " + noise);
		try (Stream<Path> files = Files.list(tokens)) {
			assertThat(files.count()).isEqualTo(BOTS);
		}
		long reconcile = System.nanoTime();
		UUID simulation = jdbc.queryForObject("SELECT id FROM simulation WHERE state <> 'CLOSED'", UUID.class);
		assertThat(store.reconciliationProblems(simulation)).isEmpty();
		report.append(String.format(Locale.ROOT, "Reconciled afterwards in %d ms%n", (System.nanoTime() - reconcile) / 1_000_000));

		System.out.print(report);
		Path file = Path.of("target", "bot-job-" + DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
			.withZone(ZoneOffset.UTC)
			.format(Instant.now()) + ".txt");
		Files.writeString(file, report);
	}

	/** AAAA, AAAB, ...: a fresh symbol for each command. */
	private static String symbol(int n) {
		char[] letters = new char[4];
		for (int i = 3; i >= 0; i--) {
			letters[i] = (char) ('A' + n % 26);
			n /= 26;
		}
		return new String(letters);
	}

}
