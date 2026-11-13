package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.mia_princz.mse.contracts.events.MarketSnapshot;
import com.mia_princz.mse.contracts.events.VenueState;
import com.mia_princz.mse.contracts.events.WireEvents;
import com.mia_princz.mse.execution.trading.application.TradingEngine;
import com.mia_princz.mse.execution.trading.domain.Side;

/**
 * Deleting an instrument over real HTTP and PostgreSQL, starting from an empty database. The property gives this class
 * its own context and database; one test walks the whole sequence, because the market it creates is shared.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=instrument-deleting",
				"mse.dev-users.trader-password=" + InstrumentDeletingIT.PASSWORD,
				"mse.dev-users.operator-password=" + InstrumentDeletingIT.PASSWORD })
class InstrumentDeletingIT {

	static final String PASSWORD = "test-instrument-deleting-password";

	private static final OpenApiContract CONTRACT = OpenApiContract.get();

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String DELETE = "/api/v1/admin/instruments/{instrumentId}";

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TradingEngine engine;

	@Test
	void unusedInstrumentsAreDeletedOnlyInSetup() throws Exception {
		TestBrowser operator = TestBrowser.loggedIn(port, "operator", PASSWORD);
		TestBrowser trader = TestBrowser.loggedIn(port, "trader", PASSWORD);
		TestBrowser anonymous = new TestBrowser(port);

		// Before any market exists there is nothing to delete, and the answer creates no market.
		HttpResponse<String> early = delete(operator, UUID.randomUUID(), UUID.randomUUID());
		assertThat(code(early, 404)).isEqualTo("INSTRUMENT_NOT_FOUND");
		contract(early, 404);
		assertThat(field(anonymous.get("/api/v1/market"), "state")).isEqualTo("NOT_SET_UP");

		UUID nova = add(operator, UUID.randomUUID(), "NOVA", "100.00");
		UUID heliKey = UUID.randomUUID();
		UUID heli = add(operator, heliKey, "HELI", "200.00");
		UUID quad = add(operator, UUID.randomUUID(), "QUAD", "25.00");
		UUID velo = add(operator, UUID.randomUUID(), "VELO", "80.00");

		// An unused instrument in SETUP is deleted, after its last snapshot, marked removed, at the command's sequence.
		UUID deleteKey = UUID.randomUUID();
		HttpResponse<String> deleted = delete(operator, deleteKey, heli);
		assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(200);
		contract(deleted, 200);
		JsonNode body = JSON.readTree(deleted.body());
		assertThat(body.get("instrumentId").asString()).isEqualTo(heli.toString());
		assertThat(body.get("symbol").asString()).isEqualTo("HELI");
		long deleteSequence = Long.parseLong(body.get("commandSequence").asString());
		MarketSnapshot removal = snapshotAt(deleteSequence);
		assertThat(removal.getRemoved()).isTrue();
		assertThat(removal.getInstrumentId()).isEqualTo(heli);
		assertThat(removal.getSymbol()).isEqualTo("HELI");
		assertThat(removal.getVenueState()).isEqualTo(VenueState.SETUP);
		assertThat(removal.getRules().getReferencePriceCents()).isEqualTo(20_000);
		assertThat(removal.getBids()).isEmpty();
		assertThat(removal.getAsks()).isEmpty();
		assertThat(removal.getRecentTrades()).isEmpty();
		assertThat(removal.getLastTrade()).isNull();
		assertThat(instruments()).containsExactly("NOVA", "QUAD", "VELO");

		// The same key and instrument repeat the answer; the same key for another instrument is a conflict.
		HttpResponse<String> repeated = delete(operator, deleteKey, heli);
		assertThat(repeated.statusCode()).isEqualTo(200);
		assertThat(repeated.body()).isEqualTo(deleted.body());
		assertThat(code(delete(operator, deleteKey, velo), 409)).isEqualTo("IDEMPOTENCY_CONFLICT");
		assertThat(outboxAt(deleteSequence)).as("a replay publishes nothing").isEqualTo(1);
		// A key is required, as for every command.
		assertThat(operator.delete(path(velo)).statusCode()).isEqualTo(400);

		// The engine no longer knows it, so endowments naming it are refused (committed, as any refusal of theirs).
		HttpResponse<String> funded = operator.post("/api/v1/admin/accounts", """
				{"username":"trader","cash":"1000.00","holdings":[{"instrumentId":"%s","quantity":10}]}"""
			.formatted(heli), "Idempotency-Key", UUID.randomUUID().toString());
		assertThat(code(funded, 422)).isEqualTo("UNKNOWN_INSTRUMENT");
		HttpResponse<String> bots = operator.post("/api/v1/admin/bots", """
				{"count":1,"cash":"1000.00","holdings":[{"instrumentId":"%s","quantity":10}]}""".formatted(heli),
				"Idempotency-Key", UUID.randomUUID().toString());
		assertThat(code(bots, 422)).isEqualTo("UNKNOWN_INSTRUMENT");

		// The symbol can be added again, under a new id. The first add's key still repeats its own answer.
		UUID readded = add(operator, UUID.randomUUID(), "HELI", "210.00");
		assertThat(readded).isNotEqualTo(heli);
		HttpResponse<String> firstAdd = operator.post("/api/v1/admin/instruments", """
				{"symbol":"HELI","referencePrice":"200.00"}""", "Idempotency-Key", heliKey.toString());
		assertThat(firstAdd.statusCode()).isEqualTo(201);
		assertThat(field(firstAdd, "instrumentId")).as("a stored answer, not a new instrument").isEqualTo(heli.toString());
		assertThat(instruments()).containsExactly("HELI", "NOVA", "QUAD", "VELO");

		// Shares given to accounts keep an instrument: the refusal names the holders, at most twenty, in name order.
		funded = operator.post("/api/v1/admin/accounts", """
				{"username":"trader","cash":"1000.00","holdings":[{"instrumentId":"%s","quantity":10}]}"""
			.formatted(nova), "Idempotency-Key", UUID.randomUUID().toString());
		assertThat(funded.statusCode()).as(funded.body()).isEqualTo(201);
		bots = operator.post("/api/v1/admin/bots", """
				{"count":22,"cash":"1000.00","holdings":[{"instrumentId":"%s","quantity":10}]}""".formatted(nova),
				"Idempotency-Key", UUID.randomUUID().toString());
		assertThat(bots.statusCode()).as(bots.body()).isEqualTo(201);
		UUID heldKey = UUID.randomUUID();
		HttpResponse<String> held = delete(operator, heldKey, nova);
		assertThat(code(held, 409)).isEqualTo("INSTRUMENT_IN_USE");
		contract(held, 409);
		JsonNode usage = JSON.readTree(held.body()).get("usage");
		List<String> firstTwenty = new ArrayList<>();
		for (int bot = 1; bot <= 20; bot++) {
			firstTwenty.add("bot-%04d".formatted(bot));
		}
		assertThat(names(usage.get("holders"))).containsExactlyElementsOf(firstTwenty);
		assertThat(usage.get("holderCount").asLong()).as("twenty-two bots and the trader").isEqualTo(23);
		assertThat(usage.get("orderCount").asLong()).isZero();
		long heldSequence = Long.parseLong(field(held, "commandSequence"));
		assertThat(delete(operator, heldKey, nova).body()).as("committed, so its key repeats it").isEqualTo(held.body());
		assertThat(outboxAt(heldSequence)).as("a refusal publishes nothing").isZero();
		assertThat(instruments()).contains("NOVA");

		// An order refused because the market is not open is stored, and keeps its instrument too.
		HttpResponse<String> earlyOrder = trader.post("/api/v1/orders", """
				{"instrumentId":"%s","side":"BUY","type":"LIMIT","timeInForce":"GTC","quantity":1,"limitPrice":"25.00"}"""
			.formatted(quad), "Idempotency-Key", UUID.randomUUID().toString());
		assertThat(code(earlyOrder, 422)).isEqualTo("MARKET_NOT_OPEN");
		assertThat(jdbc.queryForObject("SELECT state FROM orders WHERE instrument_id = ?", String.class, quad))
			.isEqualTo("REJECTED");
		UUID orderedKey = UUID.randomUUID();
		HttpResponse<String> ordered = delete(operator, orderedKey, quad);
		assertThat(code(ordered, 409)).as("refused, not a foreign key failure").isEqualTo("INSTRUMENT_IN_USE");
		usage = JSON.readTree(ordered.body()).get("usage");
		assertThat(names(usage.get("holders"))).isEmpty();
		assertThat(usage.get("holderCount").asLong()).isZero();
		assertThat(usage.get("orderCount").asLong()).isEqualTo(1);
		assertThat(instruments()).contains("QUAD");

		// An instrument this market does not have: committed, so its key repeats the answer.
		UUID unknownKey = UUID.randomUUID();
		UUID missingId = UUID.randomUUID();
		HttpResponse<String> unknown = delete(operator, unknownKey, missingId);
		assertThat(code(unknown, 404)).isEqualTo("INSTRUMENT_NOT_FOUND");
		assertThat(delete(operator, unknownKey, missingId).body()).isEqualTo(unknown.body());
		// The deleted one is unknown now as well.
		UUID goneKey = UUID.randomUUID();
		assertThat(code(delete(operator, goneKey, heli), 404)).isEqualTo("INSTRUMENT_NOT_FOUND");

		// Once open, the catalogue is frozen: the refusal is committed and repeats under its key.
		HttpResponse<String> opened = operator.post("/api/v1/admin/market/open", "", "Idempotency-Key",
				UUID.randomUUID().toString());
		assertThat(opened.statusCode()).as(opened.body()).isEqualTo(200);
		UUID lateKey = UUID.randomUUID();
		HttpResponse<String> late = delete(operator, lateKey, velo);
		assertThat(code(late, 409)).isEqualTo("MARKET_NOT_IN_SETUP");
		contract(late, 409);
		assertThat(delete(operator, lateKey, velo).body()).isEqualTo(late.body());
		assertThat(outboxAt(Long.parseLong(field(late, "commandSequence")))).isZero();
		assertThat(instruments()).containsExactly("HELI", "NOVA", "QUAD", "VELO");
		UUID lateUnknownKey = UUID.randomUUID();
		assertThat(code(delete(operator, lateUnknownKey, UUID.randomUUID()), 404)).isEqualTo("INSTRUMENT_NOT_FOUND");

		// A refused delete leaves the instrument trading: in use before opening, or refused once open.
		for (UUID kept : List.of(nova, velo)) {
			HttpResponse<String> order = trader.post("/api/v1/orders", """
					{"instrumentId":"%s","side":"BUY","type":"LIMIT","timeInForce":"GTC","quantity":1,"limitPrice":"1.00"}"""
				.formatted(kept), "Idempotency-Key", UUID.randomUUID().toString());
			assertThat(order.statusCode()).as(order.body()).isEqualTo(201);
		}

		// Trading works on the instrument added again, and the deleted id stays unknown.
		HttpResponse<String> onDeleted = trader.post("/api/v1/orders", """
				{"instrumentId":"%s","side":"BUY","type":"LIMIT","timeInForce":"GTC","quantity":1,"limitPrice":"20.00"}"""
			.formatted(heli), "Idempotency-Key", UUID.randomUUID().toString());
		assertThat(code(onDeleted, 422)).isEqualTo("UNKNOWN_INSTRUMENT");
		HttpResponse<String> onReadded = trader.post("/api/v1/orders", """
				{"instrumentId":"%s","side":"BUY","type":"LIMIT","timeInForce":"GTC","quantity":1,"limitPrice":"20.00"}"""
			.formatted(readded), "Idempotency-Key", UUID.randomUUID().toString());
		assertThat(onReadded.statusCode()).as(onReadded.body()).isEqualTo(201);

		// A restart rebuilds the books from the database, without the deleted instrument.
		engine.start();
		assertThat(engine.depth(readded, Side.BUY).get(5, TimeUnit.SECONDS)).hasSize(1);
		assertThat(code(trader.post("/api/v1/orders", """
				{"instrumentId":"%s","side":"BUY","type":"LIMIT","timeInForce":"GTC","quantity":1,"limitPrice":"20.00"}"""
			.formatted(heli), "Idempotency-Key", UUID.randomUUID().toString()), 422)).isEqualTo("UNKNOWN_INSTRUMENT");

		UUID operatorId = jdbc.queryForObject("SELECT id FROM app_user WHERE username = 'operator'", UUID.class);
		List<String> audit = jdbc.queryForList("""
				SELECT outcome || ' ' || correlation_id FROM audit_log
				WHERE actor_user_id = ? AND action = 'INSTRUMENT_DELETE' ORDER BY command_sequence""", String.class,
				operatorId);
		assertThat(audit).containsExactly("APPLIED " + deleteKey, "NOT_ALLOWED " + heldKey,
				"NOT_ALLOWED " + orderedKey, "NOT_ALLOWED " + unknownKey, "NOT_ALLOWED " + goneKey,
				"NOT_ALLOWED " + lateKey, "NOT_ALLOWED " + lateUnknownKey);
	}

	@Test
	void onlyOperatorsDeleteInstruments() throws Exception {
		TestBrowser trader = TestBrowser.loggedIn(port, "trader", PASSWORD);
		assertThat(delete(trader, UUID.randomUUID(), UUID.randomUUID()).statusCode()).isEqualTo(403);
		assertThat(delete(new TestBrowser(port), UUID.randomUUID(), UUID.randomUUID()).statusCode()).isIn(401, 403);
	}

	private static UUID add(TestBrowser operator, UUID key, String symbol, String price) throws Exception {
		HttpResponse<String> added = operator.post("/api/v1/admin/instruments", """
				{"symbol":"%s","referencePrice":"%s"}""".formatted(symbol, price), "Idempotency-Key", key.toString());
		assertThat(added.statusCode()).as(added.body()).isEqualTo(201);
		return UUID.fromString(field(added, "instrumentId"));
	}

	private static HttpResponse<String> delete(TestBrowser browser, UUID key, UUID instrument) throws Exception {
		return browser.delete(path(instrument), "Idempotency-Key", key.toString());
	}

	private static String path(UUID instrument) {
		return "/api/v1/admin/instruments/" + instrument;
	}

	private static void contract(HttpResponse<String> response, int status) {
		CONTRACT.assertResponse("DELETE", DELETE, status, response.headers().firstValue("Content-Type").orElse(null),
				response.body());
	}

	private MarketSnapshot snapshotAt(long sequence) throws Exception {
		return WireEvents.decodeSnapshot(jdbc.queryForObject(
				"SELECT payload FROM outbox WHERE topic = 'mse.market.v1' AND command_sequence = ?", byte[].class,
				sequence));
	}

	private long outboxAt(long sequence) {
		return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE command_sequence = ?", Long.class, sequence);
	}

	private List<String> instruments() {
		return jdbc.queryForList("SELECT symbol FROM instrument ORDER BY symbol", String.class);
	}

	private static List<String> names(JsonNode array) {
		List<String> names = new ArrayList<>();
		array.forEach((name) -> names.add(name.asString()));
		return names;
	}

	private static String field(HttpResponse<String> response, String name) {
		return JSON.readTree(response.body()).get(name).asString();
	}

	private static String code(HttpResponse<String> problem, int status) {
		assertThat(problem.statusCode()).as(problem.body()).isEqualTo(status);
		return field(problem, "code");
	}

}
