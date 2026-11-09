package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.mia_princz.mse.contracts.events.TradeExecuted;
import com.mia_princz.mse.contracts.events.WireEvents;
import com.mia_princz.mse.execution.trading.application.TradingEngine;

/**
 * The order API over real HTTP, sessions, CSRF and PostgreSQL. The property gives this class its own context and
 * database. The market is set up once, before the app's engine starts, with one instrument per test so books never
 * interfere.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=order-api", "mse.dev-users.trader-password=" + OrderApiIT.PASSWORD,
				"mse.dev-users.operator-password=" + OrderApiIT.OPERATOR_PASSWORD,
				// These tests share one trader and send commands back to back. AccountRateLimitIT covers pacing.
				"mse.trading.human-commands-per-second=1000" })
class OrderApiIT {

	static final String PASSWORD = "test-trader-password";

	static final String OPERATOR_PASSWORD = "test-operator-password";

	private static final String OTHER_PASSWORD = "test-other-password";

	private static final int INSTRUMENTS = 16;

	private static final long STARTING_CASH = 10_000_000;

	private static final long STARTING_SHARES = 1_000;

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final List<UUID> instruments = new ArrayList<>();

	private static final AtomicInteger nextInstrument = new AtomicInteger();

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	TradingEngine engine;

	@Autowired
	KafkaConnectionDetails kafka;

	@Autowired
	MeterRegistry meters;

	private UUID instrument;

	private TestBrowser trader;

	@BeforeEach
	void market() throws Exception {
		synchronized (instruments) {
			if (instruments.isEmpty()) {
				new TransactionTemplate(transactionManager).executeWithoutResult((status) -> setUpMarket());
				engine.start();
			}
		}
		instrument = instruments.get(nextInstrument.getAndIncrement());
		trader = TestBrowser.loggedIn(port, "trader", PASSWORD);
	}

	/** Each command is timed from admission to its result, and the writer's queue is measured. */
	@Test
	void commandsAreTimedAndTheQueueMeasured() throws Exception {
		long places = committed("place");
		long cancels = committed("cancel");
		String orderId = orderId(place(trader, UUID.randomUUID(), limit("BUY", 1, "90.00")));
		assertThat(cancel(trader, UUID.randomUUID(), orderId).statusCode()).isEqualTo(200);

		// The timer records when the result completes, which can be a moment after the caller is released.
		long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		while ((committed("place") < places + 1 || committed("cancel") < cancels + 1) && System.nanoTime() < deadline) {
			Thread.sleep(10);
		}
		assertThat(committed("place")).isEqualTo(places + 1);
		assertThat(committed("cancel")).isEqualTo(cancels + 1);
		// Prometheus export is off in tests; the gate reads the real scrape and its bucket edges.
		assertThat(meters.get("mse.writer.queue.depth").gauge().value()).isZero();
		assertThat(meters.get("mse.writer.queue.capacity").gauge().value()).isEqualTo(256);
	}

	@Test
	void placedOrderCanBeReadBack() throws Exception {
		HttpResponse<String> placed = place(trader, UUID.randomUUID(), limit("BUY", 5, "99.50"));

		assertThat(placed.statusCode()).isEqualTo(201);
		JsonNode order = JSON.readTree(placed.body());
		assertThat(order.get("state").asString()).isEqualTo("OPEN");
		assertThat(order.get("workingQuantity").asLong()).isEqualTo(5);
		assertThat(order.get("commandSequence").isString()).as("sequences travel as strings").isTrue();

		HttpResponse<String> read = trader.get("/api/v1/orders/" + order.get("orderId").asString());
		assertThat(read.statusCode()).isEqualTo(200);
		JsonNode view = JSON.readTree(read.body());
		assertThat(view.get("instrumentId").asString()).isEqualTo(instrument.toString());
		assertThat(view.get("type").asString()).isEqualTo("LIMIT");
		assertThat(view.get("timeInForce").asString()).isEqualTo("GTC");
		assertThat(view.get("limitPrice").asString()).isEqualTo("99.50");
		assertThat(view.get("protectionPrice").isNull()).isTrue();
		assertThat(view.get("requestedQuantity").asLong()).isEqualTo(5);
		assertThat(view.get("state").asString()).isEqualTo("OPEN");
	}

	@Test
	void replayReturnsTheFirstAnswerAndPlacesOnce() throws Exception {
		UUID key = UUID.randomUUID();
		HttpResponse<String> first = place(trader, key, limit("BUY", 2, "98.00"));
		// The same request with its fields in another order.
		HttpResponse<String> replay = place(trader, key, """
				{"limitPrice":"98.00","quantity":2,"timeInForce":"GTC","type":"LIMIT","side":"BUY","instrumentId":"%s"}"""
			.formatted(instrument));
		HttpResponse<String> lookup = trader.get("/api/v1/commands/" + key);

		assertThat(first.statusCode()).isEqualTo(201);
		assertThat(replay.statusCode()).isEqualTo(201);
		assertThat(replay.body()).isEqualTo(first.body());
		assertThat(lookup.statusCode()).isEqualTo(201);
		assertThat(lookup.body()).isEqualTo(first.body());
		assertThat(ordersOnInstrument()).isEqualTo(1);
	}

	@Test
	void reusedKeyWithADifferentRequestConflicts() throws Exception {
		UUID key = UUID.randomUUID();
		place(trader, key, limit("BUY", 2, "97.00"));

		HttpResponse<String> conflict = place(trader, key, limit("BUY", 3, "97.00"));

		assertThat(conflict.statusCode()).isEqualTo(409);
		assertThat(code(conflict)).isEqualTo("IDEMPOTENCY_CONFLICT");
		assertThat(ordersOnInstrument()).isEqualTo(1);
	}

	@Test
	void concurrentRequestsWithOneKeyPlaceOnce() throws Exception {
		UUID key = UUID.randomUUID();
		String body = limit("BUY", 1, "96.00");
		List<Callable<HttpResponse<String>>> requests = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			requests.add(() -> place(trader, key, body));
		}

		List<HttpResponse<String>> responses = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
			for (Future<HttpResponse<String>> response : pool.invokeAll(requests)) {
				responses.add(response.get());
			}
		}

		assertThat(responses).extracting(HttpResponse::statusCode).containsOnly(201);
		assertThat(responses).extracting(HttpResponse::body).containsOnly(responses.getFirst().body());
		assertThat(ordersOnInstrument()).isEqualTo(1);
	}

	@Test
	void invalidRequestsAreRejectedBeforeTheEngine() throws Exception {
		HttpResponse<String> noKey = trader.post("/api/v1/orders", limit("BUY", 1, "95.00"));
		HttpResponse<String> unknownField = place(trader, UUID.randomUUID(),
				limit("BUY", 1, "95.00").replace("}", ",\"accountId\":\"" + UUID.randomUUID() + "\"}"));
		HttpResponse<String> subCent = place(trader, UUID.randomUUID(), limit("BUY", 1, "95.005"));
		HttpResponse<String> wholeNumber = place(trader, UUID.randomUUID(), limit("BUY", 1, "95"));
		HttpResponse<String> wrongTimeInForce = place(trader, UUID.randomUUID(),
				limit("BUY", 1, "95.00").replace("GTC", "IOC"));
		HttpResponse<String> zero = place(trader, UUID.randomUUID(), limit("BUY", 0, "95.00"));

		assertThat(noKey.statusCode()).isEqualTo(400);
		assertThat(code(noKey)).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
		assertThat(unknownField.statusCode()).as("a body cannot name an account").isEqualTo(400);
		assertThat(fieldError(subCent)).isEqualTo("limitPrice INVALID_TICK");
		assertThat(fieldError(wholeNumber)).as("exactly two decimals").isEqualTo("limitPrice INVALID_PRICE");
		assertThat(fieldError(wrongTimeInForce)).isEqualTo("timeInForce INVALID_COMBINATION");
		assertThat(fieldError(zero)).isEqualTo("quantity INVALID_QUANTITY");
		assertThat(ordersOnInstrument()).isZero();
	}

	@Test
	void marketProtectedNeedsIocAndAProtectionPrice() throws Exception {
		HttpResponse<String> missing = place(trader, UUID.randomUUID(), """
				{"instrumentId":"%s","side":"BUY","type":"MARKET_PROTECTED","timeInForce":"IOC","quantity":1}"""
			.formatted(instrument));
		HttpResponse<String> filledNothing = place(trader, UUID.randomUUID(), """
				{"instrumentId":"%s","side":"BUY","type":"MARKET_PROTECTED","timeInForce":"IOC","quantity":1,
				"protectionPrice":"101.00"}""".formatted(instrument));

		assertThat(fieldError(missing)).isEqualTo("protectionPrice REQUIRED");
		assertThat(filledNothing.statusCode()).isEqualTo(201);
		assertThat(JSON.readTree(filledNothing.body()).get("state").asString()).isEqualTo("CANCELED");
	}

	@Test
	void engineRejectionIsAProblemWithTheOrderId() throws Exception {
		HttpResponse<String> rejected = place(trader, UUID.randomUUID(), limit("BUY", 1_000, "200.00"));

		assertThat(rejected.statusCode()).isEqualTo(422);
		assertThat(rejected.headers().firstValue("Content-Type")).hasValue("application/problem+json");
		JsonNode problem = JSON.readTree(rejected.body());
		assertThat(problem.get("code").asString()).isEqualTo("INSUFFICIENT_CASH");
		assertThat(jdbc.queryForObject("SELECT state FROM orders WHERE id = ?", String.class,
				UUID.fromString(problem.get("orderId").asString())))
			.isEqualTo("REJECTED");
	}

	@Test
	void unknownInstrumentIsUnprocessable() throws Exception {
		HttpResponse<String> response = place(trader, UUID.randomUUID(),
				limit("BUY", 1, "95.00").replace(instrument.toString(), UUID.randomUUID().toString()));

		assertThat(response.statusCode()).isEqualTo(422);
		assertThat(code(response)).isEqualTo("UNKNOWN_INSTRUMENT");
	}

	@Test
	void cancelReleasesRestingOrdersAndRefusesFilledOnes() throws Exception {
		String resting = orderId(place(trader, UUID.randomUUID(), limit("SELL", 3, "100.00")));

		HttpResponse<String> canceled = cancel(trader, UUID.randomUUID(), resting);
		HttpResponse<String> again = cancel(trader, UUID.randomUUID(), resting);

		assertThat(canceled.statusCode()).isEqualTo(200);
		assertThat(JSON.readTree(canceled.body()).get("status").asString()).isEqualTo("CANCELED");
		assertThat(JSON.readTree(canceled.body()).get("canceledQuantity").asLong()).isEqualTo(3);
		assertThat(again.statusCode()).isEqualTo(200);
		assertThat(JSON.readTree(again.body()).get("status").asString()).isEqualTo("ALREADY_CANCELED");

		String sold = orderId(place(trader, UUID.randomUUID(), limit("SELL", 2, "100.00")));
		HttpResponse<String> bought = place(other(), UUID.randomUUID(), limit("BUY", 2, "100.00"));
		assertThat(JSON.readTree(bought.body()).get("state").asString()).isEqualTo("FILLED");

		HttpResponse<String> tooLate = cancel(trader, UUID.randomUUID(), sold);
		assertThat(tooLate.statusCode()).isEqualTo(409);
		assertThat(code(tooLate)).isEqualTo("ORDER_NOT_CANCELABLE");
	}

	@Test
	void aTradeReachesKafkaThroughTheRelay() throws Exception {
		place(trader, UUID.randomUUID(), limit("SELL", 4, "100.00"));
		place(other(), UUID.randomUUID(), limit("BUY", 4, "100.00"));
		UUID tradeId = jdbc.queryForObject("SELECT id FROM trades WHERE instrument_id = ?", UUID.class, instrument);

		TradeExecuted published = null;
		Map<String, Object> config = Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
				String.join(",", kafka.getBootstrapServers()), ConsumerConfig.GROUP_ID_CONFIG,
				"order-api-" + UUID.randomUUID(), ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(config, new StringDeserializer(),
				new ByteArrayDeserializer())) {
			consumer.subscribe(List.of("mse.trades.v1"));
			long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
			while (published == null && System.nanoTime() < deadline) {
				for (var record : consumer.poll(Duration.ofMillis(200))) {
					TradeExecuted trade = WireEvents.decodeTrade(record.value());
					if (trade.getTradeId().equals(tradeId)) {
						assertThat(record.key()).isEqualTo(trade.getSimulationId() + "|" + instrument);
						published = trade;
					}
				}
			}
		}

		assertThat(published).as("trade on mse.trades.v1").isNotNull();
		assertThat(published.getPriceCents()).isEqualTo(10_000);
		assertThat(published.getQuantity()).isEqualTo(4);
		// The record can be read just before the relay marks it, so give the mark a moment.
		long marked = 0;
		for (int attempt = 0; attempt < 50 && marked == 0; attempt++) {
			marked = jdbc.queryForObject(
					"SELECT count(*) FROM outbox WHERE event_id = ? AND published_at IS NOT NULL", Long.class,
					published.getEventId());
			if (marked == 0) {
				Thread.sleep(100);
			}
		}
		assertThat(marked).as("outbox row marked published").isEqualTo(1);
	}

	@Test
	void anotherTradersOrdersAndCommandsLookMissing() throws Exception {
		TestBrowser other = other();
		UUID otherKey = UUID.randomUUID();
		String theirs = orderId(place(other, otherKey, limit("BUY", 1, "94.00")));

		HttpResponse<String> read = trader.get("/api/v1/orders/" + theirs);
		HttpResponse<String> cancel = cancel(trader, UUID.randomUUID(), theirs);
		HttpResponse<String> command = trader.get("/api/v1/commands/" + otherKey);

		assertThat(read.statusCode()).isEqualTo(404);
		assertThat(cancel.statusCode()).isEqualTo(404);
		assertThat(code(cancel)).isEqualTo("ORDER_NOT_FOUND");
		assertThat(command.statusCode()).isEqualTo(404);
		assertThat(other.get("/api/v1/orders/" + theirs).statusCode()).isEqualTo(200);
		assertThat(jdbc.queryForObject("SELECT state FROM orders WHERE id = ?::uuid", String.class, theirs))
			.isEqualTo("OPEN");
	}

	@Test
	void unknownCommandKeyIsNotFound() throws Exception {
		HttpResponse<String> response = trader.get("/api/v1/commands/" + UUID.randomUUID());

		assertThat(response.statusCode()).isEqualTo(404);
		assertThat(code(response)).isEqualTo("COMMAND_NOT_FOUND");
	}

	@Test
	void ordersNeedATraderSessionAndACsrfToken() throws Exception {
		TestBrowser anonymous = new TestBrowser(port);
		TestBrowser operator = TestBrowser.loggedIn(port, "operator", OPERATOR_PASSWORD);

		assertThat(anonymous.get("/api/v1/orders/" + UUID.randomUUID()).statusCode()).isEqualTo(401);
		// An empty body means the filter chain refused it; the controller would answer with a problem.
		HttpResponse<String> operatorRead = operator.get("/api/v1/commands/" + UUID.randomUUID());
		assertThat(operatorRead.statusCode()).isEqualTo(403);
		assertThat(operatorRead.body()).as("refused by the role rule").isEmpty();
		HttpResponse<String> operatorPlace = place(operator, UUID.randomUUID(), limit("BUY", 1, "93.00"));
		assertThat(operatorPlace.statusCode()).isEqualTo(403);
		assertThat(operatorPlace.body()).as("refused by the role rule").isEmpty();

		trader.csrfToken = null;
		assertThat(place(trader, UUID.randomUUID(), limit("BUY", 1, "93.00")).statusCode()).isEqualTo(403);
		assertThat(ordersOnInstrument()).isZero();
	}

	@Test
	void traderWithoutAnAccountCannotTrade() throws Exception {
		TestBrowser unfunded = TestBrowser.loggedIn(port, "unfunded", OTHER_PASSWORD);

		HttpResponse<String> response = place(unfunded, UUID.randomUUID(), limit("BUY", 1, "92.00"));

		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(code(response)).isEqualTo("NO_TRADING_ACCOUNT");
		assertThat(ordersOnInstrument()).isZero();
	}

	private void setUpMarket() {
		UUID simulation = UUID.randomUUID();
		UUID issuance = UUID.randomUUID();
		jdbc.update("INSERT INTO simulation (id, state, opened_at) VALUES (?, 'OPEN', now())", simulation);
		jdbc.update("INSERT INTO account (id, simulation_id, kind, display_name) VALUES (?, ?, 'ISSUANCE', 'Issuance')",
				issuance, simulation);
		for (int i = 0; i < INSTRUMENTS; i++) {
			UUID id = UUID.randomUUID();
			jdbc.update(
					"INSERT INTO instrument (id, simulation_id, symbol, reference_price_cents) VALUES (?, ?, ?, 10000)",
					id, simulation, "API" + (char) ('A' + i));
			instruments.add(id);
		}
		for (String username : List.of("other", "unfunded")) {
			jdbc.update("INSERT INTO app_user (id, username, password_hash, role) VALUES (?, ?, ?, 'TRADER')",
					UUID.randomUUID(), username, TestBrowser.passwordHash(OTHER_PASSWORD));
		}
		for (String username : List.of("trader", "other")) {
			fund(simulation, issuance, username);
		}
	}

	private void fund(UUID simulation, UUID issuance, String username) {
		UUID account = UUID.randomUUID();
		UUID user = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", UUID.class, username);
		jdbc.update("""
				INSERT INTO account (id, simulation_id, kind, display_name, owner_user_id)
				VALUES (?, ?, 'TRADER', ?, ?)""", account, simulation, username, user);
		UUID batch = UUID.randomUUID();
		jdbc.update("INSERT INTO journal_batch (id, simulation_id, cause, command_sequence) VALUES (?, ?, 'GENESIS', 1)",
				batch, simulation);
		String post = "INSERT INTO ledger_entry (batch_id, account_id, instrument_id, bucket, delta) VALUES (?, ?, ?, 'AVAILABLE', ?)";
		jdbc.update(post, batch, issuance, null, -STARTING_CASH);
		jdbc.update(post, batch, account, null, STARTING_CASH);
		jdbc.update(
				"INSERT INTO cash_balance (account_id, account_kind, total_cents, reserved_cents) VALUES (?, 'TRADER', ?, 0)",
				account, STARTING_CASH);
		for (UUID id : instruments) {
			jdbc.update(post, batch, issuance, id, -STARTING_SHARES);
			jdbc.update(post, batch, account, id, STARTING_SHARES);
			jdbc.update("""
					INSERT INTO position (account_id, account_kind, instrument_id, total_quantity, reserved_quantity)
					VALUES (?, 'TRADER', ?, ?, 0)""", account, id, STARTING_SHARES);
		}
	}

	private TestBrowser other() throws Exception {
		return TestBrowser.loggedIn(port, "other", OTHER_PASSWORD);
	}

	private String limit(String side, long quantity, String price) {
		return """
				{"instrumentId":"%s","side":"%s","type":"LIMIT","timeInForce":"GTC","quantity":%d,"limitPrice":"%s"}"""
			.formatted(instrument, side, quantity, price);
	}

	private long committed(String command) {
		Timer timer = meters.find("mse.commands").tag("command", command).tag("outcome", "committed").timer();
		return timer == null ? 0 : timer.count();
	}

	private static HttpResponse<String> place(TestBrowser browser, UUID key, String body) throws Exception {
		return browser.post("/api/v1/orders", body, "Idempotency-Key", key.toString());
	}

	private static HttpResponse<String> cancel(TestBrowser browser, UUID key, String orderId) throws Exception {
		return browser.post("/api/v1/orders/" + orderId + "/cancel", "", "Idempotency-Key", key.toString());
	}

	private static String orderId(HttpResponse<String> placed) {
		assertThat(placed.statusCode()).as(placed.body()).isEqualTo(201);
		return JSON.readTree(placed.body()).get("orderId").asString();
	}

	private static String code(HttpResponse<String> problem) {
		return JSON.readTree(problem.body()).get("code").asString();
	}

	private static String fieldError(HttpResponse<String> problem) {
		assertThat(problem.statusCode()).as(problem.body()).isEqualTo(400);
		JsonNode errors = JSON.readTree(problem.body()).get("errors");
		assertThat(errors.size()).as(problem.body()).isEqualTo(1);
		return errors.get(0).get("field").asString() + " " + errors.get(0).get("code").asString();
	}

	private long ordersOnInstrument() {
		return jdbc.queryForObject("SELECT count(*) FROM orders WHERE instrument_id = ?", Long.class, instrument);
	}

}
