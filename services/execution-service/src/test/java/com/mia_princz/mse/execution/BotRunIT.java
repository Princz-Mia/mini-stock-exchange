package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Starting and stopping bot runs over real HTTP and PostgreSQL. One test walks the whole sequence, because the market
 * it sets up is shared. The worker's reports are written straight to the table, as the simulator will.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=bot-runs", "mse.dev-users.trader-password=" + BotRunIT.PASSWORD,
				"mse.dev-users.operator-password=" + BotRunIT.PASSWORD })
class BotRunIT {

	static final String PASSWORD = "test-bot-run-password";

	private static final OpenApiContract CONTRACT = OpenApiContract.get();

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String RUNS = "/api/v1/admin/bot-runs";

	private static final String STOP = "/api/v1/admin/bot-runs/{runId}/stop";

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PostgreSQLContainer postgres;

	@Test
	void runsStartOneAtATimeAndStopOnRequestOrWhenTheirTimeIsUp() throws Exception {
		TestBrowser operator = TestBrowser.loggedIn(port, "operator", PASSWORD);
		String nova = instrument(operator, "NOVA", "100.00");
		String zeta = instrument(operator, "ZETA", "20.00");
		HttpResponse<String> provisioned = operator.post("/api/v1/admin/bots", """
				{"count":4,"cash":"10000.00","holdings":[{"instrumentId":"%s","quantity":50}]}""".formatted(nova),
				"Idempotency-Key", UUID.randomUUID().toString());
		assertThat(provisioned.statusCode()).as(provisioned.body()).isEqualTo(201);
		List<UUID> bots = jdbc.queryForList("SELECT id FROM account WHERE kind = 'BOT' ORDER BY created_at, id",
				UUID.class);
		assertThat(runs(operator)).isEmpty();

		// Before the market opens, a start is a committed refusal.
		UUID setupKey = UUID.randomUUID();
		HttpResponse<String> early = start(operator, setupKey, run(1, 1, 0, "1.00"));
		assertThat(code(early, 409)).isEqualTo("MARKET_NOT_OPEN");
		assertThat(JSON.readTree(early.body()).get("commandSequence").asString()).matches("[1-9][0-9]*");
		assertThat(operator.post("/api/v1/admin/market/open", "", "Idempotency-Key", UUID.randomUUID().toString())
			.statusCode()).isEqualTo(200);

		// Invalid requests are refused before the writer, with every problem listed.
		assertThat(fields(start(operator, UUID.randomUUID(), "{\"commandsPerSecond\":\"0.00\",\"durationMinutes\":0}")))
			.containsExactlyInAnyOrder("noise", "commandsPerSecond", "durationMinutes");
		assertThat(fields(start(operator, UUID.randomUUID(), """
				{"noise":1001,"seed":-1,"commandsPerSecond":"200.01","maxOrderQuantity":0,"maxPosition":1000001,
				"instrumentIds":["%s","%s"]}""".formatted(nova, nova))))
			.containsExactlyInAnyOrder("noise", "seed", "commandsPerSecond", "maxOrderQuantity", "maxPosition",
					"instrumentIds");
		assertThat(fields(start(operator, UUID.randomUUID(), "{\"noise\":1,\"commandsPerSecond\":\"1.5\"}")))
			.containsExactly("commandsPerSecond");

		// More bots than exist, or an instrument the market lacks, are committed refusals.
		UUID tooManyKey = UUID.randomUUID();
		assertThat(code(start(operator, tooManyKey, run(2, 2, 1, "1.00")), 422)).isEqualTo("NOT_ENOUGH_BOTS");
		UUID unknownKey = UUID.randomUUID();
		assertThat(code(start(operator, unknownKey, """
				{"noise":1,"commandsPerSecond":"1.00","instrumentIds":["%s"]}""".formatted(UUID.randomUUID())), 422))
			.isEqualTo("UNKNOWN_INSTRUMENT");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM bot_run", Long.class)).isZero();

		// A run: market makers take the oldest bots, then noise, then momentum.
		UUID firstKey = UUID.randomUUID();
		String firstBody = """
				{"marketMakers":1,"noise":2,"momentum":1,"seed":42,"commandsPerSecond":"2.50","maxOrderQuantity":7,
				"maxPosition":300,"instrumentIds":["%s"]}""".formatted(nova);
		HttpResponse<String> started = start(operator, firstKey, firstBody);
		assertThat(started.statusCode()).as(started.body()).isEqualTo(201);
		CONTRACT.assertResponse("POST", RUNS, 201, started.headers().firstValue("Content-Type").orElse(null),
				started.body());
		JsonNode first = JSON.readTree(started.body());
		assertThat(first.get("bots").asInt()).isEqualTo(4);
		UUID firstRun = UUID.fromString(first.get("runId").asString());
		assertThat(jdbc.queryForList("SELECT account_id || ' ' || strategy FROM bot_assignment WHERE run_id = ?",
				String.class, firstRun))
			.containsExactlyInAnyOrder(bots.get(0) + " MARKET_MAKER", bots.get(1) + " NOISE", bots.get(2) + " NOISE",
					bots.get(3) + " MOMENTUM");
		assertThat(jdbc.queryForObject("SELECT start_sequence FROM bot_run WHERE id = ?", Long.class, firstRun))
			.isEqualTo(Long.parseLong(first.get("commandSequence").asString()));

		// The same key repeats the answer; a different request with it is a conflict; a second run waits its turn.
		assertThat(start(operator, firstKey, firstBody).body()).isEqualTo(started.body());
		assertThat(code(start(operator, firstKey, run(1, 2, 1, "2.50")), 409)).isEqualTo("IDEMPOTENCY_CONFLICT");
		assertThat(code(start(operator, firstKey, firstBody.replace(nova, zeta)), 409)).isEqualTo("IDEMPOTENCY_CONFLICT");
		UUID activeKey = UUID.randomUUID();
		assertThat(code(start(operator, activeKey, run(0, 1, 0, "1.00")), 409)).isEqualTo("RUN_ACTIVE");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM bot_run", Long.class)).isEqualTo(1);

		// The list shows the configuration, and a status that follows the worker's lease.
		JsonNode listed = runs(operator).get(0);
		assertThat(listed.get("runId").asString()).isEqualTo(firstRun.toString());
		assertThat(listed.get("status").asString()).isEqualTo("STARTING");
		assertThat(listed.get("endsAt").isNull()).isTrue();
		JsonNode config = listed.get("config");
		assertThat(config.get("seed").asString()).isEqualTo("42");
		assertThat(config.get("commandsPerSecond").asString()).isEqualTo("2.50");
		assertThat(config.get("maxOrderQuantity").asInt()).isEqualTo(7);
		assertThat(config.get("maxPosition").asInt()).isEqualTo(300);
		assertThat(config.get("instruments")).hasSize(1);
		assertThat(config.get("instruments").get(0).get("symbol").asString()).isEqualTo("NOVA");
		assertThat(config.get("strategies").toString()).isEqualTo("{\"marketMakers\":1,\"noise\":2,\"momentum\":1}");
		report(firstRun, "worker-a", Duration.ofSeconds(30));
		assertThat(runs(operator).get(0).get("status").asString()).isEqualTo("RUNNING");
		assertThat(runs(operator).get(0).get("worker").get("workerId").asString()).isEqualTo("worker-a");
		assertThat(runs(operator).get(0).get("activity").get("placed").asLong()).isEqualTo(12);
		report(firstRun, "worker-a", Duration.ofSeconds(-1));
		assertThat(runs(operator).get(0).get("status").asString()).isEqualTo("STALE");
		report(firstRun, "worker-a", Duration.ofSeconds(30));

		// Stopping: replayed with its key, refused once stopped or for a run that isn't there.
		UUID stopKey = UUID.randomUUID();
		HttpResponse<String> stopped = stop(operator, stopKey, firstRun);
		assertThat(stopped.statusCode()).as(stopped.body()).isEqualTo(200);
		CONTRACT.assertResponse("POST", STOP, 200, stopped.headers().firstValue("Content-Type").orElse(null),
				stopped.body());
		assertThat(JSON.readTree(stopped.body()).get("runId").asString()).isEqualTo(firstRun.toString());
		assertThat(stop(operator, stopKey, firstRun).body()).isEqualTo(stopped.body());
		UUID againKey = UUID.randomUUID();
		assertThat(code(stop(operator, againKey, firstRun), 409)).isEqualTo("RUN_NOT_RUNNING");
		UUID missingKey = UUID.randomUUID();
		assertThat(code(stop(operator, missingKey, UUID.randomUUID()), 404)).isEqualTo("RUN_NOT_FOUND");
		assertThat(jdbc.queryForObject("SELECT desired_state FROM bot_run WHERE id = ?", String.class, firstRun))
			.isEqualTo("STOPPED");
		assertThat(runs(operator).get(0).get("status").asString()).as("until the worker confirms").isEqualTo("STOPPING");
		jdbc.update("UPDATE bot_run SET stopped_at = now(), in_flight = 0 WHERE id = ?", firstRun);
		assertThat(runs(operator).get(0).get("status").asString()).isEqualTo("STOPPED");

		// Without a seed one is chosen; without instruments the run trades them all; a run with a time limit gives
		// way once its time is up, and is recorded as stopped at its end.
		HttpResponse<String> timed = start(operator, UUID.randomUUID(),
				"{\"noise\":2,\"commandsPerSecond\":\"0.50\",\"durationMinutes\":1}");
		assertThat(timed.statusCode()).as(timed.body()).isEqualTo(201);
		UUID timedRun = UUID.fromString(JSON.readTree(timed.body()).get("runId").asString());
		assertThat(jdbc.queryForList("SELECT account_id FROM bot_assignment WHERE run_id = ?", UUID.class, timedRun))
			.as("the oldest bots").containsExactlyInAnyOrder(bots.get(0), bots.get(1));
		JsonNode timedView = runs(operator).get(0);
		assertThat(timedView.get("runId").asString()).isEqualTo(timedRun.toString());
		assertThat(Long.parseLong(timedView.get("config").get("seed").asString())).isNotNegative();
		List<String> symbols = new ArrayList<>();
		timedView.get("config").get("instruments").forEach((i) -> symbols.add(i.get("symbol").asString()));
		assertThat(symbols).containsExactly("NOVA", "ZETA");
		assertThat(Duration.between(Instant.parse(timedView.get("startedAt").asString()),
				Instant.parse(timedView.get("endsAt").asString()))).isEqualTo(Duration.ofMinutes(1));
		assertThat(code(start(operator, UUID.randomUUID(), run(0, 1, 0, "1.00")), 409)).isEqualTo("RUN_ACTIVE");
		Instant endedAt = rewind(timedRun, Duration.ofMinutes(2));
		assertThat(runs(operator)).filteredOn((r) -> r.get("runId").asString().equals(timedRun.toString()))
			.singleElement()
			.satisfies((r) -> assertThat(r.get("status").asString()).as("its time is up").isEqualTo("STOPPED"));

		// A halted market still runs bots.
		assertThat(operator.post("/api/v1/admin/market/halt", "", "Idempotency-Key",
				UUID.randomUUID().toString()).statusCode()).isEqualTo(200);
		HttpResponse<String> halted = start(operator, UUID.randomUUID(),
				"{\"momentum\":1,\"commandsPerSecond\":\"1.00\",\"instrumentIds\":[\"%s\"]}".formatted(zeta));
		assertThat(halted.statusCode()).as(halted.body()).isEqualTo(201);
		assertThat(jdbc.queryForObject("SELECT desired_state || ' ' || (stop_requested_at = ?) FROM bot_run WHERE id = ?",
				String.class, Timestamp.from(endedAt), timedRun)).isEqualTo("STOPPED true");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM bot_run WHERE desired_state = 'RUNNING'", Long.class))
			.isEqualTo(1);
		HttpResponse<String> limited = operator.get(RUNS + "?limit=2");
		CONTRACT.assertResponse("GET", RUNS, 200, limited.headers().firstValue("Content-Type").orElse(null),
				limited.body());
		assertThat(JSON.readTree(limited.body()).get("runs")).hasSize(2);
		assertThat(code(operator.get(RUNS + "?limit=51"), 400)).isEqualTo("VALIDATION_FAILED");

		// Operators only.
		TestBrowser trader = TestBrowser.loggedIn(port, "trader", PASSWORD);
		assertThat(trader.get(RUNS).statusCode()).isEqualTo(403);
		assertThat(start(trader, UUID.randomUUID(), run(0, 1, 0, "1.00")).statusCode()).isEqualTo(403);
		assertThat(stop(trader, UUID.randomUUID(), timedRun).statusCode()).isEqualTo(403);

		List<String> audit = jdbc.queryForList("""
				SELECT action || ' ' || outcome || ' ' || correlation_id FROM audit_log
				WHERE action IN ('BOT_RUN_START', 'BOT_RUN_STOP')
				  AND correlation_id IN (?, ?, ?, ?, ?, ?, ?, ?) ORDER BY command_sequence""", String.class, setupKey,
				tooManyKey, unknownKey, firstKey, activeKey, stopKey, againKey, missingKey);
		assertThat(audit).containsExactly("BOT_RUN_START NOT_ALLOWED " + setupKey,
				"BOT_RUN_START NOT_ALLOWED " + tooManyKey, "BOT_RUN_START NOT_ALLOWED " + unknownKey,
				"BOT_RUN_START APPLIED " + firstKey, "BOT_RUN_START NOT_ALLOWED " + activeKey,
				"BOT_RUN_STOP APPLIED " + stopKey, "BOT_RUN_STOP NOT_ALLOWED " + againKey,
				"BOT_RUN_STOP NOT_ALLOWED " + missingKey);
	}

	/** What a worker's heartbeat writes. */
	private void report(UUID run, String worker, Duration lease) {
		jdbc.update("""
				UPDATE bot_run SET worker_id = ?, heartbeat_at = now(), lease_expires_at = ?, placed = 12, canceled = 3,
				    rejected = 1, in_flight = 2 WHERE id = ?""", worker, Timestamp.from(Instant.now().plus(lease)), run);
	}

	/** Moves a run into the past; only the owner role may change when a run started. */
	private Instant rewind(UUID run, Duration by) throws Exception {
		String url = TestcontainersConfiguration.jdbcUrl(postgres, "execution_db");
		try (Connection owner = DriverManager.getConnection(url, "execution_owner",
				TestcontainersConfiguration.EXECUTION_OWNER_PASSWORD);
				PreparedStatement statement = owner.prepareStatement("""
						UPDATE bot_run SET started_at = started_at - make_interval(secs => ?),
						    ends_at = ends_at - make_interval(secs => ?) WHERE id = ? RETURNING ends_at""")) {
			statement.setLong(1, by.toSeconds());
			statement.setLong(2, by.toSeconds());
			statement.setObject(3, run);
			try (var result = statement.executeQuery()) {
				assertThat(result.next()).isTrue();
				return result.getTimestamp(1).toInstant();
			}
		}
	}

	private static String run(int marketMakers, int noise, int momentum, String rate) {
		return "{\"marketMakers\":%d,\"noise\":%d,\"momentum\":%d,\"commandsPerSecond\":\"%s\"}".formatted(marketMakers,
				noise, momentum, rate);
	}

	private static List<JsonNode> runs(TestBrowser operator) throws Exception {
		HttpResponse<String> read = operator.get(RUNS);
		assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
		CONTRACT.assertResponse("GET", RUNS, 200, read.headers().firstValue("Content-Type").orElse(null), read.body());
		List<JsonNode> runs = new ArrayList<>();
		JSON.readTree(read.body()).get("runs").forEach(runs::add);
		return runs;
	}

	private static HttpResponse<String> start(TestBrowser browser, UUID key, String body) throws Exception {
		return browser.post(RUNS, body, "Idempotency-Key", key.toString());
	}

	private static HttpResponse<String> stop(TestBrowser browser, UUID key, UUID run) throws Exception {
		return browser.post(RUNS + "/" + run + "/stop", "", "Idempotency-Key", key.toString());
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
