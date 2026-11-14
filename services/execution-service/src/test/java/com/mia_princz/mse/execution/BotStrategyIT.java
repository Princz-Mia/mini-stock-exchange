package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bots tagged with their strategy, each batch with an endowment of its own, over real HTTP and PostgreSQL. One test
 * walks the whole sequence, because the market it sets up is shared.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=bot-strategy", "mse.dev-users.trader-password=" + BotStrategyIT.PASSWORD,
				"mse.dev-users.operator-password=" + BotStrategyIT.PASSWORD })
class BotStrategyIT {

	static final String PASSWORD = "test-bot-strategy-password";

	private static final OpenApiContract CONTRACT = OpenApiContract.get();

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String BOTS = "/api/v1/admin/bots";

	private static final Path TOKENS;

	static {
		try {
			TOKENS = Files.createTempDirectory("bot-strategy-tokens-").resolve("secrets");
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
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
	void eachStrategyGetsItsOwnTagAndEndowment() throws Exception {
		TestBrowser operator = TestBrowser.loggedIn(port, "operator", PASSWORD);
		String nova = instrument(operator, "NOVA", "100.00");
		String orbt = instrument(operator, "ORBT", "50.00");

		// Three batches, each with its own strategy, cash and shares.
		UUID makersKey = UUID.randomUUID();
		HttpResponse<String> makers = bots(operator, makersKey, 2, "100000.00", "MARKET_MAKER", holding(nova, 500),
				holding(orbt, 500));
		assertThat(makers.statusCode()).as(makers.body()).isEqualTo(201);
		CONTRACT.assertResponse("POST", BOTS, 201, makers.headers().firstValue("Content-Type").orElse(null),
				makers.body());
		assertThat(JSON.readTree(makers.body()).get("strategy").asString()).isEqualTo("MARKET_MAKER");
		HttpResponse<String> noise = bots(operator, UUID.randomUUID(), 3, "20000.00", "NOISE", holding(nova, 50));
		assertThat(noise.statusCode()).as(noise.body()).isEqualTo(201);
		HttpResponse<String> momentum = bots(operator, UUID.randomUUID(), 1, "50000.00", "MOMENTUM", holding(orbt, 100));
		assertThat(momentum.statusCode()).as(momentum.body()).isEqualTo(201);
		// Without a strategy, as before tags existed: untagged, and the answer says so.
		HttpResponse<String> plain = bots(operator, UUID.randomUUID(), 1, "1000.00", null);
		assertThat(plain.statusCode()).as(plain.body()).isEqualTo(201);
		assertThat(JSON.readTree(plain.body()).get("strategy").isNull()).isTrue();

		// Each bot carries its batch's tag, and its own genesis batch with its batch's endowment.
		Map<String, String> tags = new LinkedHashMap<>();
		jdbc.query("SELECT display_name, bot_strategy FROM account WHERE kind = 'BOT' ORDER BY created_at, id",
				(row) -> {
					tags.put(row.getString("display_name"), row.getString("bot_strategy"));
				});
		Map<String, String> expected = new LinkedHashMap<>();
		expected.put("bot-0001", "MARKET_MAKER");
		expected.put("bot-0002", "MARKET_MAKER");
		expected.put("bot-0003", "NOISE");
		expected.put("bot-0004", "NOISE");
		expected.put("bot-0005", "NOISE");
		expected.put("bot-0006", "MOMENTUM");
		expected.put("bot-0007", null);
		assertThat(tags).containsExactlyEntriesOf(expected);
		assertThat(endowment("bot-0001")).containsExactly("CASH 10000000", "NOVA 500", "ORBT 500");
		assertThat(endowment("bot-0004")).containsExactly("CASH 2000000", "NOVA 50");
		assertThat(endowment("bot-0006")).containsExactly("CASH 5000000", "ORBT 100");
		assertThat(endowment("bot-0007")).containsExactly("CASH 100000");
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM journal_batch WHERE cause = 'GENESIS'
				AND command_sequence IN (SELECT command_sequence FROM command_result WHERE route = 'POST /api/v1/admin/bots')""",
				Long.class)).as("one genesis batch per bot").isEqualTo(7);
		// Every batch balances, cash and each instrument on its own: what the bots got, issuance gave.
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM (
				    SELECT batch_id, instrument_id FROM ledger_entry GROUP BY batch_id, instrument_id
				    HAVING sum(delta) <> 0) unbalanced""", Long.class)).isZero();

		// The population says how many carry each tag, and the account list shows each bot's.
		HttpResponse<String> population = operator.get(BOTS);
		CONTRACT.assertResponse("GET", BOTS, 200, population.headers().firstValue("Content-Type").orElse(null),
				population.body());
		JsonNode byStrategy = JSON.readTree(population.body()).get("byStrategy");
		assertThat(JSON.readTree(population.body()).get("population").asInt()).isEqualTo(7);
		assertThat(byStrategy.get("marketMakers").asInt()).isEqualTo(2);
		assertThat(byStrategy.get("noise").asInt()).isEqualTo(3);
		assertThat(byStrategy.get("momentum").asInt()).isEqualTo(1);
		assertThat(byStrategy.get("untagged").asInt()).isEqualTo(1);
		HttpResponse<String> accounts = operator.get("/api/v1/admin/accounts");
		CONTRACT.assertResponse("GET", "/api/v1/admin/accounts", 200,
				accounts.headers().firstValue("Content-Type").orElse(null), accounts.body());
		Map<String, String> listed = new LinkedHashMap<>();
		JSON.readTree(accounts.body())
			.get("accounts")
			.forEach((account) -> listed.put(account.get("username").asString(),
					account.get("strategy").isNull() ? null : account.get("strategy").asString()));
		assertThat(listed).containsExactlyEntriesOf(expected);

		// The strategy is part of the request: the same key with another, or none, is a conflict.
		assertThat(bots(operator, makersKey, 2, "100000.00", "MARKET_MAKER", holding(nova, 500), holding(orbt, 500))
			.body()).isEqualTo(makers.body());
		assertThat(code(bots(operator, makersKey, 2, "100000.00", "NOISE", holding(nova, 500), holding(orbt, 500)), 409))
			.isEqualTo("IDEMPOTENCY_CONFLICT");
		assertThat(code(bots(operator, makersKey, 2, "100000.00", null, holding(nova, 500), holding(orbt, 500)), 409))
			.isEqualTo("IDEMPOTENCY_CONFLICT");

		// An unknown strategy is refused before the writer, next to any other problem.
		HttpResponse<String> invalid = bots(operator, UUID.randomUUID(), 0, "1.00", "SCALPER");
		assertThat(code(invalid, 400)).isEqualTo("VALIDATION_FAILED");
		List<String> fields = new ArrayList<>();
		JSON.readTree(invalid.body())
			.get("errors")
			.forEach((error) -> fields.add(error.get("field").asString() + " " + error.get("code").asString()));
		assertThat(fields).containsExactlyInAnyOrder("count OUT_OF_RANGE", "strategy INVALID_STRATEGY");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM account WHERE kind = 'BOT'", Long.class)).isEqualTo(7);
	}

	private List<String> endowment(String bot) {
		return jdbc.queryForList("""
				SELECT coalesce(i.symbol, 'CASH') || ' ' || a.amount FROM initial_allocation a
				JOIN account b ON b.id = a.account_id
				LEFT JOIN instrument i ON i.id = a.instrument_id WHERE b.display_name = ? ORDER BY 1""", String.class,
				bot);
	}

	private static String instrument(TestBrowser operator, String symbol, String price) throws Exception {
		HttpResponse<String> added = operator.post("/api/v1/admin/instruments",
				"{\"symbol\":\"%s\",\"referencePrice\":\"%s\"}".formatted(symbol, price), "Idempotency-Key",
				UUID.randomUUID().toString());
		assertThat(added.statusCode()).as(added.body()).isEqualTo(201);
		return JSON.readTree(added.body()).get("instrumentId").asString();
	}

	private static String holding(String instrumentId, long quantity) {
		return "{\"instrumentId\":\"%s\",\"quantity\":%d}".formatted(instrumentId, quantity);
	}

	private static HttpResponse<String> bots(TestBrowser browser, UUID key, int count, String cash, String strategy,
			String... holdings) throws Exception {
		String tag = strategy == null ? "" : ",\"strategy\":\"%s\"".formatted(strategy);
		String body = "{\"count\":%d,\"cash\":\"%s\",\"holdings\":[%s]%s}".formatted(count, cash,
				String.join(",", holdings), tag);
		return browser.post(BOTS, body, "Idempotency-Key", key.toString());
	}

	private static String code(HttpResponse<String> problem, int status) {
		assertThat(problem.statusCode()).as(problem.body()).isEqualTo(status);
		return JSON.readTree(problem.body()).get("code").asString();
	}

}
