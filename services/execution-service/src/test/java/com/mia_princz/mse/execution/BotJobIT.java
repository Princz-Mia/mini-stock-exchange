package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.mia_princz.mse.execution.identity.ApiTokens;
import com.mia_princz.mse.execution.trading.application.BotJob;
import com.mia_princz.mse.execution.trading.application.BotJobWorker;
import com.mia_princz.mse.execution.trading.application.TradingEngine;

/**
 * Bot jobs over real HTTP and PostgreSQL, from an empty market through a job of several parts, a job stopped partway,
 * the run form's counts, and a close and a new simulation, which start with no job. Parts of seven bots, so a job of
 * a few dozen takes several. The job's runner can be held after a part, so the test can look at a job that is
 * still running, deterministically. One test walks the whole sequence, because the market it sets up is shared.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=bot-jobs", "mse.bots.job-part-size=7",
				"mse.dev-users.trader-password=" + BotJobIT.PASSWORD,
				"mse.dev-users.operator-password=" + BotJobIT.PASSWORD })
class BotJobIT {

	static final String PASSWORD = "test-bot-jobs-password";

	private static final OpenApiContract CONTRACT = OpenApiContract.get();

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String JOBS = "/api/v1/admin/bot-jobs";

	private static final String CURRENT = JOBS + "/current";

	private static final String STOP = JOBS + "/{jobId}/stop";

	private static final String OPEN = "/api/v1/admin/market/open";

	private static final Path TOKENS;

	static {
		try {
			TOKENS = Files.createTempDirectory("bot-job-tokens-").resolve("secrets");
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static final Gate GATE = new Gate();

	/** Holds the job's runner after a given part has committed, until released; so a running job can be looked at. */
	static final class Gate implements BotJobWorker.Listener {

		private volatile int holdAfter;

		private volatile CountDownLatch held = new CountDownLatch(1);

		private volatile CountDownLatch release = new CountDownLatch(1);

		void holdAfter(int parts) {
			held = new CountDownLatch(1);
			release = new CountDownLatch(1);
			holdAfter = parts;
		}

		void awaitHeld() throws InterruptedException {
			assertThat(held.await(30, TimeUnit.SECONDS)).as("the runner reached the part it is held after").isTrue();
		}

		void release() {
			holdAfter = 0;
			release.countDown();
		}

		@Override
		public void partMade(BotJob job) {
			if (job.partsDone() != holdAfter) {
				return;
			}
			held.countDown();
			try {
				release.await(30, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}

	}

	@TestConfiguration(proxyBeanMethods = false)
	static class HeldRunner {

		@Bean
		@Primary
		BotJobWorker heldBotJobWorker(ObjectProvider<TradingEngine> engine) {
			return new BotJobWorker(engine::getObject, Duration.ofSeconds(5), Thread::sleep, GATE);
		}

	}

	@DynamicPropertySource
	static void tokenDirectory(DynamicPropertyRegistry registry) {
		registry.add("mse.bots.token-directory", TOKENS::toString);
	}

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void aJobMakesAnyNumberOfBotsInPartsWithProgressEveryScreenReads() throws Exception {
		TestBrowser operator = TestBrowser.loggedIn(port, "operator", PASSWORD);
		String nova = instrument(operator, "NOVA", "100.00");
		HttpResponse<String> none = operator.get(CURRENT);
		assertThat(none.statusCode()).as("no job yet").isEqualTo(204);
		CONTRACT.assertResponse("GET", CURRENT, 204, null, none.body());

		// Strategies that do not add up to the count are refused, with every problem named.
		HttpResponse<String> mismatch = start(operator, UUID.randomUUID(), 20, 4, 12, 3, "1000.00", nova);
		assertThat(fields(mismatch)).containsExactly("strategies");
		CONTRACT.assertResponse("POST", JOBS, 400, mismatch.headers().firstValue("Content-Type").orElse(null),
				mismatch.body());

		// A job of 40 bots: 8 market makers, 24 noise, 8 momentum, in parts of 7. Held after its first part.
		GATE.holdAfter(1);
		UUID jobKey = UUID.randomUUID();
		HttpResponse<String> started = start(operator, jobKey, 40, 8, 24, 8, "1000.00", nova);
		assertThat(started.statusCode()).as(started.body()).isEqualTo(202);
		CONTRACT.assertResponse("POST", JOBS, 202, started.headers().firstValue("Content-Type").orElse(null),
				started.body());
		JsonNode job = JSON.readTree(started.body());
		String jobId = job.get("jobId").asString();
		assertThat(job.get("state").asString()).isEqualTo("RUNNING");
		assertThat(job.get("total").asInt()).isEqualTo(40);
		assertThat(job.get("partSize").asInt()).isEqualTo(7);
		GATE.awaitHeld();

		// Progress, as committed, on any screen: the first part made 7 market makers.
		HttpResponse<String> running = operator.get(CURRENT);
		CONTRACT.assertResponse("GET", CURRENT, 200, running.headers().firstValue("Content-Type").orElse(null),
				running.body());
		JsonNode progress = JSON.readTree(running.body());
		assertThat(progress.get("jobId").asString()).isEqualTo(jobId);
		assertThat(progress.get("state").asString()).isEqualTo("RUNNING");
		assertThat(progress.get("created").asInt()).isEqualTo(7);
		assertThat(progress.get("partsDone").asInt()).isEqualTo(1);
		assertThat(progress.get("strategies").get("marketMakers").get("created").asInt()).isEqualTo(7);
		assertThat(progress.get("strategies").get("noise").get("requested").asInt()).isEqualTo(24);
		assertThat(progress.get("secondsLeft").isNumber()).as("estimated from the first part: %s", progress).isTrue();
		assertThat(progress.get("finishedAt").isNull()).isTrue();
		HttpResponse<String> byId = operator.get(JOBS + "/" + jobId);
		CONTRACT.assertResponse("GET", JOBS + "/{jobId}", 200, byId.headers().firstValue("Content-Type").orElse(null),
				byId.body());
		assertThat(JSON.readTree(byId.body()).get("created").asInt()).isEqualTo(7);

		// The same key is the same job, however far it has got; another request under it is a conflict.
		HttpResponse<String> again = start(operator, jobKey, 40, 8, 24, 8, "1000.00", nova);
		assertThat(again.statusCode()).isEqualTo(202);
		assertThat(JSON.readTree(again.body()).get("jobId").asString()).isEqualTo(jobId);
		assertThat(JSON.readTree(again.body()).get("created").asInt()).isEqualTo(7);
		assertThat(code(start(operator, jobKey, 41, 9, 24, 8, "1000.00", nova), 409)).isEqualTo("IDEMPOTENCY_CONFLICT");

		// One job at a time; and the market does not open while bots are being made.
		HttpResponse<String> second = start(operator, UUID.randomUUID(), 5, 0, 5, 0, "1.00");
		assertThat(code(second, 409)).isEqualTo("BOT_JOB_RUNNING");
		CONTRACT.assertResponse("POST", JOBS, 409, second.headers().firstValue("Content-Type").orElse(null),
				second.body());
		assertThat(JSON.readTree(second.body()).get("botJob").get("jobId").asString()).isEqualTo(jobId);
		HttpResponse<String> opening = operator.post(OPEN, "", "Idempotency-Key", UUID.randomUUID().toString());
		assertThat(code(opening, 409)).isEqualTo("BOT_JOB_RUNNING");
		CONTRACT.assertResponse("POST", OPEN, 409, opening.headers().firstValue("Content-Type").orElse(null),
				opening.body());
		JsonNode refusal = JSON.readTree(opening.body());
		assertThat(refusal.get("detail").asString()).isEqualTo(
				"Bots are still being created: 7 of 40 bots are made. The market can open once the job is done or stopped");
		assertThat(refusal.get("botJob").get("created").asInt()).isEqualTo(7);
		assertThat(refusal.get("state").asString()).isEqualTo("SETUP");
		assertThat(jdbc.queryForObject("SELECT state FROM simulation WHERE state <> 'CLOSED'", String.class))
			.isEqualTo("SETUP");

		// Other setup commands are taken while the job runs, and the population counts the bots as they are made.
		String orbt = instrument(operator, "ORBT", "50.00");
		assertThat(orbt).isNotNull();
		assertThat(population(operator)).isEqualTo("7: 7 0 0");

		GATE.release();
		JsonNode done = awaitState(operator, "DONE");
		CONTRACT.assertResponse("GET", CURRENT, 200, "application/json", done.toString());
		assertThat(done.get("created").asInt()).isEqualTo(40);
		assertThat(done.get("partsDone").asInt()).as("2 + 4 + 2 parts of up to 7").isEqualTo(8);
		assertThat(done.get("secondsLeft").isNull()).isTrue();
		assertThat(done.get("finishedAt").isNull()).isFalse();
		assertThat(population(operator)).isEqualTo("40: 8 24 8");

		// Exactly the counts asked for, named in the order made, each with its tag, endowment and token.
		List<Map<String, Object>> bots = jdbc.queryForList(
				"SELECT id, display_name, bot_strategy FROM account WHERE kind = 'BOT' ORDER BY created_at, id");
		assertThat(bots).hasSize(40);
		for (int i = 0; i < bots.size(); i++) {
			assertThat(bots.get(i).get("display_name")).isEqualTo(String.format("bot-%04d", i + 1));
		}
		assertThat(bots.subList(0, 7)).as("the first part's market makers")
			.allMatch((bot) -> "MARKET_MAKER".equals(bot.get("bot_strategy")));
		for (Map<String, Object> bot : bots) {
			UUID id = (UUID) bot.get("id");
			String token = Files.readString(TOKENS.resolve(id + ".token"), StandardCharsets.US_ASCII).strip();
			assertThat(jdbc.queryForObject("SELECT token_hash FROM api_token WHERE account_id = ?", byte[].class, id))
				.isEqualTo(ApiTokens.hash(token));
			assertThat(jdbc.queryForList("""
					SELECT coalesce(i.symbol, 'CASH') || ' ' || a.amount FROM initial_allocation a
					LEFT JOIN instrument i ON i.id = a.instrument_id WHERE a.account_id = ? ORDER BY 1""", String.class,
					id)).containsExactly("CASH 100000", "NOVA 10");
		}
		assertThat(tokenFiles()).hasSize(40);
		assertThat(unbalancedBatches()).isZero();
		// Each part is one provisioning command, under its key derived from the job's and its number.
		UUID operatorId = jdbc.queryForObject("SELECT id FROM app_user WHERE username = 'operator'", UUID.class);
		for (int number = 0; number < 8; number++) {
			assertThat(jdbc.queryForObject("""
					SELECT route || ' ' || response_status FROM command_result
					WHERE principal_id = ? AND idempotency_key = ?""", String.class, operatorId,
					BotJob.partKey(jobKey, number))).isEqualTo("BOT_JOB_PART 201");
		}
		assertThat(jdbc.queryForList("""
				SELECT action || ' ' || outcome FROM audit_log
				WHERE action IN ('BOT_JOB_START', 'BOT_PROVISION', 'MARKET_OPEN') ORDER BY command_sequence""",
				String.class))
			.containsExactly("BOT_JOB_START APPLIED", "BOT_PROVISION APPLIED", "BOT_JOB_START NOT_ALLOWED",
					"MARKET_OPEN NOT_ALLOWED", "BOT_PROVISION APPLIED", "BOT_PROVISION APPLIED", "BOT_PROVISION APPLIED",
					"BOT_PROVISION APPLIED", "BOT_PROVISION APPLIED", "BOT_PROVISION APPLIED", "BOT_PROVISION APPLIED");

		// A job stopped after its second part: the 14 made stay, the third part makes nothing.
		GATE.holdAfter(2);
		UUID stoppedKey = UUID.randomUUID();
		HttpResponse<String> stopping = start(operator, stoppedKey, 21, 0, 21, 0, "500.00");
		assertThat(stopping.statusCode()).as(stopping.body()).isEqualTo(202);
		String stoppedId = JSON.readTree(stopping.body()).get("jobId").asString();
		GATE.awaitHeld();
		HttpResponse<String> stopped = operator.post(JOBS + "/" + stoppedId + "/stop", "", "Idempotency-Key",
				UUID.randomUUID().toString());
		assertThat(stopped.statusCode()).as(stopped.body()).isEqualTo(200);
		CONTRACT.assertResponse("POST", STOP, 200, stopped.headers().firstValue("Content-Type").orElse(null),
				stopped.body());
		assertThat(JSON.readTree(stopped.body()).get("state").asString()).isEqualTo("STOPPED");
		assertThat(JSON.readTree(stopped.body()).get("created").asInt()).isEqualTo(14);
		GATE.release();
		// The runner goes on to the third part, which finds the job stopped: refused, its files deleted.
		awaitTrue(() -> jdbc.queryForObject("SELECT count(*) FROM command_result WHERE idempotency_key = ?", Long.class,
				BotJob.partKey(stoppedKey, 2)) == 1);
		assertThat(jdbc.queryForObject("SELECT response_status FROM command_result WHERE idempotency_key = ?",
				Integer.class, BotJob.partKey(stoppedKey, 2))).isEqualTo(409);
		assertThat(botCount()).isEqualTo(54);
		awaitTrue(() -> tokenFiles().size() == 54);
		JsonNode afterStop = JSON.readTree(operator.get(CURRENT).body());
		assertThat(afterStop.get("jobId").asString()).isEqualTo(stoppedId);
		assertThat(afterStop.get("state").asString()).isEqualTo("STOPPED");
		assertThat(afterStop.get("created").asInt()).isEqualTo(14);
		HttpResponse<String> stopAgain = operator.post(JOBS + "/" + stoppedId + "/stop", "", "Idempotency-Key",
				UUID.randomUUID().toString());
		assertThat(code(stopAgain, 409)).isEqualTo("BOT_JOB_NOT_RUNNING");
		CONTRACT.assertResponse("POST", STOP, 409, stopAgain.headers().firstValue("Content-Type").orElse(null),
				stopAgain.body());
		HttpResponse<String> unknown = operator.post(JOBS + "/" + UUID.randomUUID() + "/stop", "", "Idempotency-Key",
				UUID.randomUUID().toString());
		assertThat(code(unknown, 404)).isEqualTo("BOT_JOB_NOT_FOUND");
		CONTRACT.assertResponse("POST", STOP, 404, unknown.headers().firstValue("Content-Type").orElse(null),
				unknown.body());
		HttpResponse<String> missing = operator.get(JOBS + "/" + UUID.randomUUID());
		assertThat(code(missing, 404)).isEqualTo("BOT_JOB_NOT_FOUND");
		CONTRACT.assertResponse("GET", JOBS + "/{jobId}", 404,
				missing.headers().firstValue("Content-Type").orElse(null), missing.body());

		// A trader may not start, read or stop a job.
		TestBrowser trader = TestBrowser.loggedIn(port, "trader", PASSWORD);
		assertThat(start(trader, UUID.randomUUID(), 1, 0, 1, 0, "1.00").statusCode()).isEqualTo(403);
		assertThat(trader.get(CURRENT).statusCode()).isEqualTo(403);

		// The run form's counts follow the bots made: 8 market makers, 38 noise, 8 momentum.
		assertThat(operator.post(OPEN, "", "Idempotency-Key", UUID.randomUUID().toString()).statusCode())
			.isEqualTo(200);
		HttpResponse<String> short1 = operator.post("/api/v1/admin/bot-runs",
				"{\"marketMakers\":9,\"noise\":1,\"momentum\":0,\"commandsPerSecond\":\"1.00\"}", "Idempotency-Key",
				UUID.randomUUID().toString());
		assertThat(code(short1, 422)).isEqualTo("NOT_ENOUGH_BOTS");
		assertThat(JSON.readTree(short1.body()).get("shortfall").get("available").asInt()).isEqualTo(8);
		HttpResponse<String> run = operator.post("/api/v1/admin/bot-runs",
				"{\"marketMakers\":8,\"noise\":38,\"momentum\":8,\"commandsPerSecond\":\"1.00\"}", "Idempotency-Key",
				UUID.randomUUID().toString());
		assertThat(run.statusCode()).as(run.body()).isEqualTo(201);

		// Once the market has opened, a job is refused.
		HttpResponse<String> late = start(operator, UUID.randomUUID(), 3, 1, 1, 1, "1.00");
		assertThat(code(late, 409)).isEqualTo("MARKET_NOT_IN_SETUP");
		CONTRACT.assertResponse("POST", JOBS, 409, late.headers().firstValue("Content-Type").orElse(null), late.body());

		// A close and a new simulation carry no job: the new one starts with none.
		assertThat(operator.post("/api/v1/admin/market/close", "", "Idempotency-Key", UUID.randomUUID().toString())
			.statusCode()).isEqualTo(200);
		assertThat(JSON.readTree(operator.get(CURRENT).body()).get("jobId").asString())
			.as("the closed simulation still shows its last job").isEqualTo(stoppedId);
		HttpResponse<String> next = operator.post("/api/v1/admin/simulations", "{\"name\":\"After the jobs\"}",
				"Idempotency-Key", UUID.randomUUID().toString());
		assertThat(next.statusCode()).as(next.body()).isEqualTo(201);
		assertThat(operator.get(CURRENT).statusCode()).isEqualTo(204);
		assertThat(operator.get(JOBS + "/" + stoppedId).statusCode()).as("a job of the simulation before")
			.isEqualTo(404);
	}

	private JsonNode awaitState(TestBrowser operator, String state) throws Exception {
		long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		JsonNode job;
		do {
			job = JSON.readTree(operator.get(CURRENT).body());
			if (state.equals(job.get("state").asString())) {
				return job;
			}
			Thread.sleep(50);
		}
		while (System.nanoTime() < until);
		throw new AssertionError("The job did not become " + state + ": " + job);
	}

	private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
		long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (!condition.getAsBoolean()) {
			assertThat(System.nanoTime()).as("waited 30 s").isLessThan(until);
			Thread.sleep(50);
		}
	}

	private static HttpResponse<String> start(TestBrowser browser, UUID key, int count, int makers, int noise,
			int momentum, String cash, String... instruments) throws Exception {
		List<String> holdings = new ArrayList<>();
		for (String instrument : instruments) {
			holdings.add("{\"instrumentId\":\"%s\",\"quantity\":10}".formatted(instrument));
		}
		String body = """
				{"count":%d,"strategies":{"marketMakers":%d,"noise":%d,"momentum":%d},"cash":"%s","holdings":[%s]}"""
			.formatted(count, makers, noise, momentum, cash, String.join(",", holdings));
		return browser.post(JOBS, body, "Idempotency-Key", key.toString());
	}

	private static String population(TestBrowser operator) throws Exception {
		JsonNode body = JSON.readTree(operator.get("/api/v1/admin/bots").body());
		JsonNode tags = body.get("byStrategy");
		return body.get("population").asInt() + ": " + tags.get("marketMakers").asInt() + " " + tags.get("noise").asInt()
				+ " " + tags.get("momentum").asInt();
	}

	private long botCount() {
		return jdbc.queryForObject("SELECT count(*) FROM account WHERE kind = 'BOT'", Long.class);
	}

	private long unbalancedBatches() {
		return jdbc.queryForObject("""
				SELECT count(*) FROM (SELECT batch_id FROM ledger_entry GROUP BY batch_id, instrument_id
				                      HAVING sum(delta) <> 0) unbalanced""", Long.class);
	}

	private static List<String> tokenFiles() {
		try (Stream<Path> files = Files.list(TOKENS)) {
			return files.map((file) -> file.getFileName().toString()).toList();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static String instrument(TestBrowser operator, String symbol, String price) throws Exception {
		HttpResponse<String> added = operator.post("/api/v1/admin/instruments",
				"{\"symbol\":\"%s\",\"referencePrice\":\"%s\"}".formatted(symbol, price), "Idempotency-Key",
				UUID.randomUUID().toString());
		assertThat(added.statusCode()).as(added.body()).isEqualTo(201);
		return JSON.readTree(added.body()).get("instrumentId").asString();
	}

	private static String code(HttpResponse<String> problem, int status) {
		assertThat(problem.statusCode()).as(problem.body()).isEqualTo(status);
		return JSON.readTree(problem.body()).get("code").asString();
	}

	private static List<String> fields(HttpResponse<String> problem) {
		assertThat(code(problem, 400)).isEqualTo("VALIDATION_FAILED");
		List<String> fields = new ArrayList<>();
		JSON.readTree(problem.body()).get("errors").forEach((error) -> fields.add(error.get("field").asString()));
		return fields;
	}

}
