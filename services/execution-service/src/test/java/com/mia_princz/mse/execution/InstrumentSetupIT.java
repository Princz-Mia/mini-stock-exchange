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
 * Adding instruments over real HTTP and PostgreSQL, starting from an empty database: the first instrument creates
 * the market. The property gives this class its own context and database; one test walks the whole sequence,
 * because the market it creates is shared.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=instrument-setup", "mse.dev-users.trader-password=" + InstrumentSetupIT.PASSWORD,
				"mse.dev-users.operator-password=" + InstrumentSetupIT.PASSWORD })
class InstrumentSetupIT {

	static final String PASSWORD = "test-instrument-setup-password";

	private static final OpenApiContract CONTRACT = OpenApiContract.get();

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String ADD = "/api/v1/admin/instruments";

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TradingEngine engine;

	@Test
	void instrumentsAreAddedOnceInSetupAndTheFirstCreatesTheMarket() throws Exception {
		TestBrowser operator = TestBrowser.loggedIn(port, "operator", PASSWORD);
		TestBrowser anonymous = new TestBrowser(port);
		assertThat(field(anonymous.get("/api/v1/market"), "state")).isEqualTo("NOT_SET_UP");

		// The first instrument creates the market in SETUP, claimed by this writer.
		UUID novaKey = UUID.randomUUID();
		HttpResponse<String> nova = add(operator, novaKey, "NOVA", "100.00");
		assertThat(nova.statusCode()).as(nova.body()).isEqualTo(201);
		CONTRACT.assertResponse("POST", ADD, 201, nova.headers().firstValue("Content-Type").orElse(null), nova.body());
		JsonNode created = JSON.readTree(nova.body());
		UUID novaId = UUID.fromString(created.get("instrumentId").asString());
		assertThat(created.get("symbol").asString()).isEqualTo("NOVA");
		assertThat(created.get("referencePrice").asString()).isEqualTo("100.00");
		assertThat(field(anonymous.get("/api/v1/market"), "state")).isEqualTo("SETUP");
		assertThat(jdbc.queryForObject("SELECT writer_epoch FROM simulation", Long.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM account WHERE kind = 'ISSUANCE'", Long.class)).isEqualTo(1);

		// Its first public state is published at the command's sequence, and its book is ready for trading.
		MarketSnapshot snapshot = WireEvents.decodeSnapshot(jdbc.queryForObject(
				"SELECT payload FROM outbox WHERE topic = 'mse.market.v1' AND command_sequence = ?", byte[].class,
				Long.parseLong(created.get("commandSequence").asString())));
		assertThat(snapshot.getInstrumentId()).isEqualTo(novaId);
		assertThat(snapshot.getSymbol()).isEqualTo("NOVA");
		assertThat(snapshot.getVenueState()).isEqualTo(VenueState.SETUP);
		assertThat(snapshot.getRules().getReferencePriceCents()).isEqualTo(10_000);
		// Written with the event's second version, whose added fields nothing sets yet: their defaults.
		assertThat(snapshot.getRemoved()).isFalse();
		assertThat(snapshot.getDisplayName()).isNull();
		assertThat(snapshot.getOpensAt()).isNull();
		assertThat(snapshot.getClosesAt()).isNull();
		assertThat(engine.depth(novaId, Side.BUY).get(5, TimeUnit.SECONDS)).isEmpty();

		// The same key and body repeat the answer; the same key with another body is a conflict.
		HttpResponse<String> repeated = add(operator, novaKey, "NOVA", "100.00");
		assertThat(repeated.statusCode()).isEqualTo(201);
		assertThat(repeated.body()).isEqualTo(nova.body());
		assertThat(code(add(operator, novaKey, "NOVA", "101.00"), 409)).isEqualTo("IDEMPOTENCY_CONFLICT");

		// A symbol is taken once; the refusal is committed, so its key repeats it.
		UUID takenKey = UUID.randomUUID();
		assertThat(code(add(operator, takenKey, "NOVA", "50.00"), 409)).isEqualTo("SYMBOL_TAKEN");
		assertThat(code(add(operator, takenKey, "NOVA", "50.00"), 409)).isEqualTo("SYMBOL_TAKEN");

		// Invalid requests are refused before the writer, with every problem listed.
		HttpResponse<String> invalid = add(operator, UUID.randomUUID(), "nova", "100.001");
		assertThat(code(invalid, 400)).isEqualTo("VALIDATION_FAILED");
		List<String> fields = new ArrayList<>();
		JSON.readTree(invalid.body()).get("errors").forEach((error) -> fields.add(error.get("field").asString()));
		assertThat(fields).containsExactlyInAnyOrder("symbol", "referencePrice");

		UUID orbtKey = UUID.randomUUID();
		HttpResponse<String> orbt = add(operator, orbtKey, "ORBT", "50.00");
		assertThat(orbt.statusCode()).as(orbt.body()).isEqualTo(201);

		// Once open, the catalogue is frozen.
		UUID openKey = UUID.randomUUID();
		HttpResponse<String> opened = operator.post("/api/v1/admin/market/open", "", "Idempotency-Key",
				openKey.toString());
		assertThat(opened.statusCode()).as(opened.body()).isEqualTo(200);
		UUID lateKey = UUID.randomUUID();
		assertThat(code(add(operator, lateKey, "HELI", "200.00"), 409)).isEqualTo("MARKET_NOT_IN_SETUP");
		assertThat(jdbc.queryForList("SELECT symbol FROM instrument ORDER BY symbol", String.class))
			.containsExactly("NOVA", "ORBT");

		// A restart rebuilds the books from the database, including instruments added since the last start.
		engine.start();
		assertThat(engine.depth(novaId, Side.SELL).get(5, TimeUnit.SECONDS)).isEmpty();

		UUID operatorId = jdbc.queryForObject("SELECT id FROM app_user WHERE username = 'operator'", UUID.class);
		List<String> audit = jdbc.queryForList("""
				SELECT action || ' ' || outcome || ' ' || correlation_id FROM audit_log
				WHERE actor_user_id = ? ORDER BY command_sequence""", String.class, operatorId);
		assertThat(audit).containsExactly("INSTRUMENT_ADD APPLIED " + novaKey, "INSTRUMENT_ADD NOT_ALLOWED " + takenKey,
				"INSTRUMENT_ADD APPLIED " + orbtKey, "MARKET_OPEN APPLIED " + openKey,
				"INSTRUMENT_ADD NOT_ALLOWED " + lateKey);
	}

	@Test
	void tradersCannotAddInstruments() throws Exception {
		TestBrowser trader = TestBrowser.loggedIn(port, "trader", PASSWORD);
		assertThat(add(trader, UUID.randomUUID(), "QUAD", "25.00").statusCode()).isEqualTo(403);
	}

	private static HttpResponse<String> add(TestBrowser browser, UUID key, String symbol, String price)
			throws Exception {
		return browser.post(ADD, """
				{"symbol":"%s","referencePrice":"%s"}""".formatted(symbol, price), "Idempotency-Key", key.toString());
	}

	private static String field(HttpResponse<String> response, String name) {
		return JSON.readTree(response.body()).get(name).asString();
	}

	private static String code(HttpResponse<String> problem, int status) {
		assertThat(problem.statusCode()).as(problem.body()).isEqualTo(status);
		return field(problem, "code");
	}

}
