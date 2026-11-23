package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.mia_princz.mse.execution.trading.application.TradingEngine;
import com.mia_princz.mse.execution.trading.application.TradingStore;

/**
 * A close that halts the writer after its request has stopped waiting, over real HTTP. Nobody is listening for the
 * close's answer by then, so the halt must be in the log by the writer's own doing, and the key, sent again as the
 * 504 says, must be told what became of the close. The writer halts, so the class has one test and a database of its
 * own, like {@link MarketCloseUnfinishedIT}, whose market and orders it uses.
 * <p>
 * The request waits half a second here. The close is held in its third transaction by the lock on that transaction's
 * order, which the test takes by ending the order in a transaction it leaves open. Once the request has given up
 * the test commits, the close finds the order ended behind its back, and the writer halts.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=market-close-unanswered", "mse.market.close-chunk-orders=1",
				"mse.market.close-timeout=500ms",
				"mse.dev-users.trader-password=" + MarketCloseUnansweredIT.PASSWORD,
				"mse.dev-users.operator-password=" + MarketCloseUnansweredIT.PASSWORD,
				"mse.trading.human-commands-per-second=1000" })
@ExtendWith(OutputCaptureExtension.class)
class MarketCloseUnansweredIT {

	static final String PASSWORD = "test-close-unanswered-password";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final OpenApiContract CONTRACT = OpenApiContract.get();

	private static final String CLOSE = "/api/v1/admin/market/close";

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	DataSource dataSource;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	TradingEngine engine;

	@Test
	void aCloseThatHaltsAfterItsRequestGaveUpIsOnRecordAndItsKeyIsToldWhatHappened(CapturedOutput output)
			throws Exception {
		UUID instrument = new TransactionTemplate(transactionManager)
			.execute((status) -> MarketCloseUnfinishedIT.market(jdbc));
		engine.start();
		TestBrowser trader = TestBrowser.loggedIn(port, "trader", PASSWORD);
		TestBrowser operator = TestBrowser.loggedIn(port, "operator", PASSWORD);
		// The close takes the best bid first, so the lowest is the order of its third transaction.
		String low = MarketCloseUnfinishedIT.place(trader, instrument, "90.00");
		MarketCloseUnfinishedIT.place(trader, instrument, "91.00");
		MarketCloseUnfinishedIT.place(trader, instrument, "92.00");
		long before = jdbc.queryForObject("SELECT last_command_sequence FROM simulation", Long.class);
		UUID key = UUID.randomUUID();

		try (Connection outside = dataSource.getConnection()) {
			outside.setAutoCommit(false);
			try (PreparedStatement end = outside.prepareStatement(MarketCloseUnfinishedIT.END_OUTSIDE)) {
				end.setString(1, low);
				assertThat(end.executeUpdate()).isEqualTo(1);
			}

			HttpResponse<String> unanswered = operator.post(CLOSE, "", "Idempotency-Key", key.toString());

			assertThat(unanswered.statusCode()).as(unanswered.body()).isEqualTo(504);
			CONTRACT.assertResponse("POST", CLOSE, 504, unanswered.headers().firstValue("Content-Type").orElse(null),
					unanswered.body());
			JsonNode problem = JSON.readTree(unanswered.body());
			assertThat(problem.get("code").asString()).isEqualTo("COMMAND_OUTCOME_UNKNOWN");
			assertThat(problem.get("commandKey").asString()).isEqualTo(key.toString());
			assertThat(problem.get("detail").asString()).as("no lookup by key serves a close")
				.contains("send the same key again")
				.doesNotContain("look it up");
			assertThat(readiness()).as("the close is still going on").isEqualTo(200);
			assertThat(output.getAll()).doesNotContain("halted the writer");
			outside.commit();
		}

		// The close goes on, finds the order ended, and halts the writer with nobody waiting for its answer.
		long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
		while (readiness() == 200 && System.nanoTime() < deadline) {
			Thread.sleep(20);
		}
		assertThat(readiness()).as("health once the writer has halted").isEqualTo(503);
		assertThat(output.getAll().lines().filter((line) -> line.contains("ERROR")))
			.as("the halt, where the search for a halted writer finds it")
			.anyMatch((line) -> line.contains("The market's close at sequence " + (before + 1)
					+ " halted the writer before it finished; resting orders left: at most 1."));
		// An out-of-step row is not tried again, so no other line has the cause, and nobody waits to log it.
		assertThat(MarketCloseSweepIT.causeLogged(output, "halted the writer before it finished"))
			.as("the halt's line, with what caused it")
			.startsWith(TradingStore.OutOfStepException.class.getName());
		assertThat(output.getAll()).as("the request was gone, so it logged nothing")
			.doesNotContain("Command " + key + " halted the writer");

		// The key, sent again as the 504 said, and any other close: told what the first request never was.
		MarketCloseUnfinishedIT.assertUnfinished(operator.post(CLOSE, "", "Idempotency-Key", key.toString()), key,
				before + 1);
		UUID other = UUID.randomUUID();
		MarketCloseUnfinishedIT.assertUnfinished(operator.post(CLOSE, "", "Idempotency-Key", other.toString()), other,
				before + 1);
		HttpResponse<String> order = trader.post("/api/v1/orders", MarketCloseUnfinishedIT.order(instrument, "90.00"),
				"Idempotency-Key", UUID.randomUUID().toString());
		assertThat(order.statusCode()).isEqualTo(503);
		assertThat(JSON.readTree(order.body()).get("code").asString()).isEqualTo("TRADING_UNAVAILABLE");
		assertThat(jdbc.queryForObject("SELECT state FROM simulation", String.class)).isEqualTo("CLOSED");
		assertThat(jdbc.queryForObject("SELECT last_command_sequence FROM simulation", Long.class))
			.as("the close and its second transaction; the third rolled back")
			.isEqualTo(before + 2);
	}

	private int readiness() throws Exception {
		return new TestBrowser(port).get("/actuator/health/readiness").statusCode();
	}

}
