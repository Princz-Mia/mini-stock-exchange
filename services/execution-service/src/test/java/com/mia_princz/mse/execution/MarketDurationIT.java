package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.mia_princz.mse.execution.trading.adapter.CurrentSimulation;
import com.mia_princz.mse.execution.trading.application.TradingEngine;

/**
 * Opening the market for a set time over HTTP, against the application itself with a clock the test moves: the body,
 * its limits and its part in the request, the status that shows the close time, and the close the exchange makes when
 * the time comes, which the status then says was scheduled. The engine-level cases are in {@link ScheduledCloseIT}. Each
 * test sets up a market of its own; the clock is the class's, and each test counts from where it finds it.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=market-duration", "mse.market.close-check-interval=50ms",
				"mse.dev-users.trader-password=" + MarketDurationIT.PASSWORD,
				"mse.dev-users.operator-password=" + MarketDurationIT.PASSWORD,
				"mse.trading.human-commands-per-second=1000" })
class MarketDurationIT {

	static final String PASSWORD = "test-market-duration-password";

	private static final Instant NOW = Instant.parse("2031-03-04T05:06:07.123456Z");

	private static final MovableClock CLOCK = new MovableClock(NOW);

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final OpenApiContract CONTRACT = OpenApiContract.get();

	private static final String OPEN = "/api/v1/admin/market/open";

	private static final String MARKET = "/api/v1/market";

	/** The application's clock: the writer's, the schedule's and the close's. */
	@TestBean
	Clock clock;

	static Clock clock() {
		return CLOCK;
	}

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	TradingEngine engine;

	@Test
	void aMarketOpenedForASetTimeShowsItAndClosesOnItsOwnWhenItRunsOut() throws Exception {
		CLOCK.set(NOW);
		UUID instrument = new TransactionTemplate(transactionManager).execute((status) -> marketInSetup());
		engine.start();
		TestBrowser operator = TestBrowser.loggedIn(port, "operator", PASSWORD);
		TestBrowser trader = TestBrowser.loggedIn(port, "trader", PASSWORD);
		TestBrowser anonymous = new TestBrowser(port);
		JsonNode setup = market(anonymous);
		assertThat(setup.get("closesAt").isNull()).isTrue();
		assertThat(setup.get("closedOnSchedule").asBoolean()).isFalse();

		// Out of range, or not a duration at all: refused before anything is sequenced.
		for (String body : new String[] { "{\"durationMinutes\":4}", "{\"durationMinutes\":43201}" }) {
			HttpResponse<String> refused = operator.post(OPEN, body, "Idempotency-Key", UUID.randomUUID().toString());
			check(refused, 400);
			assertThat(JSON.readTree(refused.body()).get("code").asString()).as(body).isEqualTo("VALIDATION_FAILED");
		}
		// Not readable as the body at all: the framework refuses it, with no code of the application's.
		for (String body : new String[] { "{\"durationMinutes\":\"ninety\"}",
				"{\"durationMinutes\":90,\"closesAt\":\"2031-03-04T06:00:00Z\"}" }) {
			HttpResponse<String> unreadable = operator.post(OPEN, body, "Idempotency-Key", UUID.randomUUID().toString());
			check(unreadable, 400);
			assertThat(JSON.readTree(unreadable.body()).has("code")).as(body).isFalse();
		}
		HttpResponse<String> outOfRange = operator.post(OPEN, "{\"durationMinutes\":4}", "Idempotency-Key",
				UUID.randomUUID().toString());
		assertThat(JSON.readTree(outOfRange.body()).get("errors").get(0).get("field").asString())
			.isEqualTo("durationMinutes");
		assertThat(sequence()).as("nothing sequenced").isZero();

		// Ninety minutes, on the exchange's clock. The duration is part of the request.
		String key = UUID.randomUUID().toString();
		HttpResponse<String> opened = operator.post(OPEN, "{\"durationMinutes\":90}", "Idempotency-Key", key);
		check(opened, 200);
		Instant closesAt = NOW.plus(Duration.ofMinutes(90));
		JsonNode transition = JSON.readTree(opened.body());
		assertThat(transition.get("state").asString()).isEqualTo("OPEN");
		assertThat(Instant.parse(transition.get("closesAt").asString())).isEqualTo(closesAt);
		HttpResponse<String> again = operator.post(OPEN, "{\"durationMinutes\":90}", "Idempotency-Key", key);
		check(again, 200);
		assertThat(again.body()).isEqualTo(opened.body());
		for (String other : new String[] { "{\"durationMinutes\":60}", "", "{}" }) {
			HttpResponse<String> conflict = operator.post(OPEN, other, "Idempotency-Key", key);
			check(conflict, 409);
			assertThat(JSON.readTree(conflict.body()).get("code").asString()).as(other).isEqualTo("IDEMPOTENCY_CONFLICT");
		}
		JsonNode open = market(anonymous);
		assertThat(open.get("state").asString()).isEqualTo("OPEN");
		assertThat(Instant.parse(open.get("closesAt").asString())).isEqualTo(closesAt);
		assertThat(open.get("closedOnSchedule").asBoolean()).isFalse();

		// No body and an empty one are the same request: a second opening, refused, and its key keeps that answer.
		String second = UUID.randomUUID().toString();
		HttpResponse<String> refused = operator.post(OPEN, "", "Idempotency-Key", second);
		check(refused, 409);
		assertThat(JSON.readTree(refused.body()).get("code").asString()).isEqualTo("MARKET_TRANSITION_NOT_ALLOWED");
		HttpResponse<String> sameRefusal = operator.post(OPEN, "{}", "Idempotency-Key", second);
		check(sameRefusal, 409);
		assertThat(JSON.readTree(sameRefusal.body()).get("commandSequence").asString())
			.isEqualTo(JSON.readTree(refused.body()).get("commandSequence").asString());
		check(operator.post(OPEN, "{\"durationMinutes\":30}", "Idempotency-Key", second), 409);
		// A refused opening with a duration of its own, under a new key, sets no close time.
		HttpResponse<String> shorter = operator.post(OPEN, "{\"durationMinutes\":5}", "Idempotency-Key",
				UUID.randomUUID().toString());
		check(shorter, 409);
		assertThat(JSON.readTree(shorter.body()).get("code").asString()).isEqualTo("MARKET_TRANSITION_NOT_ALLOWED");
		assertThat(closesAtInDatabase()).isEqualTo(closesAt);
		assertThat(Instant.parse(market(anonymous).get("closesAt").asString())).isEqualTo(closesAt);

		String bid = MarketCloseUnfinishedIT.place(trader, instrument, "90.00");

		// At the time: an order is refused as for a closed market whether or not the close has run, and the close comes.
		CLOCK.set(closesAt);
		HttpResponse<String> late = trader.post("/api/v1/orders", MarketCloseUnfinishedIT.order(instrument, "91.00"),
				"Idempotency-Key", UUID.randomUUID().toString());
		check(late, "POST", "/api/v1/orders", 422);
		assertThat(JSON.readTree(late.body()).get("code").asString()).isEqualTo("MARKET_CLOSED");
		long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
		JsonNode closed = market(anonymous);
		while (!"CLOSED".equals(closed.get("state").asString())) {
			assertThat(System.nanoTime()).as("closed within twenty seconds").isLessThan(deadline);
			Thread.sleep(20);
			closed = market(anonymous);
		}
		assertThat(Instant.parse(closed.get("closedAt").asString())).isEqualTo(closesAt);
		assertThat(Instant.parse(closed.get("closesAt").asString())).isEqualTo(closesAt);
		assertThat(closed.get("closedOnSchedule").asBoolean()).isTrue();
		assertThat(jdbc.queryForObject("SELECT state || ' ' || reason FROM orders WHERE id = ?::uuid", String.class, bid))
			.isEqualTo("CANCELED MARKET_CLOSED");
		assertThat(jdbc.queryForObject("""
				SELECT u.username FROM audit_log a JOIN app_user u ON u.id = a.actor_user_id
				WHERE a.action = 'MARKET_CLOSE_SCHEDULED' AND a.outcome = 'APPLIED'""", String.class))
			.isEqualTo("operator");

		// An operator's close after it changes nothing, and says why.
		HttpResponse<String> manual = operator.post("/api/v1/admin/market/close", "", "Idempotency-Key",
				UUID.randomUUID().toString());
		check(manual, "POST", "/api/v1/admin/market/close", 409);
		JsonNode problem = JSON.readTree(manual.body());
		assertThat(problem.get("code").asString()).isEqualTo("MARKET_TRANSITION_NOT_ALLOWED");
		assertThat(problem.get("state").asString()).isEqualTo("CLOSED");
	}

	/**
	 * A market opened for an hour and closed by hand before its time: the status keeps the time it was meant to close,
	 * and does not say it closed on schedule.
	 */
	@Test
	void aMarketClosedByHandBeforeItsTimeKeepsItsTimeAndWasNotClosedOnSchedule() throws Exception {
		new TransactionTemplate(transactionManager).execute((status) -> {
			jdbc.update("""
					UPDATE simulation SET state = 'CLOSED', opened_at = coalesce(opened_at, now()),
					    closed_at = coalesce(closed_at, now())
					WHERE state <> 'CLOSED'""");
			return marketInSetup();
		});
		engine.start();
		TestBrowser operator = TestBrowser.loggedIn(port, "operator", PASSWORD);
		TestBrowser anonymous = new TestBrowser(port);
		Instant opening = CLOCK.instant();
		HttpResponse<String> opened = operator.post(OPEN, "{\"durationMinutes\":60}", "Idempotency-Key",
				UUID.randomUUID().toString());
		check(opened, 200);
		Instant closesAt = opening.plus(Duration.ofHours(1));
		assertThat(Instant.parse(JSON.readTree(opened.body()).get("closesAt").asString())).isEqualTo(closesAt);

		CLOCK.set(opening.plus(Duration.ofMinutes(5)));
		HttpResponse<String> closed = operator.post("/api/v1/admin/market/close", "", "Idempotency-Key",
				UUID.randomUUID().toString());
		check(closed, "POST", "/api/v1/admin/market/close", 200);
		JsonNode status = market(anonymous);
		assertThat(status.get("state").asString()).isEqualTo("CLOSED");
		assertThat(Instant.parse(status.get("closedAt").asString())).isEqualTo(opening.plus(Duration.ofMinutes(5)));
		assertThat(Instant.parse(status.get("closesAt").asString())).as("kept, as the time it was meant to close")
			.isEqualTo(closesAt);
		assertThat(status.get("closedOnSchedule").asBoolean()).as("an operator closed it").isFalse();
	}

	private JsonNode market(TestBrowser browser) throws Exception {
		HttpResponse<String> status = browser.get(MARKET);
		check(status, "GET", MARKET, 200);
		return JSON.readTree(status.body());
	}

	private static void check(HttpResponse<String> response, int status) {
		check(response, "POST", OPEN, status);
	}

	private static void check(HttpResponse<String> response, String method, String template, int status) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
		CONTRACT.assertResponse(method, template, status, response.headers().firstValue("Content-Type").orElse(null),
				response.body());
	}

	/** A market in SETUP with one instrument and the development trader funded with cash; returns the instrument. */
	private UUID marketInSetup() {
		UUID simulation = UUID.randomUUID();
		UUID issuance = UUID.randomUUID();
		UUID instrument = UUID.randomUUID();
		UUID account = UUID.randomUUID();
		UUID batch = UUID.randomUUID();
		jdbc.update("INSERT INTO simulation (id, state) VALUES (?, 'SETUP')", simulation);
		jdbc.update("INSERT INTO account (id, simulation_id, kind, display_name) VALUES (?, ?, 'ISSUANCE', 'Issuance')",
				issuance, simulation);
		jdbc.update("INSERT INTO instrument (id, simulation_id, symbol, reference_price_cents) VALUES (?, ?, 'TIME', 10000)",
				instrument, simulation);
		jdbc.update("""
				INSERT INTO account (id, simulation_id, kind, display_name, owner_user_id)
				SELECT ?, ?, 'TRADER', 'trader', id FROM app_user WHERE username = 'trader'""", account, simulation);
		jdbc.update("INSERT INTO journal_batch (id, simulation_id, cause, command_sequence) VALUES (?, ?, 'GENESIS', 1)",
				batch, simulation);
		String post = "INSERT INTO ledger_entry (batch_id, account_id, instrument_id, bucket, delta) VALUES (?, ?, ?, 'AVAILABLE', ?)";
		jdbc.update(post, batch, issuance, null, -1_000_000);
		jdbc.update(post, batch, account, null, 1_000_000);
		jdbc.update(
				"INSERT INTO cash_balance (account_id, account_kind, total_cents, reserved_cents) VALUES (?, 'TRADER', 1000000, 0)",
				account);
		return instrument;
	}

	/** The current simulation's last sequence: each test sets up a market of its own beside the closed ones. */
	private long sequence() {
		return jdbc.queryForObject("SELECT last_command_sequence FROM simulation " + CurrentSimulation.FIRST, Long.class);
	}

	private Instant closesAtInDatabase() {
		return jdbc.queryForObject("SELECT closes_at FROM simulation " + CurrentSimulation.FIRST, Timestamp.class)
			.toInstant();
	}

}
