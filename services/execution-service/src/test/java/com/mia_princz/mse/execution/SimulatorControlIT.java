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

import com.mia_princz.mse.execution.identity.ApiTokens;

/**
 * The simulator's control plane over real HTTP and PostgreSQL: its token, the assignment, the lease, takeover, and
 * stops confirmed by the worker. One test walks the whole sequence, because the market it sets up is shared.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=simulator-control",
				"mse.dev-users.trader-password=" + SimulatorControlIT.PASSWORD,
				"mse.dev-users.operator-password=" + SimulatorControlIT.PASSWORD,
				"mse.simulator.token=" + SimulatorControlIT.SIMULATOR_TOKEN,
				"mse.exports.token=" + SimulatorControlIT.EXPORT_TOKEN, "mse.simulator.lease=20s" })
class SimulatorControlIT {

	static final String PASSWORD = "test-simulator-control-password";

	static final String SIMULATOR_TOKEN = "test-simulator-token";

	static final String EXPORT_TOKEN = "test-export-token";

	private static final OpenApiContract CONTRACT = OpenApiContract.get();

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String ASSIGNMENT = "/internal/simulator/assignment";

	private static final String HEARTBEAT = "/internal/simulator/heartbeat";

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PostgreSQLContainer postgres;

	@Test
	void oneWorkerHoldsARunAndConfirmsItsStop() throws Exception {
		TestBrowser operator = TestBrowser.loggedIn(port, "operator", PASSWORD);
		TestBrowser simulator = new TestBrowser(port);
		String nova = instrument(operator, "NOVA", "100.00");
		String zeta = instrument(operator, "ZETA", "20.00");
		// Tagged out of assignment order: momentum, then noise, then a market maker.
		for (String strategy : new String[] { "MOMENTUM", "NOISE", "MARKET_MAKER" }) {
			HttpResponse<String> provisioned = operator.post("/api/v1/admin/bots", """
					{"count":1,"cash":"10000.00","holdings":[{"instrumentId":"%s","quantity":50}],"strategy":"%s"}"""
				.formatted(nova, strategy), "Idempotency-Key", UUID.randomUUID().toString());
			assertThat(provisioned.statusCode()).as(provisioned.body()).isEqualTo(201);
		}
		List<UUID> bots = jdbc.queryForList("SELECT id FROM account WHERE kind = 'BOT' ORDER BY created_at, id",
				UUID.class);

		// Only the simulator's token opens its routes, and it opens nothing else.
		assertThat(simulator.get(ASSIGNMENT).statusCode()).isEqualTo(401);
		assertThat(simulator.get(ASSIGNMENT, "Authorization", "Bearer wrong").statusCode()).isEqualTo(401);
		assertThat(simulator.get(ASSIGNMENT, bearer(EXPORT_TOKEN)).statusCode()).isEqualTo(403);
		assertThat(simulator.post(HEARTBEAT, "{}", bearer(EXPORT_TOKEN)).statusCode()).isEqualTo(403);
		assertThat(operator.get(ASSIGNMENT).statusCode()).as("an operator's session doesn't count").isEqualTo(401);
		assertThat(simulator.get("/internal/exports/trades?instrumentId=" + nova, bearer(SIMULATOR_TOKEN))
			.statusCode()).isEqualTo(403);
		assertThat(simulator.post("/api/v1/orders", """
				{"instrumentId":"%s","side":"BUY","type":"LIMIT","timeInForce":"GTC","quantity":1,"limitPrice":"1.00"}"""
			.formatted(nova), "Authorization", "Bearer " + SIMULATOR_TOKEN, "Idempotency-Key",
				UUID.randomUUID().toString()).statusCode())
			.as("it can't trade").isEqualTo(401);
		assertThat(assignment(simulator).get("run").isNull()).as("no run yet").isTrue();

		// A run stopped before any worker took it has nothing to settle, so it isn't assigned.
		assertThat(operator.post("/api/v1/admin/market/open", "", "Idempotency-Key", UUID.randomUUID().toString())
			.statusCode()).isEqualTo(200);
		UUID unworked = start(operator, "{\"noise\":1,\"commandsPerSecond\":\"1.00\"}");
		assertThat(operator.post("/api/v1/admin/bot-runs/" + unworked + "/stop", "", "Idempotency-Key",
				UUID.randomUUID().toString()).statusCode()).isEqualTo(200);
		assertThat(assignment(simulator).get("run").isNull()).isTrue();

		// A run: the assignment names each bot's credential file, never a token.
		UUID first = start(operator, """
				{"marketMakers":1,"noise":1,"momentum":1,"seed":9,"commandsPerSecond":"3.00","maxOrderQuantity":5,
				"maxPosition":40,"instrumentIds":["%s"]}""".formatted(nova));
		HttpResponse<String> read = simulator.get(ASSIGNMENT, bearer(SIMULATOR_TOKEN));
		assertThat(read.body()).doesNotContain(ApiTokens.PREFIX);
		JsonNode run = assignment(simulator).get("run");
		assertThat(run.get("runId").asString()).isEqualTo(first.toString());
		assertThat(run.get("desiredState").asString()).isEqualTo("RUNNING");
		assertThat(run.get("seed").asString()).isEqualTo("9");
		assertThat(run.get("commandsPerSecond").asString()).isEqualTo("3.00");
		assertThat(run.get("maxOrderQuantity").asInt()).isEqualTo(5);
		assertThat(run.get("maxPosition").asInt()).isEqualTo(40);
		assertThat(run.get("leaseSeconds").asLong()).isEqualTo(20);
		assertThat(run.get("worker").get("workerId").isNull()).isTrue();
		assertThat(run.get("instruments").get(0).get("symbol").asString()).isEqualTo("NOVA");
		List<String> assigned = new ArrayList<>();
		run.get("bots").forEach((bot) -> assigned.add(bot.get("accountId").asString() + " "
				+ bot.get("strategy").asString() + " " + bot.get("credentialRef").asString()));
		// Each bot runs the strategy it is tagged with, and the list stays in creation order.
		assertThat(assigned).containsExactly(bots.get(0) + " MOMENTUM " + bots.get(0) + ".token",
				bots.get(1) + " NOISE " + bots.get(1) + ".token", bots.get(2) + " MARKET_MAKER " + bots.get(2) + ".token");

		// The first heartbeat takes the lease; a retry, or lower totals, change nothing.
		Instant before = Instant.now();
		JsonNode beat = beat(simulator, first, "worker-a", 10, 2, 1, 0, 3, false, 200);
		assertThat(beat.get("desiredState").asString()).isEqualTo("RUNNING");
		assertThat(beat.get("stopped").asBoolean()).isFalse();
		assertThat(Duration.between(before, Instant.parse(beat.get("leaseExpiresAt").asString())))
			.isBetween(Duration.ofSeconds(19), Duration.ofSeconds(21));
		beat(simulator, first, "worker-a", 10, 2, 1, 0, 3, false, 200);
		beat(simulator, first, "worker-a", 4, 1, 0, 0, 1, false, 200);
		JsonNode listed = operatorView(operator, first);
		assertThat(listed.get("status").asString()).isEqualTo("RUNNING");
		assertThat(listed.get("worker").get("workerId").asString()).isEqualTo("worker-a");
		assertThat(listed.get("activity").toString()).isEqualTo(
				"{\"placed\":10,\"canceled\":2,\"rejected\":1,\"errors\":0,\"inFlight\":1,\"stopFailures\":0}");
		assertThat(assignment(simulator).get("run").get("totals").get("placed").asLong()).isEqualTo(10);

		// Another worker waits until the lease lapses, then takes over.
		assertThat(problem(beatResponse(simulator, first, "worker-b", 0, 0, 0, 0, 0, false), 409))
			.isEqualTo("LEASE_HELD");
		assertThat(problem(beatResponse(simulator, first, "worker-a", 10, 2, 1, 0, 0, true), 409))
			.isEqualTo("RUN_NOT_STOPPING");
		jdbc.update("UPDATE bot_run SET lease_expires_at = now() - interval '1 second' WHERE id = ?", first);
		assertThat(operatorView(operator, first).get("status").asString()).isEqualTo("STALE");
		beat(simulator, first, "worker-b", 11, 2, 1, 0, 0, false, 200);
		assertThat(operatorView(operator, first).get("worker").get("workerId").asString()).isEqualTo("worker-b");
		assertThat(problem(beatResponse(simulator, first, "worker-a", 12, 2, 1, 0, 0, false), 409))
			.isEqualTo("LEASE_HELD");

		// Invalid heartbeats list every problem; an unknown run is not found.
		assertThat(fields(simulator.post(HEARTBEAT, "{\"workerId\":\"has space\",\"placed\":-1}",
				bearer(SIMULATOR_TOKEN)))).containsExactlyInAnyOrder("runId", "workerId", "placed", "canceled",
						"rejected", "errors", "inFlight", "stopFailures");
		assertThat(fields(beatResponse(simulator, first, "worker-b", 11, 2, 1, 0, 2, true))).containsExactly("inFlight");
		assertThat(problem(beatResponse(simulator, UUID.randomUUID(), "worker-b", 0, 0, 0, 0, 0, false), 404))
			.isEqualTo("RUN_NOT_FOUND");

		// Stopped by the operator: the worker still holds it until it confirms, and a stopping run comes before a new
		// one, so its quotes are cancelled before new trading starts.
		assertThat(operator.post("/api/v1/admin/bot-runs/" + first + "/stop", "", "Idempotency-Key",
				UUID.randomUUID().toString()).statusCode()).isEqualTo(200);
		UUID second = start(operator, """
				{"noise":1,"momentum":1,"commandsPerSecond":"1.00","durationMinutes":1,"instrumentIds":["%s"]}""".formatted(zeta));
		JsonNode stopping = assignment(simulator).get("run");
		assertThat(stopping.get("runId").asString()).isEqualTo(first.toString());
		assertThat(stopping.get("desiredState").asString()).isEqualTo("STOPPED");
		assertThat(beat(simulator, first, "worker-b", 12, 5, 1, 0, 1, false, 200).get("desiredState").asString())
			.isEqualTo("STOPPED");
		assertThat(operatorView(operator, first).get("status").asString()).isEqualTo("STOPPING");
		JsonNode confirmed = beat(simulator, first, "worker-b", 12, 6, 1, 0, 0, true, 200);
		assertThat(confirmed.get("stopped").asBoolean()).isTrue();
		assertThat(Instant.parse(confirmed.get("leaseExpiresAt").asString())).as("the lease is released")
			.isBeforeOrEqualTo(Instant.now());
		JsonNode done = operatorView(operator, first);
		assertThat(done.get("status").asString()).isEqualTo("STOPPED");
		assertThat(done.get("stoppedAt").isNull()).isFalse();
		assertThat(done.get("activity").get("canceled").asLong()).isEqualTo(6);
		assertThat(problem(beatResponse(simulator, first, "worker-b", 12, 6, 1, 0, 0, false), 409))
			.isEqualTo("RUN_FINISHED");

		// Then the new run; once its time is up the worker confirms, and it is recorded as stopped at its end.
		assertThat(assignment(simulator).get("run").get("runId").asString()).isEqualTo(second.toString());
		beat(simulator, second, "worker-b", 1, 0, 0, 0, 0, false, 200);
		Instant endedAt = rewind(second, Duration.ofMinutes(2));
		JsonNode expired = assignment(simulator).get("run");
		assertThat(expired.get("runId").asString()).isEqualTo(second.toString());
		assertThat(expired.get("desiredState").asString()).isEqualTo("STOPPED");
		beat(simulator, second, "worker-b", 2, 0, 0, 0, 0, true, 200);
		assertThat(jdbc.queryForObject("""
				SELECT desired_state || ' ' || (stop_requested_at = ?) || ' ' || (stopped_at IS NOT NULL)
				FROM bot_run WHERE id = ?""", String.class, Timestamp.from(endedAt), second))
			.isEqualTo("STOPPED true true");
		assertThat(assignment(simulator).get("run").isNull()).isTrue();
		start(operator, "{\"noise\":1,\"commandsPerSecond\":\"1.00\"}");
	}

	private static String[] bearer(String token) {
		return new String[] { "Authorization", "Bearer " + token };
	}

	private static JsonNode assignment(TestBrowser simulator) throws Exception {
		HttpResponse<String> read = simulator.get(ASSIGNMENT, bearer(SIMULATOR_TOKEN));
		assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
		CONTRACT.assertResponse("GET", ASSIGNMENT, 200, read.headers().firstValue("Content-Type").orElse(null),
				read.body());
		return JSON.readTree(read.body());
	}

	private static HttpResponse<String> beatResponse(TestBrowser simulator, UUID run, String worker, long placed,
			long canceled, long rejected, long errors, int inFlight, boolean stopped) throws Exception {
		return simulator.post(HEARTBEAT, """
				{"runId":"%s","workerId":"%s","placed":%d,"canceled":%d,"rejected":%d,"errors":%d,"inFlight":%d,
				"stopFailures":0,"stopped":%s}""".formatted(run, worker, placed, canceled, rejected, errors, inFlight,
				stopped), bearer(SIMULATOR_TOKEN));
	}

	private static JsonNode beat(TestBrowser simulator, UUID run, String worker, long placed, long canceled,
			long rejected, long errors, int inFlight, boolean stopped, int status) throws Exception {
		HttpResponse<String> beat = beatResponse(simulator, run, worker, placed, canceled, rejected, errors, inFlight,
				stopped);
		assertThat(beat.statusCode()).as(beat.body()).isEqualTo(status);
		CONTRACT.assertResponse("POST", HEARTBEAT, status, beat.headers().firstValue("Content-Type").orElse(null),
				beat.body());
		return JSON.readTree(beat.body());
	}

	private static JsonNode operatorView(TestBrowser operator, UUID run) throws Exception {
		HttpResponse<String> read = operator.get("/api/v1/admin/bot-runs");
		assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
		for (JsonNode listed : JSON.readTree(read.body()).get("runs")) {
			if (listed.get("runId").asString().equals(run.toString())) {
				return listed;
			}
		}
		throw new AssertionError("run " + run + " is not listed");
	}

	private static UUID start(TestBrowser operator, String body) throws Exception {
		HttpResponse<String> started = operator.post("/api/v1/admin/bot-runs", body, "Idempotency-Key",
				UUID.randomUUID().toString());
		assertThat(started.statusCode()).as(started.body()).isEqualTo(201);
		return UUID.fromString(JSON.readTree(started.body()).get("runId").asString());
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

	private static String instrument(TestBrowser operator, String symbol, String price) throws Exception {
		HttpResponse<String> added = operator.post("/api/v1/admin/instruments",
				"{\"symbol\":\"%s\",\"referencePrice\":\"%s\"}".formatted(symbol, price), "Idempotency-Key",
				UUID.randomUUID().toString());
		assertThat(added.statusCode()).as(added.body()).isEqualTo(201);
		return JSON.readTree(added.body()).get("instrumentId").asString();
	}

	private static String problem(HttpResponse<String> problem, int status) {
		assertThat(problem.statusCode()).as(problem.body()).isEqualTo(status);
		return JSON.readTree(problem.body()).get("code").asString();
	}

	private static List<String> fields(HttpResponse<String> problem) {
		assertThat(problem(problem, 400)).isEqualTo("VALIDATION_FAILED");
		List<String> fields = new ArrayList<>();
		JSON.readTree(problem.body()).get("errors").forEach((error) -> fields.add(error.get("field").asString()));
		return fields;
	}

}
