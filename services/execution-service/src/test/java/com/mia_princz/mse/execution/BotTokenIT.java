package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.mia_princz.mse.execution.identity.ApiTokens;
import com.mia_princz.mse.execution.trading.application.TradingEngine;

/**
 * Bots trade over HTTP with a bearer token instead of a session. The market is open with one instrument; the bot and
 * the dev trader each start with 10,000.00 and 100 shares. Tokens are stored here as the hash only, as provisioning
 * will store them.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=bot-token", "mse.dev-users.trader-password=" + BotTokenIT.PASSWORD,
				"mse.dev-users.operator-password=" + BotTokenIT.PASSWORD,
				// These tests are about credentials; one bot sends commands back to back. AccountRateLimitIT covers pacing.
				"mse.trading.bot-commands-per-second=1000" })
class BotTokenIT {

	static final String PASSWORD = "test-bot-token-password";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final ApiTokens.Issued TOKEN = ApiTokens.issue();

	private static final ApiTokens.Issued REVOKED = ApiTokens.issue();

	private static final String BEARER = "Bearer " + TOKEN.token();

	private static UUID instrument;

	private static UUID bot;

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	TradingEngine engine;

	@BeforeEach
	void market() {
		synchronized (BotTokenIT.class) {
			if (instrument == null) {
				new TransactionTemplate(transactionManager).executeWithoutResult((status) -> setUp());
				engine.start();
			}
		}
	}

	@Test
	void aBotTradesWithItsTokenAloneAndGetsNoSession() throws Exception {
		TestBrowser client = new TestBrowser(port);
		UUID key = UUID.randomUUID();
		HttpResponse<String> placed = client.post("/api/v1/orders", order("BUY", 2, "90.00"), "Authorization", BEARER,
				"Idempotency-Key", key.toString());

		assertThat(placed.statusCode()).as(placed.body()).isEqualTo(201);
		assertThat(placed.headers().allValues("Set-Cookie")).as("stateless").isEmpty();
		String orderId = JSON.readTree(placed.body()).get("orderId").asString();
		assertThat(jdbc.queryForObject("SELECT account_id FROM orders WHERE id = ?", UUID.class,
				UUID.fromString(orderId))).isEqualTo(bot);
		assertThat(jdbc.queryForObject("SELECT principal_id FROM command_result WHERE idempotency_key = ?", UUID.class,
				key)).as("a bot has no user; its account is the principal").isEqualTo(bot);

		// The same key answers the same, and the bot can look its commands, orders and balances up.
		assertThat(client.post("/api/v1/orders", order("BUY", 2, "90.00"), "Authorization", BEARER, "Idempotency-Key",
				key.toString()).body()).isEqualTo(placed.body());
		assertThat(client.get("/api/v1/commands/" + key, bearer()).body()).isEqualTo(placed.body());
		assertThat(client.get("/api/v1/orders/" + orderId, bearer()).statusCode()).isEqualTo(200);
		JsonNode portfolio = JSON.readTree(client.get("/api/v1/portfolio", bearer()).body());
		assertThat(portfolio.get("cash").get("reserved").asString()).isEqualTo("180.00");
		// The cost figures are worked out from every trade on each read, so a bot's read leaves them out.
		assertThat(portfolio.get("costBasis").isNull()).isTrue();
		portfolio.get("holdings").forEach((holding) -> {
			assertThat(holding.get("averageCost").isNull()).isTrue();
			assertThat(holding.get("unrealized").isNull()).isTrue();
			assertThat(holding.get("weight").isString()).isTrue();
		});
		// The trader was funded the same way; a person's read has the cost, so the bot's nulls come from being a bot.
		JsonNode person = JSON.readTree(TestBrowser.loggedIn(port, "trader", PASSWORD).get("/api/v1/portfolio").body());
		assertThat(person.get("holdings").get(0).get("averageCost").asString()).isEqualTo("100.00");
		assertThat(person.get("costBasis").asString()).isEqualTo("10000.00");

		HttpResponse<String> canceled = client.post("/api/v1/orders/" + orderId + "/cancel", "", "Authorization", BEARER,
				"Idempotency-Key", UUID.randomUUID().toString());
		assertThat(canceled.statusCode()).as(canceled.body()).isEqualTo(200);
		assertThat(client.cookies).isEmpty();
	}

	@Test
	void anUnknownMalformedOrRevokedTokenIsRefused() throws Exception {
		long commands = jdbc.queryForObject("SELECT count(*) FROM command_result", Long.class);
		for (String header : new String[] { "Bearer " + ApiTokens.issue().token(), "Bearer " + REVOKED.token(),
				"Bearer " + TOKEN.token().substring(1), "Bearer " + TOKEN.token() + "x" }) {
			HttpResponse<String> refused = new TestBrowser(port).post("/api/v1/orders", order("BUY", 1, "90.00"),
					"Authorization", header, "Idempotency-Key", UUID.randomUUID().toString());
			assertThat(refused.statusCode()).as(header).isEqualTo(401);
			assertThat(refused.headers().firstValue("WWW-Authenticate")).hasValue("Bearer error=\"invalid_token\"");
		}
		// "Bearer" alone (trailing spaces are not part of a header value) offers no credential, so the session chain
		// answers it: no session and no CSRF token.
		assertThat(new TestBrowser(port).post("/api/v1/orders", order("BUY", 1, "90.00"), "Authorization", "Bearer",
				"Idempotency-Key", UUID.randomUUID().toString()).statusCode()).isEqualTo(403);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM command_result", Long.class)).isEqualTo(commands);
	}

	@Test
	void aTokenOpensOnlyTheTradingRoutes() throws Exception {
		TestBrowser client = new TestBrowser(port);
		assertThat(client.get("/api/v1/market", bearer()).statusCode()).isEqualTo(200);
		assertThat(client.get("/api/v1/me", bearer()).statusCode()).isEqualTo(403);
		assertThat(client.get("/api/v1/me/tour", bearer()).statusCode()).isEqualTo(403);
		assertThat(client.put("/api/v1/me/tour", "{\"seenVersion\":1}", bearer()).statusCode()).isEqualTo(403);
		assertThat(client.get("/api/v1/admin/accounts", bearer()).statusCode()).isEqualTo(403);
		assertThat(client.post("/api/v1/admin/market/halt", "", "Authorization", BEARER, "Idempotency-Key",
				UUID.randomUUID().toString()).statusCode()).isEqualTo(403);
		assertThat(client.post("/api/v1/auth/logout", "", bearer()).statusCode()).isEqualTo(403);
		assertThat(jdbc.queryForObject("SELECT state FROM simulation", String.class)).isEqualTo("OPEN");
	}

	/** The bearer chain never reads the session, so a cookie can neither help a bad token nor skip CSRF. */
	@Test
	void aSessionCookieSentWithABearerTokenIsIgnored() throws Exception {
		TestBrowser trader = TestBrowser.loggedIn(port, "trader", PASSWORD);
		HttpResponse<String> traderOrder = trader.post("/api/v1/orders", order("SELL", 1, "150.00"),
				"Idempotency-Key", UUID.randomUUID().toString());
		assertThat(traderOrder.statusCode()).as(traderOrder.body()).isEqualTo(201);
		String traderOrderId = JSON.readTree(traderOrder.body()).get("orderId").asString();

		assertThat(trader.post("/api/v1/orders", order("BUY", 1, "90.00"), "Authorization",
				"Bearer " + ApiTokens.issue().token(), "Idempotency-Key", UUID.randomUUID().toString())
			.statusCode()).as("valid session and CSRF token, invalid bearer").isEqualTo(401);

		trader.csrfToken = null;
		HttpResponse<String> asBot = trader.post("/api/v1/orders", order("BUY", 1, "91.00"), "Authorization", BEARER,
				"Idempotency-Key", UUID.randomUUID().toString());
		assertThat(asBot.statusCode()).as(asBot.body()).isEqualTo(201);
		assertThat(jdbc.queryForObject("SELECT account_id FROM orders WHERE id = ?", UUID.class,
				UUID.fromString(JSON.readTree(asBot.body()).get("orderId").asString())))
			.as("the bot's account, not the session's")
			.isEqualTo(bot);

		// Each credential sees only its own orders.
		assertThat(trader.get("/api/v1/orders/" + traderOrderId, bearer()).statusCode()).isEqualTo(404);
		assertThat(trader.post("/api/v1/orders/" + traderOrderId + "/cancel", "", "Authorization", BEARER,
				"Idempotency-Key", UUID.randomUUID().toString()).statusCode()).isEqualTo(404);
		assertThat(jdbc.queryForObject("SELECT state FROM orders WHERE id = ?", String.class,
				UUID.fromString(traderOrderId))).isEqualTo("OPEN");
	}

	private static String[] bearer() {
		return new String[] { "Authorization", BEARER };
	}

	private static String order(String side, long quantity, String price) {
		return "{\"instrumentId\":\"" + instrument + "\",\"side\":\"" + side
				+ "\",\"type\":\"LIMIT\",\"timeInForce\":\"GTC\",\"quantity\":" + quantity + ",\"limitPrice\":\"" + price
				+ "\"}";
	}

	private void setUp() {
		UUID simulation = UUID.randomUUID();
		jdbc.update("INSERT INTO simulation (id, state, opened_at) VALUES (?, 'OPEN', now())", simulation);
		UUID issuance = UUID.randomUUID();
		jdbc.update("INSERT INTO account (id, simulation_id, kind, display_name) VALUES (?, ?, 'ISSUANCE', 'Issuance')",
				issuance, simulation);
		instrument = UUID.randomUUID();
		jdbc.update("INSERT INTO instrument (id, simulation_id, symbol, reference_price_cents) VALUES (?, ?, 'BOTS', 10000)",
				instrument, simulation);
		bot = UUID.randomUUID();
		jdbc.update("INSERT INTO account (id, simulation_id, kind, display_name) VALUES (?, ?, 'BOT', 'bot-0001')", bot,
				simulation);
		fund(simulation, issuance, bot, "BOT");
		UUID trader = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO account (id, simulation_id, kind, display_name, owner_user_id)
				SELECT ?, ?, 'TRADER', 'trader', id FROM app_user WHERE username = 'trader'""", trader, simulation);
		fund(simulation, issuance, trader, "TRADER");
		String token = """
				INSERT INTO api_token (id, account_id, account_kind, token_hash, scope, revoked_at)
				VALUES (?, ?, 'BOT', ?, 'TRADE', ?)""";
		jdbc.update(token, UUID.randomUUID(), bot, TOKEN.hash(), null);
		jdbc.update(token, UUID.randomUUID(), bot, REVOKED.hash(), Timestamp.from(Instant.now()));
	}

	/** 10,000.00 and 100 shares from one genesis batch. */
	private void fund(UUID simulation, UUID issuance, UUID account, String kind) {
		UUID batch = UUID.randomUUID();
		jdbc.update("INSERT INTO journal_batch (id, simulation_id, cause, command_sequence) VALUES (?, ?, 'GENESIS', 1)",
				batch, simulation);
		String post = "INSERT INTO ledger_entry (batch_id, account_id, instrument_id, bucket, delta) VALUES (?, ?, ?, 'AVAILABLE', ?)";
		jdbc.update(post, batch, issuance, null, -1_000_000);
		jdbc.update(post, batch, account, null, 1_000_000);
		jdbc.update(post, batch, issuance, instrument, -100);
		jdbc.update(post, batch, account, instrument, 100);
		jdbc.update("""
				INSERT INTO cash_balance (account_id, account_kind, total_cents, reserved_cents)
				VALUES (?, ?, 1000000, 0)""", account, kind);
		jdbc.update("""
				INSERT INTO position (account_id, account_kind, instrument_id, total_quantity, reserved_quantity)
				VALUES (?, ?, ?, 100, 0)""", account, kind, instrument);
		// As account setup records them, so a person's read of the same account would have a cost.
		String allocate = "INSERT INTO initial_allocation (account_id, instrument_id, amount, journal_batch_id) VALUES (?, ?, ?, ?)";
		jdbc.update(allocate, account, null, 1_000_000, batch);
		jdbc.update(allocate, account, instrument, 100, batch);
	}

}
