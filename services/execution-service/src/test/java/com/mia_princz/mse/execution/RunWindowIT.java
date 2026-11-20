package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
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
 * the run's closing commands, a run the next start ends before its worker confirms, and a run whose time runs out.
 * Two windows never overlap, and each instrument's trades in a window lie between the trade sequences recorded at its
 * edges. One test walks it all, on one market.
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

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PostgreSQLContainer postgres;

	@Test
	void aRunsWindowRunsFromItsStartToItsLastCommandAndNeverOverlapsTheNext() throws Exception {
		TestBrowser operator = TestBrowser.loggedIn(port, "operator", PASSWORD);
		TestBrowser simulator = new TestBrowser(port);
		String nova = post(operator, "/api/v1/admin/instruments", "{\"symbol\":\"NOVA\",\"referencePrice\":\"100.00\"}")
			.get("instrumentId")
			.asString();
		for (String strategy : new String[] { "MARKET_MAKER", "NOISE" }) {
			post(operator, "/api/v1/admin/bots", """
					{"count":1,"cash":"10000.00","holdings":[{"instrumentId":"%s","quantity":50}],"strategy":"%s"}"""
				.formatted(nova, strategy));
		}
		post(operator, "/api/v1/admin/traders", """
				{"username":"seller.windows","password":"%s","cash":"1000.00","holdings":[{"instrumentId":"%s",
				"quantity":100}]}""".formatted(PASSWORD, nova));
		post(operator, "/api/v1/admin/traders", """
				{"username":"buyer.windows","password":"%s","cash":"100000.00","holdings":[]}""".formatted(PASSWORD));
		post(operator, "/api/v1/admin/market/open", "");
		TestBrowser seller = TestBrowser.loggedIn(port, "seller.windows", PASSWORD);
		TestBrowser buyer = TestBrowser.loggedIn(port, "buyer.windows", PASSWORD);
		trade(seller, buyer, nova);

		// A run starts at its start command, with the instrument's newest trade as its lower edge.
		JsonNode started = post(operator, "/api/v1/admin/bot-runs", "{\"marketMakers\":1,\"noise\":1,\"commandsPerSecond\":\"1.00\"}");
		UUID first = UUID.fromString(started.get("runId").asString());
		long firstStart = started.get("commandSequence").asLong();
		assertThat(window(operator, first)).containsExactly(Long.toString(firstStart), null, null);
		assertThat(startTrade(first)).isEqualTo(1);
		trade(seller, buyer, nova);

		// The operator's stop is recorded; the run isn't finished until its worker confirms.
		JsonNode stopped = post(operator, "/api/v1/admin/bot-runs/" + first + "/stop", "");
		long firstStop = stopped.get("commandSequence").asLong();
		assertThat(window(operator, first)).containsExactly(Long.toString(firstStart), Long.toString(firstStop), null);
		// Commands after the stop, as the worker's closing cancels would be, still belong to the run.
		long tail = trade(seller, buyer, nova);
		confirm(simulator, first);
		assertThat(window(operator, first)).containsExactly(Long.toString(firstStart), Long.toString(firstStop),
				Long.toString(tail));
		assertThat(endTrade(first)).isEqualTo(3);

		// A run stopped, then replaced by the next before its worker confirms: the next start ends it.
		UUID second = UUID.fromString(post(operator, "/api/v1/admin/bot-runs", "{\"noise\":1,\"commandsPerSecond\":\"1.00\"}").get("runId").asString());
		assertThat(startTrade(second)).isEqualTo(3);
		trade(seller, buyer, nova);
		post(operator, "/api/v1/admin/bot-runs/" + second + "/stop", "");
		JsonNode replacing = post(operator, "/api/v1/admin/bot-runs", "{\"noise\":1,\"commandsPerSecond\":\"1.00\"}");
		UUID third = UUID.fromString(replacing.get("runId").asString());
		long thirdStart = replacing.get("commandSequence").asLong();
		assertThat(window(operator, second).get(2)).isEqualTo(Long.toString(thirdStart - 1));
		assertThat(endTrade(second)).isEqualTo(4);
		// Its worker confirming later moves neither end, though more has traded since.
		trade(seller, buyer, nova);
		confirm(simulator, second);
		assertThat(window(operator, second).get(2)).isEqualTo(Long.toString(thirdStart - 1));
		assertThat(endTrade(second)).isEqualTo(4);

		// A run whose time ran out ends when its worker confirms, with no operator stop.
		post(operator, "/api/v1/admin/bot-runs/" + third + "/stop", "");
		confirm(simulator, third);
		UUID timed = UUID.fromString(
				post(operator, "/api/v1/admin/bot-runs", "{\"noise\":1,\"durationMinutes\":1,\"commandsPerSecond\":\"1.00\"}").get("runId").asString());
		asOwner("UPDATE bot_run SET started_at = started_at - interval '2 minutes', "
				+ "ends_at = ends_at - interval '2 minutes' WHERE id = ?", timed);
		long last = trade(seller, buyer, nova);
		confirm(simulator, timed);
		assertThat(window(operator, timed).get(1)).as("no operator stop").isNull();
		assertThat(window(operator, timed).get(2)).isEqualTo(Long.toString(last));

		// No two windows overlap: each run ends before the next starts.
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM bot_run a JOIN bot_run b ON a.id <> b.id
				WHERE a.end_sequence IS NOT NULL AND b.start_sequence BETWEEN a.start_sequence AND a.end_sequence""",
				Long.class)).isZero();

		// The runtime role records ends, never rewrites where a run started, and can't end a run before its start.
		assertThatThrownBy(() -> jdbc.update("UPDATE bot_run SET start_sequence = 1 WHERE id = ?", first))
			.rootCause()
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
	private long trade(TestBrowser seller, TestBrowser buyer, String instrument) throws Exception {
		String order = """
				{"instrumentId":"%s","side":"%s","type":"LIMIT","timeInForce":"GTC","quantity":1,"limitPrice":"100.00"}""";
		post(seller, "/api/v1/orders", order.formatted(instrument, "SELL"));
		JsonNode bought = post(buyer, "/api/v1/orders", order.formatted(instrument, "BUY"));
		assertThat(bought.get("filledQuantity").asLong()).isEqualTo(1);
		return bought.get("commandSequence").asLong();
	}

	private static void confirm(TestBrowser simulator, UUID run) throws Exception {
		HttpResponse<String> beat = simulator.post("/internal/simulator/heartbeat", """
				{"runId":"%s","workerId":"windows-worker","placed":0,"canceled":0,"rejected":0,"errors":0,"inFlight":0,
				"stopFailures":0,"stopped":true}""".formatted(run), "Authorization", "Bearer " + SIMULATOR_TOKEN);
		assertThat(beat.statusCode()).as(beat.body()).isEqualTo(200);
	}

	/** The run's start, stop and end sequences as the operator's list shows them, checked against the contract. */
	private static java.util.List<String> window(TestBrowser operator, UUID run) throws Exception {
		HttpResponse<String> read = operator.get("/api/v1/admin/bot-runs?limit=200");
		assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
		CONTRACT.assertResponse("GET", "/api/v1/admin/bot-runs", 200,
				read.headers().firstValue("Content-Type").orElse(null), read.body());
		for (JsonNode listed : JSON.readTree(read.body()).get("runs")) {
			if (listed.get("runId").asString().equals(run.toString())) {
				JsonNode window = listed.get("window");
				java.util.List<String> sequences = new java.util.ArrayList<>();
				for (String field : new String[] { "startSequence", "stopSequence", "endSequence" }) {
					sequences.add(window.get(field).isNull() ? null : window.get(field).asString());
				}
				return sequences;
			}
		}
		throw new AssertionError("run " + run + " is not listed");
	}

	private long startTrade(UUID run) {
		return jdbc.queryForObject("SELECT start_trade_sequence FROM bot_run_instrument WHERE run_id = ?", Long.class,
				run);
	}

	private long endTrade(UUID run) {
		return jdbc.queryForObject("SELECT end_trade_sequence FROM bot_run_instrument WHERE run_id = ?", Long.class, run);
	}

	private static JsonNode post(TestBrowser browser, String path, String body) throws Exception {
		HttpResponse<String> sent = browser.post(path, body, "Idempotency-Key", UUID.randomUUID().toString());
		assertThat(sent.statusCode()).as(path + ": " + sent.body()).isBetween(200, 201);
		return JSON.readTree(sent.body());
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
