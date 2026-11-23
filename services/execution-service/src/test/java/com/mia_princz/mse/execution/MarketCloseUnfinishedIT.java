package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
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

import com.mia_princz.mse.execution.trading.application.TradingEngine;

/**
 * What the operator is told when a close commits and then cannot cancel every resting order, over real HTTP. The
 * market is closed by then, so the answer must not be the one for a command that failed and applied nothing. The
 * writer halts for it, which ends this application's trading: the class has one test and a database of its own.
 * <p>
 * A close here cancels one order per transaction. What stops it is an order ended behind the exchange's back, which
 * the books still hold; {@link MarketCloseSweepIT} has the other ways a close can be stopped, and the start that
 * finishes it.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=market-close-unfinished", "mse.market.close-chunk-orders=1",
				"mse.dev-users.trader-password=" + MarketCloseUnfinishedIT.PASSWORD,
				"mse.dev-users.operator-password=" + MarketCloseUnfinishedIT.PASSWORD,
				"mse.trading.human-commands-per-second=1000" })
class MarketCloseUnfinishedIT {

	static final String PASSWORD = "test-close-unfinished-password";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final OpenApiContract CONTRACT = OpenApiContract.get();

	private static final String CLOSE = "/api/v1/admin/market/close";

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	TradingEngine engine;

	@Test
	void aCloseThatCannotCancelEveryOrderSaysTheMarketIsClosedAndTradingStops() throws Exception {
		UUID instrument = new TransactionTemplate(transactionManager).execute((status) -> market());
		engine.start();
		TestBrowser trader = TestBrowser.loggedIn(port, "trader", PASSWORD);
		TestBrowser operator = TestBrowser.loggedIn(port, "operator", PASSWORD);
		String low = place(trader, instrument, "90.00");
		String middle = place(trader, instrument, "91.00");
		String high = place(trader, instrument, "92.00");
		long before = sequence();
		assertThat(new TestBrowser(port).get("/actuator/health/readiness").statusCode()).isEqualTo(200);
		// The close takes the best bid first, so this is the order of its third transaction.
		jdbc.update("""
				UPDATE orders SET state = 'CANCELED', reason = 'OUTSIDE', canceled_quantity = working_quantity,
				    working_quantity = 0
				WHERE id = ?::uuid""", low);

		UUID key = UUID.randomUUID();
		HttpResponse<String> closed = operator.post(CLOSE, "", "Idempotency-Key", key.toString());

		assertThat(closed.statusCode()).as(closed.body()).isEqualTo(503);
		assertThat(closed.headers().firstValue("Retry-After")).isPresent();
		CONTRACT.assertResponse("POST", CLOSE, 503, closed.headers().firstValue("Content-Type").orElse(null),
				closed.body());
		JsonNode problem = JSON.readTree(closed.body());
		assertThat(problem.get("code").asString()).isEqualTo("MARKET_CLOSE_UNFINISHED");
		assertThat(problem.get("state").asString()).isEqualTo("CLOSED");
		assertThat(problem.get("commandSequence").asString()).as("the close's own")
			.isEqualTo(Long.toString(before + 1));
		assertThat(problem.get("restingOrders").asInt()).isEqualTo(1);
		assertThat(problem.get("commandKey").asString()).isEqualTo(key.toString());
		assertThat(problem.get("detail").asString()).contains("The market is closed", "1 orders still rest", "restarts")
			.doesNotContain("nothing was applied");

		// What it says is so: closed, with the first two orders cancelled by the close and its second transaction.
		JsonNode market = JSON.readTree(new TestBrowser(port).get("/api/v1/market").body());
		assertThat(market.get("state").asString()).isEqualTo("CLOSED");
		assertThat(market.get("commandSequence").asString()).isEqualTo(Long.toString(before + 2));
		for (String order : new String[] { high, middle }) {
			assertThat(jdbc.queryForObject("SELECT state || ' ' || reason FROM orders WHERE id = ?::uuid", String.class,
					order)).isEqualTo("CANCELED MARKET_CLOSED");
		}
		assertThat(jdbc.queryForObject("SELECT action || ' ' || outcome FROM audit_log WHERE correlation_id = ?",
				String.class, key)).isEqualTo("MARKET_CLOSE APPLIED");

		// Trading has stopped, and the application says so until it is restarted.
		assertThat(new TestBrowser(port).get("/actuator/health/readiness").statusCode()).isEqualTo(503);
		HttpResponse<String> again = operator.post(CLOSE, "", "Idempotency-Key", key.toString());
		assertThat(again.statusCode()).isEqualTo(503);
		assertThat(JSON.readTree(again.body()).get("code").asString()).isEqualTo("TRADING_UNAVAILABLE");
		HttpResponse<String> order = trader.post("/api/v1/orders", order(instrument, "90.00"), "Idempotency-Key",
				UUID.randomUUID().toString());
		assertThat(order.statusCode()).isEqualTo(503);
		assertThat(sequence()).isEqualTo(before + 2);
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
		jdbc.update("INSERT INTO instrument (id, simulation_id, symbol, reference_price_cents) VALUES (?, ?, 'HALF', 10000)",
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

	/** Places a resting bid and returns its ID. */
	private static String place(TestBrowser trader, UUID instrument, String price) throws Exception {
		HttpResponse<String> placed = trader.post("/api/v1/orders", order(instrument, price), "Idempotency-Key",
				UUID.randomUUID().toString());
		assertThat(placed.statusCode()).as(placed.body()).isEqualTo(201);
		return JSON.readTree(placed.body()).get("orderId").asString();
	}

	private static String order(UUID instrument, String price) {
		return """
				{"instrumentId":"%s","side":"BUY","type":"LIMIT","timeInForce":"GTC","quantity":1,"limitPrice":"%s"}"""
			.formatted(instrument, price);
	}

	private long sequence() {
		return jdbc.queryForObject("SELECT last_command_sequence FROM simulation", Long.class);
	}

}
