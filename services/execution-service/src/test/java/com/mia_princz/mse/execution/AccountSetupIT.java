package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.mia_princz.mse.execution.trading.application.TradingEngine;

/**
 * Provisioning funded accounts over real HTTP and PostgreSQL, from an empty database through opening and trading.
 * The property gives this class its own context and database; one test walks the whole sequence, because the market
 * it sets up is shared.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=account-setup", "mse.dev-users.trader-password=" + AccountSetupIT.PASSWORD,
				"mse.dev-users.operator-password=" + AccountSetupIT.PASSWORD })
class AccountSetupIT {

	static final String PASSWORD = "test-account-setup-password";

	private static final OpenApiContract CONTRACT = OpenApiContract.get();

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String PROVISION = "/api/v1/admin/accounts";

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TradingEngine engine;

	@Test
	void accountsAreFundedOnceFromIssuanceAndCanTradeAfterOpening() throws Exception {
		TestBrowser operator = TestBrowser.loggedIn(port, "operator", PASSWORD);
		String nova = instrument(operator, "NOVA", "100.00");
		String orbt = instrument(operator, "ORBT", "50.00");
		jdbc.update("INSERT INTO app_user (id, username, password_hash, role) VALUES (?, 'other', ?, 'TRADER')",
				UUID.randomUUID(), TestBrowser.passwordHash(PASSWORD));

		// A trader gets cash and shares in one balanced genesis batch, recorded as their initial allocation.
		UUID traderKey = UUID.randomUUID();
		HttpResponse<String> funded = provision(operator, traderKey, "trader", "100000.00",
				holding(nova, 500), holding(orbt, 200));
		assertThat(funded.statusCode()).as(funded.body()).isEqualTo(201);
		CONTRACT.assertResponse("POST", PROVISION, 201, funded.headers().firstValue("Content-Type").orElse(null),
				funded.body());
		JsonNode account = JSON.readTree(funded.body());
		UUID accountId = UUID.fromString(account.get("accountId").asString());
		assertThat(account.get("cash").asString()).isEqualTo("100000.00");
		long sequence = Long.parseLong(account.get("commandSequence").asString());
		Map<String, Object> batch = jdbc.queryForMap(
				"SELECT id, cause FROM journal_batch WHERE command_sequence = ?", sequence);
		assertThat(batch.get("cause")).isEqualTo("GENESIS");
		assertThat(jdbc.queryForList("""
				SELECT coalesce(i.symbol, 'CASH') || ' ' || a.amount FROM initial_allocation a
				LEFT JOIN instrument i ON i.id = a.instrument_id
				WHERE a.account_id = ? AND a.journal_batch_id = ? ORDER BY 1""", String.class, accountId,
				batch.get("id")))
			.containsExactly("CASH 10000000", "NOVA 500", "ORBT 200");
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM ledger_entry e JOIN account a ON a.id = e.account_id
				WHERE e.batch_id = ? AND a.kind = 'ISSUANCE'""", Long.class, batch.get("id"))).isEqualTo(3);

		TestBrowser trader = TestBrowser.loggedIn(port, "trader", PASSWORD);
		JsonNode portfolio = JSON.readTree(trader.get("/api/v1/portfolio").body());
		assertThat(portfolio.get("cash").get("available").asString()).isEqualTo("100000.00");
		List<String> holdings = new ArrayList<>();
		portfolio.get("holdings")
			.forEach((held) -> holdings.add(held.get("symbol").asString() + " " + held.get("total").asLong()));
		assertThat(holdings).containsExactlyInAnyOrder("NOVA 500", "ORBT 200");

		// The same key repeats the answer however the holdings are listed; a different endowment is a conflict.
		HttpResponse<String> reordered = provision(operator, traderKey, "trader", "100000.00", holding(orbt, 200),
				holding(nova, 500));
		assertThat(reordered.statusCode()).isEqualTo(201);
		assertThat(reordered.body()).isEqualTo(funded.body());
		assertThat(code(provision(operator, traderKey, "trader", "99999.00", holding(nova, 500), holding(orbt, 200)),
				409)).isEqualTo("IDEMPOTENCY_CONFLICT");

		// One account per trader, and only for instruments the market has. Both refusals are committed.
		UUID againKey = UUID.randomUUID();
		assertThat(code(provision(operator, againKey, "trader", "1.00"), 409)).isEqualTo("ACCOUNT_EXISTS");
		assertThat(code(provision(operator, againKey, "trader", "1.00"), 409)).isEqualTo("ACCOUNT_EXISTS");
		UUID unknownKey = UUID.randomUUID();
		String missing = UUID.randomUUID().toString();
		assertThat(code(provision(operator, unknownKey, "other", "1.00", holding(missing, 1)), 422))
			.isEqualTo("UNKNOWN_INSTRUMENT");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM account WHERE display_name = 'other'", Long.class))
			.isZero();

		// Only traders get accounts; that is checked before the writer, so nothing is committed or audited.
		assertThat(code(provision(operator, UUID.randomUUID(), "nobody", "1.00"), 422)).isEqualTo("UNKNOWN_TRADER");
		assertThat(code(provision(operator, UUID.randomUUID(), "operator", "1.00"), 422)).isEqualTo("UNKNOWN_TRADER");

		// Invalid endowments are refused before the writer, with every problem listed.
		assertThat(fields(provision(operator, UUID.randomUUID(), "other", "-1.00", holding(nova, 1),
				holding(nova, 0)))).containsExactlyInAnyOrder("cash", "holdings[1].instrumentId", "holdings[1].quantity");
		assertThat(fields(provision(operator, UUID.randomUUID(), "other", "0.00"))).containsExactly("cash");

		UUID otherKey = UUID.randomUUID();
		assertThat(provision(operator, otherKey, "other", "50000.00", holding(nova, 100)).statusCode()).isEqualTo(201);

		// The operator reads the accounts back with what they started with, oldest first, in pages.
		HttpResponse<String> firstPage = operator.get(PROVISION + "?limit=1");
		CONTRACT.assertResponse("GET", PROVISION, 200, firstPage.headers().firstValue("Content-Type").orElse(null),
				firstPage.body());
		JsonNode first = JSON.readTree(firstPage.body());
		assertThat(first.get("accounts").size()).isEqualTo(1);
		JsonNode listed = first.get("accounts").get(0);
		assertThat(listed.get("username").asString()).isEqualTo("trader");
		assertThat(listed.get("kind").asString()).isEqualTo("TRADER");
		assertThat(listed.get("cash").asString()).isEqualTo("100000.00");
		List<String> allocated = new ArrayList<>();
		listed.get("holdings")
			.forEach((held) -> allocated.add(held.get("symbol").asString() + " " + held.get("quantity").asLong()));
		assertThat(allocated).containsExactly("NOVA 500", "ORBT 200");
		JsonNode second = JSON.readTree(
				operator.get(PROVISION + "?limit=1&cursor=" + first.get("nextCursor").asString()).body());
		assertThat(second.get("accounts").get(0).get("username").asString()).isEqualTo("other");
		assertThat(second.get("accounts").get(0).get("cash").asString()).isEqualTo("50000.00");
		assertThat(second.get("nextCursor").isNull()).isTrue();
		assertThat(trader.get(PROVISION).statusCode()).isEqualTo(403);

		// Once open, the funded accounts trade with each other, and no more accounts are funded. The instrument was added
		// through the operator API and nothing has restarted since, so this also trades on the tape the writer made then.
		UUID openKey = UUID.randomUUID();
		assertThat(operator.post("/api/v1/admin/market/open", "", "Idempotency-Key", openKey.toString()).statusCode())
			.isEqualTo(200);
		TestBrowser other = TestBrowser.loggedIn(port, "other", PASSWORD);
		assertThat(place(other, nova, "SELL", 10, "100.00").statusCode()).isEqualTo(201);
		HttpResponse<String> bought = place(trader, nova, "BUY", 10, "100.00");
		assertThat(JSON.readTree(bought.body()).get("state").asString()).as(bought.body()).isEqualTo("FILLED");

		// A restart reconciles balances, positions and the journal, including the genesis batches and the trade.
		engine.start();
		UUID lateKey = UUID.randomUUID();
		assertThat(code(provision(operator, lateKey, "nobody-else", "1.00"), 422)).isEqualTo("UNKNOWN_TRADER");
		jdbc.update("INSERT INTO app_user (id, username, password_hash, role) VALUES (?, 'late', ?, 'TRADER')",
				UUID.randomUUID(), TestBrowser.passwordHash(PASSWORD));
		assertThat(code(provision(operator, lateKey, "late", "1.00"), 409)).isEqualTo("MARKET_NOT_IN_SETUP");

		List<String> audit = jdbc.queryForList("""
				SELECT action || ' ' || outcome || ' ' || correlation_id FROM audit_log
				WHERE action IN ('ACCOUNT_PROVISION', 'MARKET_OPEN') ORDER BY command_sequence""", String.class);
		assertThat(audit).containsExactly("ACCOUNT_PROVISION APPLIED " + traderKey,
				"ACCOUNT_PROVISION NOT_ALLOWED " + againKey, "ACCOUNT_PROVISION NOT_ALLOWED " + unknownKey,
				"ACCOUNT_PROVISION APPLIED " + otherKey, "MARKET_OPEN APPLIED " + openKey,
				"ACCOUNT_PROVISION NOT_ALLOWED " + lateKey);
	}

	@Test
	void tradersCannotProvisionAccounts() throws Exception {
		TestBrowser trader = TestBrowser.loggedIn(port, "trader", PASSWORD);
		assertThat(provision(trader, UUID.randomUUID(), "trader", "1.00").statusCode()).isEqualTo(403);
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

	private static HttpResponse<String> provision(TestBrowser browser, UUID key, String username, String cash,
			String... holdings) throws Exception {
		String body = "{\"username\":\"%s\",\"cash\":\"%s\",\"holdings\":[%s]}".formatted(username, cash,
				String.join(",", holdings));
		return browser.post(PROVISION, body, "Idempotency-Key", key.toString());
	}

	private static HttpResponse<String> place(TestBrowser browser, String instrument, String side, long quantity,
			String price) throws Exception {
		return browser.post("/api/v1/orders", """
				{"instrumentId":"%s","side":"%s","type":"LIMIT","timeInForce":"GTC","quantity":%d,"limitPrice":"%s"}"""
			.formatted(instrument, side, quantity, price), "Idempotency-Key", UUID.randomUUID().toString());
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
