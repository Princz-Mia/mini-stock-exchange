package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.HashMap;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.mia_princz.mse.contracts.events.BookLevel;
import com.mia_princz.mse.contracts.events.MarketSnapshot;
import com.mia_princz.mse.contracts.events.VenueState;
import com.mia_princz.mse.contracts.events.WireEvents;
import com.mia_princz.mse.execution.trading.application.TradingEngine;

/**
 * Opening, halting and resuming over real HTTP and PostgreSQL. The property gives this class its own context and
 * database. The market starts in SETUP with two instruments and two funded traders; the whole lifecycle runs in one
 * test, because every transition changes the one market all tests would share.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=market-control", "mse.dev-users.trader-password=" + MarketControlIT.PASSWORD,
				"mse.dev-users.operator-password=" + MarketControlIT.PASSWORD })
class MarketControlIT {

	static final String PASSWORD = "test-market-control-password";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final long STARTING_CASH = 10_000_000;

	private static final long STARTING_SHARES = 1_000;

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	TradingEngine engine;

	private UUID first;

	private UUID second;

	@Test
	void setupOpensHaltsAndResumesWithAnswersThatRepeatAndAnAuditTrail() throws Exception {
		TestBrowser operator = TestBrowser.loggedIn(port, "operator", PASSWORD);
		TestBrowser anonymous = new TestBrowser(port);

		// Before any market exists: nothing to open, and nothing committed, so the key stays usable.
		assertThat(field(anonymous.get("/api/v1/market"), "state")).isEqualTo("NOT_SET_UP");
		UUID early = UUID.randomUUID();
		assertThat(code(control(operator, "open", early), 409)).isEqualTo("MARKET_NOT_SET_UP");

		new TransactionTemplate(transactionManager).executeWithoutResult((status) -> setUpMarket());
		engine.start();
		TestBrowser trader = TestBrowser.loggedIn(port, "trader", PASSWORD);
		TestBrowser other = TestBrowser.loggedIn(port, "other", PASSWORD);
		JsonNode setup = JSON.readTree(anonymous.get("/api/v1/market").body());
		assertThat(setup.get("state").asString()).isEqualTo("SETUP");
		assertThat(setup.get("openedAt").isNull()).isTrue();

		// SETUP takes no orders and cannot be halted.
		assertThat(code(place(trader, limit(first, "BUY", 1, "100.00")), 422)).isEqualTo("MARKET_NOT_OPEN");
		UUID haltInSetup = UUID.randomUUID();
		HttpResponse<String> refused = control(operator, "halt", haltInSetup);
		assertThat(code(refused, 409)).isEqualTo("MARKET_TRANSITION_NOT_ALLOWED");
		assertThat(field(refused, "state")).isEqualTo("SETUP");

		// Open, with the key that was refused before a market existed.
		HttpResponse<String> opened = control(operator, "open", early);
		assertThat(opened.statusCode()).as(opened.body()).isEqualTo(200);
		long openSequence = Long.parseLong(field(opened, "commandSequence"));
		assertThat(field(opened, "state")).isEqualTo("OPEN");
		JsonNode market = JSON.readTree(anonymous.get("/api/v1/market").body());
		assertThat(market.get("state").asString()).isEqualTo("OPEN");
		assertThat(market.get("openedAt").isNull()).isFalse();
		assertThat(market.get("commandSequence").asString()).isEqualTo(Long.toString(openSequence));
		assertThat(snapshots(openSequence)).containsOnlyKeys(first, second)
			.allSatisfy((instrument, snapshot) -> assertThat(snapshot.getVenueState()).isEqualTo(VenueState.OPEN));

		// The same key repeats the first answer; a new key finds the market already open; another action with the
		// same key is a conflict.
		HttpResponse<String> repeated = control(operator, "open", early);
		assertThat(repeated.statusCode()).isEqualTo(200);
		assertThat(repeated.body()).isEqualTo(opened.body());
		UUID openAgain = UUID.randomUUID();
		assertThat(field(control(operator, "open", openAgain), "state")).isEqualTo("OPEN");
		assertThat(code(control(operator, "open", openAgain), 409)).isEqualTo("MARKET_TRANSITION_NOT_ALLOWED");
		assertThat(code(control(operator, "halt", early), 409)).isEqualTo("IDEMPOTENCY_CONFLICT");

		// Orders rest while open.
		String offer = orderId(place(other, limit(first, "SELL", 5, "100.00")));
		String bid = orderId(place(trader, limit(first, "BUY", 2, "90.00")));

		// Halt: new orders are rejected, cancels still work, and the snapshot still shows the resting offer.
		UUID halt = UUID.randomUUID();
		HttpResponse<String> halted = control(operator, "halt", halt);
		assertThat(field(halted, "state")).as(halted.body()).isEqualTo("HALTED");
		long haltSequence = Long.parseLong(field(halted, "commandSequence"));
		MarketSnapshot haltedFirst = snapshots(haltSequence).get(first);
		assertThat(haltedFirst.getVenueState()).isEqualTo(VenueState.HALTED);
		assertThat(haltedFirst.getAsks()).containsExactly(new BookLevel(10_000L, 5L, 1));
		assertThat(haltedFirst.getBids()).containsExactly(new BookLevel(9_000L, 2L, 1));
		assertThat(code(place(trader, limit(first, "BUY", 5, "100.00")), 422)).isEqualTo("MARKET_NOT_OPEN");
		HttpResponse<String> canceled = trader.post("/api/v1/orders/" + bid + "/cancel", "", "Idempotency-Key",
				UUID.randomUUID().toString());
		assertThat(field(canceled, "status")).as(canceled.body()).isEqualTo("CANCELED");
		UUID haltAgain = UUID.randomUUID();
		assertThat(code(control(operator, "halt", haltAgain), 409)).isEqualTo("MARKET_TRANSITION_NOT_ALLOWED");

		// Resume: the offer that rested before the halt is still there to trade against.
		UUID resume = UUID.randomUUID();
		assertThat(field(control(operator, "resume", resume), "state")).isEqualTo("OPEN");
		HttpResponse<String> bought = place(trader, limit(first, "BUY", 5, "100.00"));
		assertThat(field(bought, "state")).as(bought.body()).isEqualTo("FILLED");
		assertThat(jdbc.queryForObject("SELECT state FROM orders WHERE id = ?::uuid", String.class, offer))
			.isEqualTo("FILLED");

		// Every operator command is audited in its own transaction, refusals included, but not the one that found
		// no market.
		UUID operatorId = jdbc.queryForObject("SELECT id FROM app_user WHERE username = 'operator'", UUID.class);
		List<Map<String, Object>> audit = jdbc.queryForList("""
				SELECT actor_user_id, action, outcome, correlation_id FROM audit_log ORDER BY command_sequence""");
		assertThat(audit)
			.extracting((row) -> row.get("action") + " " + row.get("outcome") + " " + row.get("correlation_id"))
			.containsExactly("MARKET_HALT NOT_ALLOWED " + haltInSetup, "MARKET_OPEN APPLIED " + early,
					"MARKET_OPEN NOT_ALLOWED " + openAgain, "MARKET_HALT APPLIED " + halt,
					"MARKET_HALT NOT_ALLOWED " + haltAgain, "MARKET_RESUME APPLIED " + resume);
		assertThat(audit).allSatisfy((row) -> assertThat(row.get("actor_user_id")).isEqualTo(operatorId));
	}

	@Test
	void onlyOperatorsControlTheMarketAndAnyoneCanReadIt() throws Exception {
		TestBrowser trader = TestBrowser.loggedIn(port, "trader", PASSWORD);
		assertThat(control(trader, "halt", UUID.randomUUID()).statusCode()).isEqualTo(403);
		assertThat(new TestBrowser(port).get("/api/v1/market").statusCode()).isEqualTo(200);
	}

	private static HttpResponse<String> control(TestBrowser browser, String action, UUID key) throws Exception {
		return browser.post("/api/v1/admin/market/" + action, "", "Idempotency-Key", key.toString());
	}

	private static HttpResponse<String> place(TestBrowser browser, String body) throws Exception {
		return browser.post("/api/v1/orders", body, "Idempotency-Key", UUID.randomUUID().toString());
	}

	private static String limit(UUID instrument, String side, long quantity, String price) {
		return """
				{"instrumentId":"%s","side":"%s","type":"LIMIT","timeInForce":"GTC","quantity":%d,"limitPrice":"%s"}"""
			.formatted(instrument, side, quantity, price);
	}

	private static String orderId(HttpResponse<String> placed) {
		assertThat(placed.statusCode()).as(placed.body()).isEqualTo(201);
		return field(placed, "orderId");
	}

	private static String field(HttpResponse<String> response, String name) {
		return JSON.readTree(response.body()).get(name).asString();
	}

	private static String code(HttpResponse<String> problem, int status) {
		assertThat(problem.statusCode()).as(problem.body()).isEqualTo(status);
		return field(problem, "code");
	}

	private Map<UUID, MarketSnapshot> snapshots(long commandSequence) throws Exception {
		Map<UUID, MarketSnapshot> byInstrument = new HashMap<>();
		for (byte[] payload : jdbc.queryForList(
				"SELECT payload FROM outbox WHERE topic = 'mse.market.v1' AND command_sequence = ?", byte[].class,
				commandSequence)) {
			MarketSnapshot snapshot = WireEvents.decodeSnapshot(payload);
			byInstrument.put(snapshot.getInstrumentId(), snapshot);
		}
		return byInstrument;
	}

	private void setUpMarket() {
		UUID simulation = UUID.randomUUID();
		UUID issuance = UUID.randomUUID();
		first = UUID.randomUUID();
		second = UUID.randomUUID();
		jdbc.update("INSERT INTO simulation (id, state) VALUES (?, 'SETUP')", simulation);
		jdbc.update("INSERT INTO account (id, simulation_id, kind, display_name) VALUES (?, ?, 'ISSUANCE', 'Issuance')",
				issuance, simulation);
		jdbc.update("INSERT INTO instrument (id, simulation_id, symbol, reference_price_cents) VALUES (?, ?, 'ONE', 10000)",
				first, simulation);
		jdbc.update("INSERT INTO instrument (id, simulation_id, symbol, reference_price_cents) VALUES (?, ?, 'TWO', 5000)",
				second, simulation);
		jdbc.update("INSERT INTO app_user (id, username, password_hash, role) VALUES (?, 'other', ?, 'TRADER')",
				UUID.randomUUID(), TestBrowser.passwordHash(PASSWORD));
		for (String username : List.of("trader", "other")) {
			UUID account = UUID.randomUUID();
			jdbc.update("""
					INSERT INTO account (id, simulation_id, kind, display_name, owner_user_id)
					SELECT ?, ?, 'TRADER', ?, id FROM app_user WHERE username = ?""", account, simulation, username,
					username);
			UUID batch = UUID.randomUUID();
			jdbc.update(
					"INSERT INTO journal_batch (id, simulation_id, cause, command_sequence) VALUES (?, ?, 'GENESIS', 1)",
					batch, simulation);
			String post = "INSERT INTO ledger_entry (batch_id, account_id, instrument_id, bucket, delta) VALUES (?, ?, ?, 'AVAILABLE', ?)";
			jdbc.update(post, batch, issuance, null, -STARTING_CASH);
			jdbc.update(post, batch, account, null, STARTING_CASH);
			jdbc.update(post, batch, issuance, first, -STARTING_SHARES);
			jdbc.update(post, batch, account, first, STARTING_SHARES);
			jdbc.update("""
					INSERT INTO cash_balance (account_id, account_kind, total_cents, reserved_cents)
					VALUES (?, 'TRADER', ?, 0)""", account, STARTING_CASH);
			jdbc.update("""
					INSERT INTO position (account_id, account_kind, instrument_id, total_quantity, reserved_quantity)
					VALUES (?, 'TRADER', ?, ?, 0)""", account, first, STARTING_SHARES);
		}
	}

}
