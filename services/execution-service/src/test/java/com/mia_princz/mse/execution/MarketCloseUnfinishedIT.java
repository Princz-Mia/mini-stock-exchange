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
 * finishes it. {@link MarketCloseUnansweredIT} has the close whose request stops waiting before the halt.
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
		UUID instrument = new TransactionTemplate(transactionManager).execute((status) -> market(jdbc));
		engine.start();
		TestBrowser trader = TestBrowser.loggedIn(port, "trader", PASSWORD);
		TestBrowser operator = TestBrowser.loggedIn(port, "operator", PASSWORD);
		String low = place(trader, instrument, "90.00");
		String middle = place(trader, instrument, "91.00");
		String high = place(trader, instrument, "92.00");
		long before = sequence();
		assertThat(new TestBrowser(port).get("/actuator/health/readiness").statusCode()).isEqualTo(200);
		// The close takes the best bid first, so this is the order of its third transaction.
		jdbc.update(END_OUTSIDE, low);

		UUID key = UUID.randomUUID();
		HttpResponse<String> closed = operator.post(CLOSE, "", "Idempotency-Key", key.toString());

		assertUnfinished(closed, key, before + 1);

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

		// Trading has stopped, and the application says so until it is restarted. A close is told why again, under
		// the same key or another; an order only that trading is unavailable, to be tried again later.
		assertThat(new TestBrowser(port).get("/actuator/health/readiness").statusCode()).isEqualTo(503);
		assertUnfinished(operator.post(CLOSE, "", "Idempotency-Key", key.toString()), key, before + 1);
		UUID other = UUID.randomUUID();
		assertUnfinished(operator.post(CLOSE, "", "Idempotency-Key", other.toString()), other, before + 1);
		HttpResponse<String> order = trader.post("/api/v1/orders", order(instrument, "90.00"), "Idempotency-Key",
				UUID.randomUUID().toString());
		assertThat(order.statusCode()).isEqualTo(503);
		assertThat(JSON.readTree(order.body()).get("code").asString()).isEqualTo("TRADING_UNAVAILABLE");
		assertThat(order.headers().firstValue("Retry-After")).isPresent();
		assertThat(sequence()).isEqualTo(before + 2);
	}

	/** Ends an order behind the exchange's back, so that its books still hold it. */
	static final String END_OUTSIDE = """
			UPDATE orders SET state = 'CANCELED', reason = 'OUTSIDE', canceled_quantity = working_quantity,
			    working_quantity = 0
			WHERE id = ?::uuid""";

	/**
	 * The answer for a close of this class's market that left its last order: the market is closed, by the close at
	 * {@code sequence}, with at most one order resting. It says nothing is to be sent again, so it has no Retry-After.
	 */
	static void assertUnfinished(HttpResponse<String> answer, UUID key, long sequence) {
		assertThat(answer.statusCode()).as(answer.body()).isEqualTo(503);
		assertThat(answer.headers().firstValue("Retry-After")).as("nothing is to be sent again").isEmpty();
		CONTRACT.assertResponse("POST", CLOSE, 503, answer.headers().firstValue("Content-Type").orElse(null),
				answer.body());
		JsonNode problem = JSON.readTree(answer.body());
		assertThat(problem.get("code").asString()).isEqualTo("MARKET_CLOSE_UNFINISHED");
		assertThat(problem.get("state").asString()).isEqualTo("CLOSED");
		assertThat(problem.get("commandSequence").asString()).as("the close's own").isEqualTo(Long.toString(sequence));
		assertThat(problem.get("restingOrders").asInt()).isEqualTo(1);
		assertThat(problem.get("commandKey").asString()).isEqualTo(key.toString());
		assertThat(problem.get("detail").asString())
			.contains("The market is closed", "at most 1 order still rests.", "nothing is to be sent again", "restarts")
			.doesNotContain("nothing was applied");
	}

	/** An open market with one instrument and the development trader funded with cash; returns the instrument. */
	static UUID market(JdbcTemplate jdbc) {
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
	static String place(TestBrowser trader, UUID instrument, String price) throws Exception {
		HttpResponse<String> placed = trader.post("/api/v1/orders", order(instrument, price), "Idempotency-Key",
				UUID.randomUUID().toString());
		assertThat(placed.statusCode()).as(placed.body()).isEqualTo(201);
		return JSON.readTree(placed.body()).get("orderId").asString();
	}

	static String order(UUID instrument, String price) {
		return """
				{"instrumentId":"%s","side":"BUY","type":"LIMIT","timeInForce":"GTC","quantity":1,"limitPrice":"%s"}"""
			.formatted(instrument, price);
	}

	private long sequence() {
		return jdbc.queryForObject("SELECT last_command_sequence FROM simulation", Long.class);
	}

}
