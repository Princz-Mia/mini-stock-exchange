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
 * Editing an instrument's reference price over real HTTP and PostgreSQL, starting from an empty database. The
 * property gives this class its own context and database; one test walks the whole sequence, because the market it
 * creates is shared.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=instrument-editing",
				"mse.dev-users.trader-password=" + InstrumentEditingIT.PASSWORD,
				"mse.dev-users.operator-password=" + InstrumentEditingIT.PASSWORD })
class InstrumentEditingIT {

	static final String PASSWORD = "test-instrument-editing-password";

	private static final OpenApiContract CONTRACT = OpenApiContract.get();

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String EDIT = "/api/v1/admin/instruments/{instrumentId}";

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TradingEngine engine;

	@Test
	void referencePricesChangeOnlyInSetup() throws Exception {
		TestBrowser operator = TestBrowser.loggedIn(port, "operator", PASSWORD);
		TestBrowser anonymous = new TestBrowser(port);

		// Before any market exists there is nothing to edit, and the answer creates no market.
		HttpResponse<String> early = edit(operator, UUID.randomUUID(), UUID.randomUUID(), "10.00");
		assertThat(code(early, 404)).isEqualTo("INSTRUMENT_NOT_FOUND");
		CONTRACT.assertResponse("PUT", EDIT, 404, early.headers().firstValue("Content-Type").orElse(null),
				early.body());
		assertThat(field(anonymous.get("/api/v1/market"), "state")).isEqualTo("NOT_SET_UP");

		UUID novaKey = UUID.randomUUID();
		UUID nova = add(operator, novaKey, "NOVA", "100.00");
		UUID orbt = add(operator, UUID.randomUUID(), "ORBT", "50.00");
		HttpResponse<String> funded = operator.post("/api/v1/admin/accounts", """
				{"username":"trader","cash":"1000.00","holdings":[{"instrumentId":"%s","quantity":10}]}"""
			.formatted(nova), "Idempotency-Key", UUID.randomUUID().toString());
		assertThat(funded.statusCode()).as(funded.body()).isEqualTo(201);

		// An edit in SETUP changes the price and publishes the instrument's snapshot at the command's sequence.
		UUID editKey = UUID.randomUUID();
		HttpResponse<String> edited = edit(operator, editKey, nova, "120.50");
		assertThat(edited.statusCode()).as(edited.body()).isEqualTo(200);
		CONTRACT.assertResponse("PUT", EDIT, 200, edited.headers().firstValue("Content-Type").orElse(null),
				edited.body());
		JsonNode body = JSON.readTree(edited.body());
		assertThat(body.get("instrumentId").asString()).isEqualTo(nova.toString());
		assertThat(body.get("symbol").asString()).isEqualTo("NOVA");
		assertThat(body.get("referencePrice").asString()).isEqualTo("120.50");
		long editSequence = Long.parseLong(body.get("commandSequence").asString());
		MarketSnapshot snapshot = snapshotAt(editSequence);
		assertThat(snapshot.getInstrumentId()).isEqualTo(nova);
		assertThat(snapshot.getSymbol()).isEqualTo("NOVA");
		assertThat(snapshot.getVenueState()).isEqualTo(VenueState.SETUP);
		assertThat(snapshot.getRules().getReferencePriceCents()).isEqualTo(12_050);
		assertThat(snapshot.getRules().getRuleVersion()).as("nothing traded under the old price").isEqualTo(1);
		assertThat(snapshot.getBids()).isEmpty();
		assertThat(price("NOVA")).isEqualTo(12_050);
		assertThat(price("ORBT")).as("only the named instrument changes").isEqualTo(5_000);

		// Starting shares are valued at the new price, since nothing has traded.
		JsonNode holding = JSON.readTree(TestBrowser.loggedIn(port, "trader", PASSWORD).get("/api/v1/portfolio").body())
			.get("holdings")
			.get(0);
		assertThat(holding.get("mark").get("price").asString()).isEqualTo("120.50");
		assertThat(holding.get("value").asString()).isEqualTo("1205.00");
		assertThat(holding.get("averageCost").asString()).as("starting shares cost the reference price").isEqualTo("120.50");

		// The same key and body repeat the answer; the same key for another price or instrument is a conflict.
		HttpResponse<String> repeated = edit(operator, editKey, nova, "120.50");
		assertThat(repeated.statusCode()).isEqualTo(200);
		assertThat(repeated.body()).isEqualTo(edited.body());
		assertThat(code(edit(operator, editKey, nova, "121.00"), 409)).isEqualTo("IDEMPOTENCY_CONFLICT");
		assertThat(code(edit(operator, editKey, orbt, "120.50"), 409)).isEqualTo("IDEMPOTENCY_CONFLICT");
		assertThat(outboxAt(editSequence)).as("a replay publishes nothing").isEqualTo(1);
		// A key already used to add an instrument answers another route's request as a conflict, not as a failure.
		assertThat(code(edit(operator, novaKey, nova, "100.00"), 409)).isEqualTo("IDEMPOTENCY_CONFLICT");

		// The price it already has is applied like any other.
		UUID sameKey = UUID.randomUUID();
		HttpResponse<String> same = edit(operator, sameKey, nova, "120.50");
		assertThat(same.statusCode()).as(same.body()).isEqualTo(200);
		long sameSequence = Long.parseLong(field(same, "commandSequence"));
		assertThat(sameSequence).isGreaterThan(editSequence);
		assertThat(snapshotAt(sameSequence).getRules().getReferencePriceCents()).isEqualTo(12_050);

		// A display name is set with the price, and every edit sets both: one left out, or null, removes the name.
		UUID nameKey = UUID.randomUUID();
		HttpResponse<String> named = edit(operator, nameKey, nova, "120.50", "Nova Dynamics");
		assertThat(named.statusCode()).as(named.body()).isEqualTo(200);
		CONTRACT.assertResponse("PUT", EDIT, 200, named.headers().firstValue("Content-Type").orElse(null),
				named.body());
		assertThat(field(named, "displayName")).isEqualTo("Nova Dynamics");
		assertThat(displayName("NOVA")).isEqualTo("Nova Dynamics");
		assertThat(snapshotAt(Long.parseLong(field(named, "commandSequence"))).getDisplayName())
			.isEqualTo("Nova Dynamics");
		assertThat(code(edit(operator, nameKey, nova, "120.50", "Nova"), 409)).isEqualTo("IDEMPOTENCY_CONFLICT");
		assertThat(code(edit(operator, nameKey, nova, "120.50"), 409)).as("no name is another request")
			.isEqualTo("IDEMPOTENCY_CONFLICT");
		UUID omittedKey = UUID.randomUUID();
		HttpResponse<String> omitted = edit(operator, omittedKey, nova, "120.50");
		assertThat(omitted.statusCode()).as(omitted.body()).isEqualTo(200);
		assertThat(JSON.readTree(omitted.body()).get("displayName").isNull()).isTrue();
		assertThat(displayName("NOVA")).as("left out, so removed").isNull();
		assertThat(snapshotAt(Long.parseLong(field(omitted, "commandSequence"))).getDisplayName()).isNull();
		UUID renamedKey = UUID.randomUUID();
		assertThat(edit(operator, renamedKey, nova, "120.50", "Nova Dynamics").statusCode()).isEqualTo(200);
		UUID nulledKey = UUID.randomUUID();
		HttpResponse<String> nulled = operator.put(path(nova), """
				{"referencePrice":"120.50","displayName":null}""", "Idempotency-Key", nulledKey.toString());
		assertThat(nulled.statusCode()).as(nulled.body()).isEqualTo(200);
		assertThat(displayName("NOVA")).as("null, so removed").isNull();
		for (String bad : List.of("", " Nova", "Nova ", "Nova  Dynamics", "Nova\tDynamics", "Nova\u200BDynamics",
				"x".repeat(101))) {
			HttpResponse<String> refused = edit(operator, UUID.randomUUID(), nova, "120.50", bad);
			assertThat(code(refused, 400)).as(bad).isEqualTo("VALIDATION_FAILED");
			assertThat(fields(refused)).as(bad).containsExactly("displayName INVALID_DISPLAY_NAME");
		}
		UUID longestKey = UUID.randomUUID();
		assertThat(edit(operator, longestKey, nova, "120.50", "x".repeat(100)).statusCode()).isEqualTo(200);
		UUID lastNameKey = UUID.randomUUID();
		assertThat(edit(operator, lastNameKey, nova, "120.50", "Nova Dynamics").statusCode()).isEqualTo(200);

		// An instrument this market does not have: committed, so its key repeats the answer.
		UUID unknownKey = UUID.randomUUID();
		UUID missingId = UUID.randomUUID();
		HttpResponse<String> unknown = edit(operator, unknownKey, missingId, "10.00");
		assertThat(code(unknown, 404)).isEqualTo("INSTRUMENT_NOT_FOUND");
		assertThat(Long.parseLong(field(unknown, "commandSequence"))).isGreaterThan(sameSequence);
		assertThat(edit(operator, unknownKey, missingId, "10.00").body()).isEqualTo(unknown.body());
		// The instrument is part of the request, so the same key for one that exists is a conflict.
		assertThat(code(edit(operator, unknownKey, orbt, "10.00"), 409)).isEqualTo("IDEMPOTENCY_CONFLICT");

		// Invalid requests are refused before the writer.
		HttpResponse<String> invalid = edit(operator, UUID.randomUUID(), nova, "100.001");
		assertThat(code(invalid, 400)).isEqualTo("VALIDATION_FAILED");
		assertThat(fields(invalid)).containsExactly("referencePrice INVALID_PRICE");
		HttpResponse<String> missing = operator.put(path(nova), "{}", "Idempotency-Key", UUID.randomUUID().toString());
		assertThat(code(missing, 400)).isEqualTo("VALIDATION_FAILED");
		assertThat(fields(missing)).containsExactly("referencePrice REQUIRED");
		assertThat(operator.put(path(nova), """
				{"referencePrice":"1.00","symbol":"NOVB"}""", "Idempotency-Key", UUID.randomUUID().toString())
			.statusCode()).as("the symbol cannot be changed").isEqualTo(400);
		assertThat(operator.put("/api/v1/admin/instruments/not-a-uuid", """
				{"referencePrice":"1.00"}""", "Idempotency-Key", UUID.randomUUID().toString()).statusCode())
			.isEqualTo(400);
		assertThat(price("NOVA")).isEqualTo(12_050);

		// Once open, the catalogue is frozen: the refusal is committed and repeats under its key.
		HttpResponse<String> opened = operator.post("/api/v1/admin/market/open", "", "Idempotency-Key",
				UUID.randomUUID().toString());
		assertThat(opened.statusCode()).as(opened.body()).isEqualTo(200);
		UUID lateNameKey = UUID.randomUUID();
		assertThat(code(edit(operator, lateNameKey, nova, "120.50", "Another"), 409))
			.isEqualTo("MARKET_NOT_IN_SETUP");
		assertThat(displayName("NOVA")).as("frozen with the catalogue").isEqualTo("Nova Dynamics");
		UUID lateKey = UUID.randomUUID();
		HttpResponse<String> late = edit(operator, lateKey, nova, "90.00");
		assertThat(code(late, 409)).isEqualTo("MARKET_NOT_IN_SETUP");
		CONTRACT.assertResponse("PUT", EDIT, 409, late.headers().firstValue("Content-Type").orElse(null), late.body());
		long lateSequence = Long.parseLong(field(late, "commandSequence"));
		assertThat(edit(operator, lateKey, nova, "90.00").body()).isEqualTo(late.body());
		assertThat(outboxAt(lateSequence)).as("a refusal publishes nothing").isZero();
		assertThat(price("NOVA")).isEqualTo(12_050);
		// An instrument the market does not have is still answered as unknown.
		UUID lateUnknownKey = UUID.randomUUID();
		assertThat(code(edit(operator, lateUnknownKey, UUID.randomUUID(), "90.00"), 404))
			.isEqualTo("INSTRUMENT_NOT_FOUND");

		// A restart rebuilds the books from the database; the price stays as edited.
		engine.start();
		assertThat(engine.depth(nova, Side.SELL).get(5, TimeUnit.SECONDS)).isEmpty();
		assertThat(price("NOVA")).isEqualTo(12_050);

		UUID operatorId = jdbc.queryForObject("SELECT id FROM app_user WHERE username = 'operator'", UUID.class);
		List<String> audit = jdbc.queryForList("""
				SELECT action || ' ' || outcome || ' ' || correlation_id FROM audit_log
				WHERE actor_user_id = ? AND action = 'INSTRUMENT_EDIT' ORDER BY command_sequence""", String.class,
				operatorId);
		assertThat(audit).containsExactly("INSTRUMENT_EDIT APPLIED " + editKey, "INSTRUMENT_EDIT APPLIED " + sameKey,
				"INSTRUMENT_EDIT APPLIED " + nameKey, "INSTRUMENT_EDIT APPLIED " + omittedKey,
				"INSTRUMENT_EDIT APPLIED " + renamedKey, "INSTRUMENT_EDIT APPLIED " + nulledKey,
				"INSTRUMENT_EDIT APPLIED " + longestKey, "INSTRUMENT_EDIT APPLIED " + lastNameKey,
				"INSTRUMENT_EDIT NOT_ALLOWED " + unknownKey, "INSTRUMENT_EDIT NOT_ALLOWED " + lateNameKey,
				"INSTRUMENT_EDIT NOT_ALLOWED " + lateKey,
				"INSTRUMENT_EDIT NOT_ALLOWED " + lateUnknownKey);
	}

	@Test
	void onlyOperatorsEditInstruments() throws Exception {
		TestBrowser trader = TestBrowser.loggedIn(port, "trader", PASSWORD);
		assertThat(edit(trader, UUID.randomUUID(), UUID.randomUUID(), "25.00").statusCode()).isEqualTo(403);
	}

	private static UUID add(TestBrowser operator, UUID key, String symbol, String price) throws Exception {
		HttpResponse<String> added = operator.post("/api/v1/admin/instruments", """
				{"symbol":"%s","referencePrice":"%s"}""".formatted(symbol, price), "Idempotency-Key", key.toString());
		assertThat(added.statusCode()).as(added.body()).isEqualTo(201);
		return UUID.fromString(field(added, "instrumentId"));
	}

	private static HttpResponse<String> edit(TestBrowser browser, UUID key, UUID instrument, String price)
			throws Exception {
		return browser.put(path(instrument), """
				{"referencePrice":"%s"}""".formatted(price), "Idempotency-Key", key.toString());
	}

	private static HttpResponse<String> edit(TestBrowser browser, UUID key, UUID instrument, String price,
			String displayName) throws Exception {
		return browser.put(path(instrument),
				JSON.writeValueAsString(java.util.Map.of("referencePrice", price, "displayName", displayName)),
				"Idempotency-Key", key.toString());
	}

	private String displayName(String symbol) {
		return jdbc.queryForObject("SELECT display_name FROM instrument WHERE symbol = ?", String.class, symbol);
	}

	private static String path(UUID instrument) {
		return "/api/v1/admin/instruments/" + instrument;
	}

	private MarketSnapshot snapshotAt(long sequence) throws Exception {
		return WireEvents.decodeSnapshot(jdbc.queryForObject(
				"SELECT payload FROM outbox WHERE topic = 'mse.market.v1' AND command_sequence = ?", byte[].class,
				sequence));
	}

	private long outboxAt(long sequence) {
		return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE command_sequence = ?", Long.class, sequence);
	}

	private long price(String symbol) {
		return jdbc.queryForObject("SELECT reference_price_cents FROM instrument WHERE symbol = ?", Long.class, symbol);
	}

	private static List<String> fields(HttpResponse<String> problem) {
		List<String> fields = new ArrayList<>();
		JSON.readTree(problem.body())
			.get("errors")
			.forEach((error) -> fields.add(error.get("field").asString() + " " + error.get("code").asString()));
		return fields;
	}

	private static String field(HttpResponse<String> response, String name) {
		return JSON.readTree(response.body()).get(name).asString();
	}

	private static String code(HttpResponse<String> problem, int status) {
		assertThat(problem.statusCode()).as(problem.body()).isEqualTo(status);
		return field(problem, "code");
	}

}
