package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
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

import com.mia_princz.mse.execution.trading.application.TradingEngine;

/**
 * New orders stop while the publication backlog is over its limit, and cancels go on, and so does the operator's
 * close. The relay is off, so the backlog stays exactly where the test puts it, and the limit is three events, read on
 * every request.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=backlog-admission", "mse.outbox.relay.enabled=false",
				"mse.outbox.admission.max-events=3", "mse.outbox.admission.refresh=0s",
				"mse.dev-users.trader-password=" + BacklogAdmissionIT.PASSWORD,
				"mse.dev-users.operator-password=" + BacklogAdmissionIT.PASSWORD })
class BacklogAdmissionIT {

	static final String PASSWORD = "test-trader-password";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	TradingEngine engine;

	@Autowired
	MeterRegistry meters;

	/**
	 * The close comes last, since it ends the market. It is not an order: it cancels, and writes a snapshot for each
	 * instrument where the cancels it stands for would each have written one. Refusing it would leave the operator
	 * unable to end a market exactly when publication is stuck.
	 */
	@Test
	void ordersStopAtTheLimitWhileCancelsAndTheCloseGoOn() throws Exception {
		UUID instrument = new TransactionTemplate(transactionManager).execute((status) -> market());
		engine.start();
		TestBrowser trader = TestBrowser.loggedIn(port, "trader", PASSWORD);

		HttpResponse<String> resting = place(trader, instrument);
		assertThat(resting.statusCode()).as(resting.body()).isEqualTo(201);
		assertThat(pending()).as("the resting order's snapshot").isEqualTo(1);
		unpublishedEvents(2);

		HttpResponse<String> refused = place(trader, instrument);
		assertThat(refused.statusCode()).isEqualTo(503);
		assertThat(refused.headers().firstValue("Retry-After")).hasValue("5");
		JsonNode problem = JSON.readTree(refused.body());
		assertThat(problem.get("code").asString()).isEqualTo("EXCHANGE_BUSY");
		assertThat(problem.get("reason").asString()).isEqualTo("PUBLICATION_BACKLOG");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Long.class)).as("never queued").isEqualTo(1);
		assertThat(meters.get("mse.commands.refused").tag("command", "place").tag("reason", "backlog").counter().count())
			.isEqualTo(1);

		String orderId = JSON.readTree(resting.body()).get("orderId").asString();
		HttpResponse<String> cancel = trader.post("/api/v1/orders/" + orderId + "/cancel", "", "Idempotency-Key",
				UUID.randomUUID().toString());
		assertThat(cancel.statusCode()).as("cancels still work").isEqualTo(200);

		jdbc.update("UPDATE outbox SET published_at = now() WHERE published_at IS NULL");
		assertThat(place(trader, instrument).statusCode()).as("admission reopens").isEqualTo(201);

		unpublishedEvents(3);
		long pending = pending();
		assertThat(place(trader, instrument).statusCode()).as("over the limit again").isEqualTo(503);
		TestBrowser operator = TestBrowser.loggedIn(port, "operator", PASSWORD);
		HttpResponse<String> closed = operator.post("/api/v1/admin/market/close", "", "Idempotency-Key",
				UUID.randomUUID().toString());
		assertThat(closed.statusCode()).as(closed.body()).isEqualTo(200);
		assertThat(JSON.readTree(closed.body()).get("canceledOrders").asInt()).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE state IN ('OPEN', 'PARTIALLY_FILLED')",
				Long.class)).isZero();
		assertThat(pending()).as("the instrument's last snapshot joins the backlog").isEqualTo(pending + 1);
	}

	/** The limits in force are measured, so a load test can report what it ran with. */
	@Test
	void theLimitsInForceAreMeasured() {
		assertThat(meters.get("mse.outbox.admission.limit").tag("limit", "events").gauge().value()).isEqualTo(3);
		assertThat(meters.get("mse.outbox.admission.limit").tag("limit", "bytes").gauge().value())
			.as("the default, 256 MiB")
			.isEqualTo(268_435_456);
	}

	private UUID market() {
		UUID simulation = UUID.randomUUID();
		UUID issuance = UUID.randomUUID();
		UUID instrument = UUID.randomUUID();
		UUID account = UUID.randomUUID();
		UUID batch = UUID.randomUUID();
		jdbc.update("INSERT INTO simulation (id, state, opened_at) VALUES (?, 'OPEN', now())", simulation);
		jdbc.update("INSERT INTO account (id, simulation_id, kind, display_name) VALUES (?, ?, 'ISSUANCE', 'Issuance')",
				issuance, simulation);
		jdbc.update("INSERT INTO instrument (id, simulation_id, symbol, reference_price_cents) VALUES (?, ?, 'LAG', 10000)",
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

	private static HttpResponse<String> place(TestBrowser trader, UUID instrument) throws Exception {
		return trader.post("/api/v1/orders", """
				{"instrumentId":"%s","side":"BUY","type":"LIMIT","timeInForce":"GTC","quantity":1,"limitPrice":"90.00"}"""
			.formatted(instrument), "Idempotency-Key", UUID.randomUUID().toString());
	}

	private long pending() {
		return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE published_at IS NULL", Long.class);
	}

	private void unpublishedEvents(int count) {
		for (int i = 0; i < count; i++) {
			jdbc.update("""
					INSERT INTO outbox (event_id, topic, event_key, schema_fingerprint, payload, command_sequence)
					VALUES (?, 'mse.trades.v1', 'k', 1, '\\x01'::bytea, 1)""", UUID.randomUUID());
		}
	}

}
