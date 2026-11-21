package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
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
 * A bot run's window in the command sequence, over every way a run ends: an operator stop the worker confirms after
 * the run's closing commands, a run the next start ends (one that never had a worker, or whose worker went quiet), and
 * a run whose time runs out. The next start waits while a worker is still finishing a run. Two windows never overlap,
 * each instrument's trades in a window lie between the trade sequences recorded at its edges, and a run from before
 * windows were kept never gets one. One test walks it all, on one market.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=run-windows", "mse.dev-users.operator-password=" + RunWindowIT.PASSWORD,
				"mse.simulator.token=" + RunWindowIT.SIMULATOR_TOKEN, "mse.simulator.lease=20s" })
class RunWindowIT {

	static final String PASSWORD = "test-run-windows-password";

	static final String SIMULATOR_TOKEN = "test-run-windows-simulator-token";

	private static final OpenApiContract CONTRACT = OpenApiContract.get();

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String RATE = "\"commandsPerSecond\":\"1.00\"";

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PostgreSQLContainer postgres;

	private TestBrowser seller;

	private TestBrowser buyer;

	@Test
	void aRunsWindowRunsFromItsStartToItsLastCommandAndNeverOverlapsTheNext() throws Exception {
		TestBrowser operator = TestBrowser.loggedIn(port, "operator", PASSWORD);
		TestBrowser simulator = new TestBrowser(port);
		String nova = instrument(operator, "NOVA");
		String zeta = instrument(operator, "ZETA");
		for (String strategy : new String[] { "MARKET_MAKER", "NOISE" }) {
			post(operator, "/api/v1/admin/bots", """
					{"count":1,"cash":"10000.00","holdings":[{"instrumentId":"%s","quantity":50}],"strategy":"%s"}"""
				.formatted(nova, strategy));
		}
		post(operator, "/api/v1/admin/traders", """
				{"username":"seller.windows","password":"%s","cash":"1000.00","holdings":[{"instrumentId":"%s",
				"quantity":100},{"instrumentId":"%s","quantity":100}]}""".formatted(PASSWORD, nova, zeta));
		post(operator, "/api/v1/admin/traders", """
				{"username":"buyer.windows","password":"%s","cash":"100000.00","holdings":[]}""".formatted(PASSWORD));
		post(operator, "/api/v1/admin/market/open", "");
		seller = TestBrowser.loggedIn(port, "seller.windows", PASSWORD);
		buyer = TestBrowser.loggedIn(port, "buyer.windows", PASSWORD);
		// A run from before windows were kept: it never gets an end.
		UUID legacy = legacyRun();
		trade(nova);
		trade(zeta);

		// A run starts at its start command, with each instrument's newest trade as its lower edge.
		JsonNode started = post(operator, "/api/v1/admin/bot-runs", "{\"marketMakers\":1,\"noise\":1," + RATE + "}");
		UUID first = UUID.fromString(started.get("runId").asString());
		long firstStart = started.get("commandSequence").asLong();
		assertThat(window(operator, first)).containsExactly(Long.toString(firstStart), null, null, null);
		assertThat(edges(first, nova)).containsExactly(1L, null);
		assertThat(edges(first, zeta)).containsExactly(1L, null);
		trade(nova);

		// While its worker holds a live lease, a stopped run is still finishing: the next start waits.
		beat(simulator, first, false);
		long firstStop = post(operator, "/api/v1/admin/bot-runs/" + first + "/stop", "").get("commandSequence").asLong();
		assertThat(window(operator, first)).containsExactly(Long.toString(firstStart), Long.toString(firstStop), null,
				null);
		assertThat(refusal(operator, "{\"noise\":1," + RATE + "}")).isEqualTo("RUN_ACTIVE");
		// Its closing commands come after the stop and still belong to it.
		long tail = trade(zeta);
		beat(simulator, first, true);
		assertThat(window(operator, first)).containsExactly(Long.toString(firstStart), Long.toString(firstStop),
				Long.toString(tail), "WORKER");
		// It ended when its worker confirmed the stop.
		assertThat(listed(operator, first).get("window").get("endedAt").asString())
			.isEqualTo(listed(operator, first).get("stoppedAt").asString());
		assertThat(edges(first, nova)).containsExactly(1L, 2L);
		assertThat(edges(first, zeta)).containsExactly(1L, 2L);

		// A run on one instrument, stopped and replaced before a worker ever took it: the next start ends it one
		// before itself, after commands that came between.
		UUID second = UUID.fromString(post(operator, "/api/v1/admin/bot-runs",
				"{\"noise\":1,\"instrumentIds\":[\"" + nova + "\"]," + RATE + "}").get("runId").asString());
		assertThat(edges(second, nova)).containsExactly(2L, null);
		post(operator, "/api/v1/admin/bot-runs/" + second + "/stop", "");
		trade(nova);
		long between = trade(zeta);
		JsonNode replacing = post(operator, "/api/v1/admin/bot-runs", "{\"noise\":1," + RATE + "}");
		UUID third = UUID.fromString(replacing.get("runId").asString());
		long thirdStart = replacing.get("commandSequence").asLong();
		assertThat(thirdStart - 1).isEqualTo(between);
		assertThat(window(operator, second).subList(2, 4)).containsExactly(Long.toString(between), "NEXT_START");
		// It ended when the next run started.
		String secondEnded = listed(operator, second).get("window").get("endedAt").asString();
		assertThat(secondEnded).isEqualTo(listed(operator, third).get("startedAt").asString());
		assertThat(edges(second, nova)).containsExactly(2L, 3L);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM bot_run_instrument WHERE run_id = ?", Long.class, second))
			.as("only its own instrument").isEqualTo(1);
		// Confirming it later moves neither end, though more has traded since.
		trade(nova);
		beat(simulator, second, true);
		assertThat(window(operator, second).subList(2, 4)).containsExactly(Long.toString(between), "NEXT_START");
		// Nor when it ended: the confirmation came after, and its stop says so.
		JsonNode confirmed = listed(operator, second);
		assertThat(confirmed.get("window").get("endedAt").asString()).isEqualTo(secondEnded);
		assertThat(Instant.parse(confirmed.get("stoppedAt").asString())).isAfterOrEqualTo(Instant.parse(secondEnded));
		assertThat(edges(second, nova)).containsExactly(2L, 3L);

		// A worker that went quiet doesn't hold the next start up: its lease lapsed, so the start ends the run.
		beat(simulator, third, false);
		post(operator, "/api/v1/admin/bot-runs/" + third + "/stop", "");
		asOwner("UPDATE bot_run SET lease_expires_at = now() - interval '1 second' WHERE id = ?", third);
		JsonNode afterQuiet = post(operator, "/api/v1/admin/bot-runs",
				"{\"noise\":1,\"durationMinutes\":1," + RATE + "}");
		UUID timed = UUID.fromString(afterQuiet.get("runId").asString());
		assertThat(window(operator, third).subList(2, 4))
			.containsExactly(Long.toString(afterQuiet.get("commandSequence").asLong() - 1), "NEXT_START");
		assertThat(listed(operator, third).get("window").get("endedAt").asString())
			.isEqualTo(listed(operator, timed).get("startedAt").asString());

		// A run whose time ran out ends when its worker confirms, with no operator stop.
		asOwner("UPDATE bot_run SET started_at = started_at - interval '2 minutes', "
				+ "ends_at = ends_at - interval '2 minutes' WHERE id = ?", timed);
		long last = trade(nova);
		beat(simulator, timed, true);
		assertThat(window(operator, timed).subList(1, 4)).containsExactly(null, Long.toString(last), "WORKER");

		// No two windows overlap; the old run kept no end through every start.
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM bot_run a JOIN bot_run b ON a.id <> b.id
				WHERE a.end_sequence IS NOT NULL AND b.start_sequence BETWEEN a.start_sequence AND a.end_sequence""",
				Long.class)).isZero();
		assertThat(window(operator, legacy).subList(2, 4)).containsExactly(null, "NOT_KEPT");
		assertThat(listed(operator, legacy).get("window").get("endedAt").isNull()).isTrue();

		// The runtime role records ends, never rewrites where a run started, and can't end a run before its start.
		assertThatThrownBy(() -> jdbc.update("UPDATE bot_run SET start_sequence = 1 WHERE id = ?", first)).rootCause()
			.hasMessageContaining("permission denied");
		assertThatThrownBy(
				() -> jdbc.update("UPDATE bot_run_instrument SET start_trade_sequence = 0 WHERE run_id = ?", first))
			.rootCause()
			.hasMessageContaining("permission denied");
		assertThatThrownBy(() -> jdbc.update("UPDATE bot_run SET end_sequence = start_sequence - 1 WHERE id = ?", first))
			.rootCause()
			.hasMessageContaining("check constraint");
	}

	/** A seller's offer lifted by a buyer at 100.00: one trade. Returns the buyer's command sequence. */
	private long trade(String instrument) throws Exception {
		String order = """
				{"instrumentId":"%s","side":"%s","type":"LIMIT","timeInForce":"GTC","quantity":1,"limitPrice":"100.00"}""";
		post(seller, "/api/v1/orders", order.formatted(instrument, "SELL"));
		JsonNode bought = post(buyer, "/api/v1/orders", order.formatted(instrument, "BUY"));
		assertThat(bought.get("filledQuantity").asLong()).isEqualTo(1);
		return bought.get("commandSequence").asLong();
	}

	private static void beat(TestBrowser simulator, UUID run, boolean stopped) throws Exception {
		HttpResponse<String> beat = simulator.post("/internal/simulator/heartbeat", """
				{"runId":"%s","workerId":"windows-worker","placed":0,"canceled":0,"rejected":0,"errors":0,"inFlight":0,
				"stopFailures":0,"stopped":%s}""".formatted(run, stopped), "Authorization", "Bearer " + SIMULATOR_TOKEN);
		assertThat(beat.statusCode()).as(beat.body()).isEqualTo(200);
	}

	/** The run's start, stop and end sequences and how it ended, as the operator's list shows them. */
	private static List<String> window(TestBrowser operator, UUID run) throws Exception {
		HttpResponse<String> read = operator.get("/api/v1/admin/bot-runs?limit=200");
		assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
		CONTRACT.assertResponse("GET", "/api/v1/admin/bot-runs", 200,
				read.headers().firstValue("Content-Type").orElse(null), read.body());
		for (JsonNode listed : JSON.readTree(read.body()).get("runs")) {
			if (listed.get("runId").asString().equals(run.toString())) {
				List<String> window = new ArrayList<>();
				for (String field : new String[] { "startSequence", "stopSequence", "endSequence", "endedBy" }) {
					JsonNode value = listed.get("window").get(field);
					window.add(value.isNull() ? null : value.asString());
				}
				return window;
			}
		}
		throw new AssertionError("run " + run + " is not listed");
	}

	/** The run as the operator's list shows it. */
	private static JsonNode listed(TestBrowser operator, UUID run) throws Exception {
		HttpResponse<String> read = operator.get("/api/v1/admin/bot-runs?limit=200");
		assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
		for (JsonNode listed : JSON.readTree(read.body()).get("runs")) {
			if (listed.get("runId").asString().equals(run.toString())) {
				return listed;
			}
		}
		throw new AssertionError("run " + run + " is not listed");
	}

	/** The instrument's newest trade sequence at the run's start and end. */
	private List<Long> edges(UUID run, String instrument) {
		return jdbc.queryForObject("""
				SELECT start_trade_sequence, end_trade_sequence FROM bot_run_instrument
				WHERE run_id = ? AND instrument_id = ?""", (row, index) -> {
			List<Long> edges = new ArrayList<>();
			edges.add(row.getObject(1, Long.class));
			edges.add(row.getObject(2, Long.class));
			return edges;
		}, run, UUID.fromString(instrument));
	}

	private static String instrument(TestBrowser operator, String symbol) throws Exception {
		return post(operator, "/api/v1/admin/instruments", "{\"symbol\":\"%s\",\"referencePrice\":\"100.00\"}"
			.formatted(symbol)).get("instrumentId").asString();
	}

	private static String refusal(TestBrowser operator, String body) throws Exception {
		HttpResponse<String> refused = operator.post("/api/v1/admin/bot-runs", body, "Idempotency-Key",
				UUID.randomUUID().toString());
		assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
		return JSON.readTree(refused.body()).get("code").asString();
	}

	private static JsonNode post(TestBrowser browser, String path, String body) throws Exception {
		HttpResponse<String> sent = browser.post(path, body, "Idempotency-Key", UUID.randomUUID().toString());
		assertThat(sent.statusCode()).as(path + ": " + sent.body()).isBetween(200, 201);
		return JSON.readTree(sent.body());
	}

	/** A finished run as the migration leaves one recorded before windows were kept. */
	private UUID legacyRun() throws Exception {
		UUID run = UUID.randomUUID();
		asOwner("""
				INSERT INTO bot_run (id, simulation_id, desired_state, seed, commands_per_second, max_order_quantity,
				    max_position, started_by, started_at, start_sequence, stop_requested_at, stopped_at, ended_by)
				SELECT ?, s.id, 'STOPPED', 1, 1, 1, 1, u.id, now() - interval '1 hour', 1, now() - interval '1 hour',
				    now() - interval '1 hour', 'NOT_KEPT'
				FROM simulation s, app_user u WHERE u.username = 'operator'""", run);
		return run;
	}

	/** A change only the owner role may make, such as moving a run into the past. */
	private void asOwner(String sql, UUID run) throws Exception {
		String url = TestcontainersConfiguration.jdbcUrl(postgres, "execution_db");
		try (Connection owner = DriverManager.getConnection(url, "execution_owner",
				TestcontainersConfiguration.EXECUTION_OWNER_PASSWORD); PreparedStatement statement = owner.prepareStatement(sql)) {
			statement.setObject(1, run);
			assertThat(statement.executeUpdate()).isEqualTo(1);
		}
	}

}
