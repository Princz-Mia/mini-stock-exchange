package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
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
 * The internal trade export over HTTP. Trades come from real orders: the dev trader sells, a second trader buys one
 * order at a time, so each buy is one trade with its own size.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=trade-export", "mse.exports.token=" + TradeExportIT.TOKEN,
				"mse.dev-users.trader-password=" + TradeExportIT.PASSWORD,
				"mse.dev-users.operator-password=" + TradeExportIT.PASSWORD })
class TradeExportIT {

	static final String TOKEN = "test-export-token";

	static final String PASSWORD = "test-trade-export-password";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final OpenApiContract CONTRACT = OpenApiContract.get();

	private static final String EXPORT = "/internal/exports/trades";

	private static UUID instrument;

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	TradingEngine engine;

	private TestBrowser seller;

	private TestBrowser buyer;

	@BeforeEach
	void market() throws Exception {
		synchronized (TradeExportIT.class) {
			if (instrument == null) {
				new TransactionTemplate(transactionManager).executeWithoutResult((status) -> setUp());
				engine.start();
			}
		}
		seller = TestBrowser.loggedIn(port, "trader", PASSWORD);
		buyer = TestBrowser.loggedIn(port, "buyer", PASSWORD);
	}

	@Test
	void pagesInSequenceOrderThroughTheSequenceCapturedFirst() throws Exception {
		int before = tradeCount();
		trade(7, "100.00");
		int first = before + 1;

		JsonNode page1 = json(export("&limit=3"), 200);
		String through = page1.get("throughSequence").asString();
		assertThat(Long.parseLong(through)).isEqualTo(before + 7L);
		assertThat(page1.get("symbol").asString()).isEqualTo("XPRT");

		// A trade after the first page is not part of this export.
		trade(1, "100.00");
		List<Long> sequences = new ArrayList<>();
		List<Long> sizes = new ArrayList<>();
		JsonNode page = page1;
		while (true) {
			page.get("trades").forEach((row) -> {
				sequences.add(Long.parseLong(row.get("tradeSequence").asString()));
				sizes.add(row.get("quantity").asLong());
			});
			if (page.get("nextCursor").isNull()) {
				break;
			}
			page = json(export("&limit=3&throughSequence=" + through + "&cursor=" + page.get("nextCursor").asString()),
					200);
		}
		assertThat(sequences).hasSize(before + 7).startsWith(1L).endsWith((long) before + 7).isSorted();
		assertThat(sizes.subList(first - 1, sizes.size())).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L);
		assertThat(Long.parseLong(json(export(""), 200).get("throughSequence").asString()))
			.as("a new export captures the newer trade")
			.isEqualTo(before + 8L);
	}

	@Test
	void rowsCarryThePublicFactsAndNothingPrivate() throws Exception {
		trade(1, "101.50");

		HttpResponse<String> response = export("");
		CONTRACT.assertResponse("GET", EXPORT, 200, response.headers().firstValue("Content-Type").orElse(null),
				response.body());
		JsonNode last = null;
		for (JsonNode row : json(response, 200).get("trades")) {
			last = row;
		}
		assertThat(last.get("priceCents").asLong()).isEqualTo(10_150);
		List<String> fields = new ArrayList<>(last.propertyNames());
		assertThat(fields).containsExactlyInAnyOrder("tradeId", "tradeSequence", "priceCents", "quantity",
				"executedAt", "commandSequence");
		assertThat(response.body()).doesNotContain("account", "order", "buyer", "seller", "owner");
	}

	/** Only the service token opens it: no session, bot token or anything else, and nothing else under /internal. */
	@Test
	void onlyTheServiceTokenOpensIt() throws Exception {
		String query = "?instrumentId=" + instrument;
		assertThat(status(EXPORT + query, null)).isEqualTo(401);
		assertThat(status(EXPORT + query, "Bearer wrong-" + TOKEN)).isEqualTo(401);
		assertThat(status(EXPORT + query, "Bearer " + ApiTokens.issue().token())).as("a bot token").isEqualTo(401);
		assertThat(seller.get(EXPORT + query).statusCode()).as("a trader's session").isEqualTo(401);
		assertThat(status("/internal/anything-else", "Bearer " + TOKEN)).isEqualTo(403);
		assertThat(status("/api/v1/orders", "Bearer " + TOKEN)).as("the service token is not a bot token")
			.isEqualTo(401);
		HttpResponse<String> refused = send(EXPORT + query, null);
		assertThat(refused.headers().firstValue("Set-Cookie")).as("stateless").isEmpty();
		assertThat(refused.headers().firstValue("WWW-Authenticate")).hasValue("Bearer error=\"invalid_token\"");
	}

	@Test
	void badRequestsAreRefused() throws Exception {
		assertThat(field(export("&limit=0"))).isEqualTo("limit");
		assertThat(field(export("&limit=1001"))).isEqualTo("limit");
		assertThat(field(export("&cursor=nope"))).isEqualTo("cursor");
		assertThat(field(export("&throughSequence=-1"))).isEqualTo("throughSequence");
		HttpResponse<String> unknown = send(EXPORT + "?instrumentId=" + UUID.randomUUID(), "Bearer " + TOKEN);
		assertThat(json(unknown, 404).get("code").asString()).isEqualTo("INSTRUMENT_NOT_FOUND");
		assertThat(send(EXPORT, "Bearer " + TOKEN).statusCode()).as("no instrument").isEqualTo(400);
	}

	/** The dev trader sells {@code count} shares at once; the buyer takes them one order at a time, 1, 2, … shares. */
	private void trade(int count, String price) throws Exception {
		long total = (long) count * (count + 1) / 2;
		assertThat(seller.post("/api/v1/orders", order("SELL", total, price), "Idempotency-Key",
				UUID.randomUUID().toString()).statusCode()).isEqualTo(201);
		for (int size = 1; size <= count; size++) {
			HttpResponse<String> bought = buyer.post("/api/v1/orders", order("BUY", size, price), "Idempotency-Key",
					UUID.randomUUID().toString());
			assertThat(JSON.readTree(bought.body()).get("state").asString()).as(bought.body()).isEqualTo("FILLED");
		}
	}

	private int tradeCount() {
		return jdbc.queryForObject("SELECT count(*) FROM trades WHERE instrument_id = ?", Integer.class, instrument);
	}

	private HttpResponse<String> export(String extra) throws Exception {
		return send(EXPORT + "?instrumentId=" + instrument + extra, "Bearer " + TOKEN);
	}

	private int status(String path, String authorization) throws Exception {
		return send(path, authorization).statusCode();
	}

	private HttpResponse<String> send(String path, String authorization) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
		if (authorization != null) {
			request.header("Authorization", authorization);
		}
		try (HttpClient client = HttpClient.newHttpClient()) {
			return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
		}
	}

	private static JsonNode json(HttpResponse<String> response, int status) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
		return JSON.readTree(response.body());
	}

	private static String field(HttpResponse<String> problem) {
		JsonNode body = json(problem, 400);
		return body.get("errors").get(0).get("field").asString();
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
		jdbc.update("INSERT INTO instrument (id, simulation_id, symbol, reference_price_cents) VALUES (?, ?, 'XPRT', 10000)",
				instrument, simulation);
		jdbc.update("INSERT INTO app_user (id, username, password_hash, role) VALUES (?, 'buyer', ?, 'TRADER')",
				UUID.randomUUID(), TestBrowser.passwordHash(PASSWORD));
		for (String username : List.of("trader", "buyer")) {
			UUID account = UUID.randomUUID();
			jdbc.update("""
					INSERT INTO account (id, simulation_id, kind, display_name, owner_user_id)
					SELECT ?, ?, 'TRADER', ?, id FROM app_user WHERE username = ?""", account, simulation, username,
					username);
			fund(simulation, issuance, account);
		}
	}

	/** 1,000,000.00 and 1,000 shares from one genesis batch. */
	private void fund(UUID simulation, UUID issuance, UUID account) {
		UUID batch = UUID.randomUUID();
		jdbc.update("INSERT INTO journal_batch (id, simulation_id, cause, command_sequence) VALUES (?, ?, 'GENESIS', 1)",
				batch, simulation);
		String post = """
				INSERT INTO ledger_entry (batch_id, account_id, instrument_id, bucket, delta)
				VALUES (?, ?, ?, 'AVAILABLE', ?)""";
		jdbc.update(post, batch, issuance, null, -100_000_000);
		jdbc.update(post, batch, account, null, 100_000_000);
		jdbc.update(post, batch, issuance, instrument, -1_000);
		jdbc.update(post, batch, account, instrument, 1_000);
		jdbc.update("""
				INSERT INTO cash_balance (account_id, account_kind, total_cents, reserved_cents)
				VALUES (?, 'TRADER', 100000000, 0)""", account);
		jdbc.update("""
				INSERT INTO position (account_id, account_kind, instrument_id, total_quantity, reserved_quantity)
				VALUES (?, 'TRADER', ?, 1000, 0)""", account, instrument);
	}

}
