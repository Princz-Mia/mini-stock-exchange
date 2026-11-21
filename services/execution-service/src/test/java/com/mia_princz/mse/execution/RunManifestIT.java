package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a worker reports about itself with a run, over real HTTP and PostgreSQL. The run keeps the first manifest
 * it hears, past every refusal; the same one again adds nothing, a different one from a worker that takes the run
 * over is counted beside it, up to eight; a malformed one is refused with every problem named. A worker that sends
 * none still holds its run: the other heartbeat tests send none.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=run-manifest", "mse.dev-users.operator-password=" + RunManifestIT.PASSWORD,
				"mse.simulator.token=" + RunManifestIT.SIMULATOR_TOKEN, "mse.simulator.lease=20s" })
class RunManifestIT {

	static final String PASSWORD = "test-run-manifest-password";

	static final String SIMULATOR_TOKEN = "test-run-manifest-simulator-token";

	private static final OpenApiContract CONTRACT = OpenApiContract.get();

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String HEARTBEAT = "/internal/simulator/heartbeat";

	private static final String RUNS = "/api/v1/admin/bot-runs";

	private static final String MANIFEST = """
			{"version":"0.1.0+27a70d6c1e2f","runtime":{"python":"3.14.10","numpy":"2.5.4"},
			 "strategies":{"MARKET_MAKER":{"halfSpreadTicks":2,"skewTicks":1,"requoteTicks":2,"quoteLifetimeSeconds":30.0},
			  "NOISE":{"spreadTicks":5,"maxOpenOrders":5,"quoteLifetimeSeconds":10.0}},
			 "defaults":{"marketPollSeconds":1.0,"maxInFlight":32,"liveOnly":true}}""";

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void theRunKeepsTheFirstManifestAndCountsTheOthers() throws Exception {
		TestBrowser operator = TestBrowser.loggedIn(port, "operator", PASSWORD);
		TestBrowser simulator = new TestBrowser(port);
		UUID run = startRun(operator);
		assertThat(view(operator, run).get("manifest").isNull()).as("nothing reported yet").isTrue();

		// A worker holds the run without a manifest; another's, refused for the lease, stores nothing.
		assertThat(beat(simulator, run, "worker-a", null).statusCode()).isEqualTo(200);
		assertThat(code(beat(simulator, run, "worker-b", MANIFEST), 409)).isEqualTo("LEASE_HELD");
		assertThat(view(operator, run).get("manifest").isNull()).isTrue();

		// The holder's manifest is the run's.
		CONTRACT.assertSchema("SimulatorHeartbeatRequest", body(run, "worker-a", MANIFEST));
		assertThat(beat(simulator, run, "worker-a", MANIFEST).statusCode()).isEqualTo(200);
		JsonNode manifest = view(operator, run).get("manifest");
		assertThat(manifest.get("version").asString()).isEqualTo("0.1.0+27a70d6c1e2f");
		assertThat(manifest.get("workerId").asString()).isEqualTo("worker-a");
		assertThat(manifest.get("reportedAt").isString()).isTrue();
		assertThat(manifest.get("runtime").toString()).isEqualTo("{\"numpy\":\"2.5.4\",\"python\":\"3.14.10\"}");
		assertThat(manifest.get("strategies").get("MARKET_MAKER").get("quoteLifetimeSeconds").asDouble()).isEqualTo(30.0);
		assertThat(manifest.get("defaults").get("liveOnly").asBoolean()).isTrue();
		assertThat(manifest.get("reports").asInt()).isOne();

		// The same manifest again, its keys in another order, adds nothing.
		String reordered = """
				{"defaults":{"liveOnly":true,"maxInFlight":32,"marketPollSeconds":1.0},
				 "strategies":{"NOISE":{"quoteLifetimeSeconds":10.0,"maxOpenOrders":5,"spreadTicks":5},
				  "MARKET_MAKER":{"quoteLifetimeSeconds":30.0,"requoteTicks":2,"skewTicks":1,"halfSpreadTicks":2}},
				 "runtime":{"numpy":"2.5.4","python":"3.14.10"},"version":"0.1.0+27a70d6c1e2f"}""";
		assertThat(beat(simulator, run, "worker-a", reordered).statusCode()).isEqualTo(200);
		assertThat(view(operator, run).get("manifest").get("reports").asInt()).isOne();

		// Rebuilt: another worker takes over with other code. The first stays the run's; the change is counted.
		jdbc.update("UPDATE bot_run SET lease_expires_at = now() - interval '1 second' WHERE id = ?", run);
		assertThat(beat(simulator, run, "worker-b", MANIFEST.replace("27a70d6c1e2f", "9f00c0ffee12.dirty"))
			.statusCode()).isEqualTo(200);
		manifest = view(operator, run).get("manifest");
		assertThat(manifest.get("version").asString()).isEqualTo("0.1.0+27a70d6c1e2f");
		assertThat(manifest.get("workerId").asString()).isEqualTo("worker-a");
		assertThat(manifest.get("reports").asInt()).isEqualTo(2);

		// At most eight are kept; more are still answered, as the lease must be.
		for (int other = 0; other < 8; other++) {
			assertThat(beat(simulator, run, "worker-b", MANIFEST.replace("\"liveOnly\":true", "\"seen\":" + other))
				.statusCode()).isEqualTo(200);
		}
		assertThat(view(operator, run).get("manifest").get("reports").asInt()).isEqualTo(8);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM bot_run_manifest WHERE run_id = ?", Integer.class, run))
			.isEqualTo(8);
	}

	@Test
	void aMalformedManifestIsRefusedWithEveryProblemNamed() throws Exception {
		TestBrowser operator = TestBrowser.loggedIn(port, "operator", PASSWORD);
		TestBrowser simulator = new TestBrowser(port);
		UUID run = startRun(operator);

		assertThat(fields(beat(simulator, run, "worker-a", "\"0.1.0\""))).containsExactly("manifest INVALID_VALUE");
		assertThat(fields(beat(simulator, run, "worker-a", MANIFEST.replace("\"version\"", "\"extra\":1,\"version\""))))
			.containsExactly("manifest INVALID_VALUE");
		assertThat(fields(beat(simulator, run, "worker-a", """
				{"version":"0.1.0 dirty","runtime":{"python":3.14},
				 "strategies":{"ARBITRAGE":{"edge":1}},"defaults":{"nested":{"maxInFlight":32}}}""")))
			.containsExactly("manifest.version INVALID_VALUE", "manifest.runtime INVALID_VALUE",
					"manifest.strategies INVALID_VALUE", "manifest.defaults INVALID_VALUE");
		// Twenty-one settings are one too many.
		assertThat(fields(beat(simulator, run, "worker-a", MANIFEST.replace(DEFAULTS, settings(21)))))
			.containsExactly("manifest.defaults INVALID_VALUE");
		// Only plain values: no control character, nothing but printable ASCII, no number written out at length.
		assertThat(fields(beat(simulator, run, "worker-a", MANIFEST.replace("3.14.10", "3.14\\u0000"))))
			.containsExactly("manifest.runtime INVALID_VALUE");
		assertThat(fields(beat(simulator, run, "worker-a", MANIFEST.replace("3.14.10", "3.14.10é"))))
			.containsExactly("manifest.runtime INVALID_VALUE");
		assertThat(fields(beat(simulator, run, "worker-a", MANIFEST.replace("\"maxInFlight\":32", "\"maxInFlight\":1e300"))))
			.containsExactly("manifest.defaults INVALID_VALUE");
		assertThat(fields(beat(simulator, run, "worker-a",
				MANIFEST.replace("\"python\":\"3.14.10\"", "\"python\":\"" + "x".repeat(65) + "\""))))
			.containsExactly("manifest.runtime INVALID_VALUE");
		// Every part within its own limits, but too much together: twenty long names and texts in each of five.
		StringBuilder large = new StringBuilder();
		for (int setting = 10; setting < 30; setting++) {
			large.append(setting == 10 ? "" : ",").append("\"s").append("k".repeat(36)).append(setting).append("\":\"")
				.append("y".repeat(64)).append('"');
		}
		String strategies = "{\"MARKET_MAKER\":{%1$s},\"NOISE\":{%1$s},\"MOMENTUM\":{%1$s}}".formatted(large);
		assertThat(fields(beat(simulator, run, "worker-a", """
				{"version":"0.1.0+27a70d6c1e2f","runtime":{%s},"strategies":%s,"defaults":{%s}}"""
			.formatted(large, strategies, large)))).containsExactly("manifest TOO_LARGE");
		// Within 8 KiB as sent, but not as stored, where PostgreSQL writes a space after each colon and comma.
		StringBuilder near = new StringBuilder();
		for (int setting = 10; setting < 30; setting++) {
			near.append(setting == 10 ? "" : ",").append("\"n").append("k".repeat(27)).append(setting).append("\":\"")
				.append("v".repeat(44)).append('"');
		}
		String edge = """
				{"version":"0.1.0+27a70d6c1e2f","runtime":{%1$s},
				 "strategies":{"MARKET_MAKER":{%1$s},"NOISE":{%1$s},"MOMENTUM":{%1$s}},"defaults":{%1$s}}"""
			.formatted(near);
		assertThat(JSON.readTree(edge).toString().length()).as("as sent").isLessThanOrEqualTo(8_192);
		assertThat(jdbc.queryForObject("SELECT octet_length(CAST(? AS jsonb)::text)", Integer.class,
				JSON.readTree(edge).toString())).as("as stored").isGreaterThan(8_192);
		assertThat(fields(beat(simulator, run, "worker-a", edge))).containsExactly("manifest TOO_LARGE");
		// Nothing refused was kept, and the run was never taken.
		assertThat(view(operator, run).get("manifest").isNull()).isTrue();
		assertThat(view(operator, run).get("worker").get("workerId").isNull()).isTrue();
		// Twenty settings are allowed.
		assertThat(beat(simulator, run, "worker-a", MANIFEST.replace(DEFAULTS, settings(20))).statusCode()).isEqualTo(200);
		assertThat(view(operator, run).get("manifest").get("defaults").size()).isEqualTo(20);
	}

	private static final String DEFAULTS = "{\"marketPollSeconds\":1.0,\"maxInFlight\":32,\"liveOnly\":true}";

	/** A defaults object of this many settings. */
	private static String settings(int count) {
		StringBuilder settings = new StringBuilder("{");
		for (int setting = 0; setting < count; setting++) {
			settings.append(setting == 0 ? "" : ",").append("\"setting").append(setting).append("\":").append(setting);
		}
		return settings.append('}').toString();
	}

	private UUID startRun(TestBrowser operator) throws Exception {
		String nova = jdbc.query("SELECT id FROM instrument WHERE symbol = 'NOVA'", (row) -> row.next()
				? row.getString(1) : null);
		if (nova == null) {
			HttpResponse<String> added = operator.post("/api/v1/admin/instruments",
					"{\"symbol\":\"NOVA\",\"referencePrice\":\"100.00\"}", "Idempotency-Key", UUID.randomUUID().toString());
			assertThat(added.statusCode()).as(added.body()).isEqualTo(201);
			nova = JSON.readTree(added.body()).get("instrumentId").asString();
			HttpResponse<String> bots = operator.post("/api/v1/admin/bots", """
					{"count":1,"cash":"10000.00","holdings":[{"instrumentId":"%s","quantity":50}],"strategy":"MARKET_MAKER"}"""
				.formatted(nova), "Idempotency-Key", UUID.randomUUID().toString());
			assertThat(bots.statusCode()).as(bots.body()).isEqualTo(201);
			assertThat(operator.post("/api/v1/admin/market/open", "", "Idempotency-Key", UUID.randomUUID().toString())
				.statusCode()).isEqualTo(200);
		}
		// One run at a time: an earlier one is stopped, and its worker gone, so the next start ends it.
		for (UUID earlier : jdbc.queryForList("SELECT id FROM bot_run WHERE stopped_at IS NULL", UUID.class)) {
			operator.post(RUNS + "/" + earlier + "/stop", "", "Idempotency-Key", UUID.randomUUID().toString());
			jdbc.update("UPDATE bot_run SET lease_expires_at = now() - interval '1 second' WHERE id = ?", earlier);
		}
		HttpResponse<String> started = operator.post(RUNS, "{\"marketMakers\":1,\"commandsPerSecond\":\"1.00\"}",
				"Idempotency-Key", UUID.randomUUID().toString());
		assertThat(started.statusCode()).as(started.body()).isEqualTo(201);
		return UUID.fromString(JSON.readTree(started.body()).get("runId").asString());
	}

	private static String body(UUID run, String worker, String manifest) {
		return """
				{"runId":"%s","workerId":"%s","placed":0,"canceled":0,"rejected":0,"errors":0,"inFlight":0,
				"stopFailures":0,"stopped":false%s}""".formatted(run, worker,
				manifest == null ? "" : ",\"manifest\":" + manifest);
	}

	private static HttpResponse<String> beat(TestBrowser simulator, UUID run, String worker, String manifest)
			throws Exception {
		HttpResponse<String> answer = simulator.post(HEARTBEAT, body(run, worker, manifest), "Authorization",
				"Bearer " + SIMULATOR_TOKEN);
		CONTRACT.assertResponse("POST", HEARTBEAT, answer.statusCode(),
				answer.headers().firstValue("Content-Type").orElse(null), answer.body());
		return answer;
	}

	private static JsonNode view(TestBrowser operator, UUID run) throws Exception {
		HttpResponse<String> read = operator.get(RUNS);
		assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
		CONTRACT.assertResponse("GET", RUNS, 200, read.headers().firstValue("Content-Type").orElse(null), read.body());
		for (JsonNode listed : JSON.readTree(read.body()).get("runs")) {
			if (listed.get("runId").asString().equals(run.toString())) {
				return listed;
			}
		}
		throw new AssertionError("run " + run + " is not listed");
	}

	private static String code(HttpResponse<String> problem, int status) {
		assertThat(problem.statusCode()).as(problem.body()).isEqualTo(status);
		return JSON.readTree(problem.body()).get("code").asString();
	}

	private static List<String> fields(HttpResponse<String> problem) {
		assertThat(code(problem, 400)).isEqualTo("VALIDATION_FAILED");
		List<String> fields = new ArrayList<>();
		JSON.readTree(problem.body())
			.get("errors")
			.forEach((error) -> fields.add(error.get("field").asString() + " " + error.get("code").asString()));
		return fields;
	}

}
