package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.mia_princz.mse.execution.identity.ApiTokens;

/**
 * Provisioning bots over real HTTP and PostgreSQL, from an empty market to a bot trading with the token it was given.
 * One test walks the whole sequence, because the market it sets up is shared.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=bot-provisioning",
				"mse.dev-users.trader-password=" + BotProvisioningIT.PASSWORD,
				"mse.dev-users.operator-password=" + BotProvisioningIT.PASSWORD })
class BotProvisioningIT {

	static final String PASSWORD = "test-bot-provisioning-password";

	private static final OpenApiContract CONTRACT = OpenApiContract.get();

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String BOTS = "/api/v1/admin/bots";

	private static final Path TOKENS;

	static {
		try {
			TOKENS = Files.createTempDirectory("bot-tokens-").resolve("secrets");
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
	void botsAreFundedInSetupAndTradeWithTheTokenTheyWereGiven() throws Exception {
		TestBrowser operator = TestBrowser.loggedIn(port, "operator", PASSWORD);
		assertThat(population(operator)).isEqualTo("0 of 1000, 100 at a time");
		String nova = instrument(operator, "NOVA", "100.00");

		// Three bots, each funded from issuance in its own genesis batch, each with a token file and only its hash.
		UUID firstKey = UUID.randomUUID();
		HttpResponse<String> created = bots(operator, firstKey, 3, "50000.00", holding(nova, 200));
		assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
		CONTRACT.assertResponse("POST", BOTS, 201, created.headers().firstValue("Content-Type").orElse(null),
				created.body());
		JsonNode body = JSON.readTree(created.body());
		assertThat(body.get("created").asInt()).isEqualTo(3);
		assertThat(body.get("firstBot").asString()).isEqualTo("bot-0001");
		assertThat(body.get("lastBot").asString()).isEqualTo("bot-0003");
		assertThat(body.get("population").asInt()).isEqualTo(3);

		List<Map<String, Object>> accounts = jdbc.queryForList(
				"SELECT id, display_name FROM account WHERE kind = 'BOT' ORDER BY display_name");
		assertThat(accounts).extracting((row) -> row.get("display_name"))
			.containsExactly("bot-0001", "bot-0002", "bot-0003");
		assertThat(tokenFiles()).hasSize(3);
		for (Map<String, Object> account : accounts) {
			UUID id = (UUID) account.get("id");
			String token = Files.readString(TOKENS.resolve(id + ".token"), StandardCharsets.US_ASCII).strip();
			assertThat(ApiTokens.wellFormed(token)).isTrue();
			assertThat(jdbc.queryForObject("SELECT token_hash FROM api_token WHERE account_id = ? AND revoked_at IS NULL",
					byte[].class, id))
				.isEqualTo(ApiTokens.hash(token));
			assertThat(jdbc.queryForList("""
					SELECT coalesce(i.symbol, 'CASH') || ' ' || a.amount FROM initial_allocation a
					LEFT JOIN instrument i ON i.id = a.instrument_id WHERE a.account_id = ? ORDER BY 1""",
					String.class, id))
				.containsExactly("CASH 5000000", "NOVA 200");
			assertThat(jdbc.queryForObject("SELECT account_kind FROM cash_balance WHERE account_id = ?", String.class,
					id)).isEqualTo("BOT");
		}
		assertThat(jdbc.queryForObject("""
				SELECT count(DISTINCT b.id) FROM journal_batch b JOIN ledger_entry e ON e.batch_id = b.id
				WHERE b.cause = 'GENESIS' AND b.command_sequence = ?""", Long.class,
				Long.parseLong(body.get("commandSequence").asString()))).isEqualTo(3);
		// No token appears in the answer or in the stored result.
		assertThat(created.body()).doesNotContain(ApiTokens.PREFIX);
		assertThat(jdbc.queryForObject("SELECT response_body::text FROM command_result WHERE idempotency_key = ?",
				String.class, firstKey)).doesNotContain(ApiTokens.PREFIX);

		// The same key repeats the answer without new bots or tokens; a different request with it is a conflict.
		assertThat(bots(operator, firstKey, 3, "50000.00", holding(nova, 200)).body()).isEqualTo(created.body());
		assertThat(code(bots(operator, firstKey, 4, "50000.00", holding(nova, 200)), 409))
			.isEqualTo("IDEMPOTENCY_CONFLICT");
		assertThat(tokenFiles()).hasSize(3);

		// Numbering continues; an unknown instrument is a committed refusal that creates nothing.
		HttpResponse<String> more = bots(operator, UUID.randomUUID(), 2, "1000.00");
		assertThat(JSON.readTree(more.body()).get("lastBot").asString()).as(more.body()).isEqualTo("bot-0005");
		assertThat(jdbc.queryForList("SELECT display_name FROM account WHERE kind = 'BOT' ORDER BY display_name",
				String.class)).containsExactly("bot-0001", "bot-0002", "bot-0003", "bot-0004", "bot-0005");
		UUID unknownKey = UUID.randomUUID();
		assertThat(code(bots(operator, unknownKey, 1, "1.00", holding(UUID.randomUUID().toString(), 1)), 422))
			.isEqualTo("UNKNOWN_INSTRUMENT");
		assertThat(botCount()).isEqualTo(5);
		assertThat(population(operator)).isEqualTo("5 of 1000, 100 at a time");
		List<String> listed = new ArrayList<>();
		JSON.readTree(operator.get("/api/v1/admin/accounts").body())
			.get("accounts")
			.forEach((account) -> listed.add(account.get("username").asString()));
		assertThat(listed).as("the order they were made").containsExactly("bot-0001", "bot-0002", "bot-0003", "bot-0004",
				"bot-0005");

		// Invalid requests are refused before the writer, with every problem listed.
		assertThat(fields(bots(operator, UUID.randomUUID(), 0, "-1.00"))).containsExactlyInAnyOrder("count", "cash");
		assertThat(fields(bots(operator, UUID.randomUUID(), 101, "1.00"))).containsExactly("count");
		assertThat(fields(bots(operator, UUID.randomUUID(), 1, "0.00"))).containsExactly("cash");
		TestBrowser trader = TestBrowser.loggedIn(port, "trader", PASSWORD);
		assertThat(bots(trader, UUID.randomUUID(), 1, "1.00").statusCode()).isEqualTo(403);
		assertThat(trader.get(BOTS).statusCode()).isEqualTo(403);

		// If a token can't be handed over, nothing commits, and the same key succeeds once it can.
		Path moved = TOKENS.resolveSibling("moved");
		Files.move(TOKENS, moved);
		Files.writeString(TOKENS, "not a directory");
		UUID failingKey = UUID.randomUUID();
		assertThat(code(bots(operator, failingKey, 1, "1.00"), 500)).isEqualTo("COMMAND_FAILED");
		assertThat(botCount()).isEqualTo(5);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM command_result WHERE idempotency_key = ?", Long.class,
				failingKey)).isZero();
		Files.delete(TOKENS);
		Files.move(moved, TOKENS);
		assertThat(bots(operator, failingKey, 1, "1.00").statusCode()).isEqualTo(201);
		assertThat(tokenFiles()).hasSize(6);

		// The population is capped. Plain rows stand in for most of it.
		jdbc.update("""
				INSERT INTO account (id, simulation_id, kind, display_name)
				SELECT gen_random_uuid(), id, 'BOT', 'filler' FROM simulation, generate_series(1, 993)""");
		UUID limitKey = UUID.randomUUID();
		assertThat(code(bots(operator, limitKey, 2, "1.00"), 409)).isEqualTo("BOT_POPULATION_LIMIT");
		assertThat(JSON.readTree(bots(operator, UUID.randomUUID(), 1, "1.00").body()).get("lastBot").asString())
			.isEqualTo("bot-1000");

		// Once open, a bot trades with the token from its file, and no more bots are made.
		UUID openKey = UUID.randomUUID();
		assertThat(operator.post("/api/v1/admin/market/open", "", "Idempotency-Key", openKey.toString()).statusCode())
			.isEqualTo(200);
		UUID lateKey = UUID.randomUUID();
		assertThat(code(bots(operator, lateKey, 1, "1.00"), 409)).isEqualTo("MARKET_NOT_IN_SETUP");
		UUID bot = (UUID) accounts.get(0).get("id");
		String token = Files.readString(TOKENS.resolve(bot + ".token"), StandardCharsets.US_ASCII).strip();
		HttpResponse<String> placed = new TestBrowser(port).post("/api/v1/orders", """
				{"instrumentId":"%s","side":"SELL","type":"LIMIT","timeInForce":"GTC","quantity":5,"limitPrice":"101.00"}"""
			.formatted(nova), "Authorization", "Bearer " + token, "Idempotency-Key", UUID.randomUUID().toString());
		assertThat(placed.statusCode()).as(placed.body()).isEqualTo(201);

		List<String> audit = jdbc.queryForList("""
				SELECT outcome || ' ' || correlation_id FROM audit_log
				WHERE action = 'BOT_PROVISION' AND correlation_id IN (?, ?, ?, ?) ORDER BY command_sequence""",
				String.class, firstKey, unknownKey, limitKey, lateKey);
		assertThat(audit).containsExactly("APPLIED " + firstKey, "NOT_ALLOWED " + unknownKey,
				"NOT_ALLOWED " + limitKey, "NOT_ALLOWED " + lateKey);
	}

	private List<Path> tokenFiles() throws IOException {
		try (Stream<Path> files = Files.list(TOKENS)) {
			List<Path> listed = new ArrayList<>(files.toList());
			assertThat(listed).allSatisfy((file) -> assertThat(file.getFileName().toString()).endsWith(".token"));
			return listed;
		}
	}

	private static String population(TestBrowser operator) throws Exception {
		HttpResponse<String> read = operator.get(BOTS);
		CONTRACT.assertResponse("GET", BOTS, 200, read.headers().firstValue("Content-Type").orElse(null), read.body());
		JsonNode body = JSON.readTree(read.body());
		return body.get("population").asInt() + " of " + body.get("maxPopulation").asInt() + ", "
				+ body.get("maxPerCommand").asInt() + " at a time";
	}

	private long botCount() {
		return jdbc.queryForObject("SELECT count(*) FROM account WHERE kind = 'BOT'", Long.class);
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

	private static HttpResponse<String> bots(TestBrowser browser, UUID key, int count, String cash,
			String... holdings) throws Exception {
		String body = "{\"count\":%d,\"cash\":\"%s\",\"holdings\":[%s]}".formatted(count, cash,
				String.join(",", holdings));
		return browser.post(BOTS, body, "Idempotency-Key", key.toString());
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
