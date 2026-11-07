package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

import com.mia_princz.mse.execution.identity.ApiTokens;
import com.mia_princz.mse.execution.trading.application.TradingEngine;

/**
 * A bot account may send two trading commands a second, at the default setting. Each test fires a burst of commands at
 * the same moment, so the first two pass and the rest are refused before they reach the writer. A slow machine may
 * spread a burst over a second, so up to four may pass. Each test uses its own bot.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=bot-rate-limit", "mse.dev-users.trader-password=" + BotRateLimitIT.PASSWORD,
				"mse.dev-users.operator-password=" + BotRateLimitIT.PASSWORD })
class BotRateLimitIT {

	static final String PASSWORD = "test-bot-rate-password";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final OpenApiContract CONTRACT = OpenApiContract.get();

	private static final int BURST = 10;

	private static final List<ApiTokens.Issued> TOKENS = List.of(ApiTokens.issue(), ApiTokens.issue(),
			ApiTokens.issue(), ApiTokens.issue());

	private static UUID instrument;

	private static final List<UUID> BOTS = new ArrayList<>();

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

	@BeforeEach
	void market() {
		synchronized (BotRateLimitIT.class) {
			if (instrument == null) {
				new TransactionTemplate(transactionManager).executeWithoutResult((status) -> setUp());
				engine.start();
			}
		}
	}

	@Test
	void aBurstOfOrdersPassesTwoAndRefusesTheRestUncommitted() throws Exception {
		String bearer = "Bearer " + TOKENS.get(0).token();
		double counted = refusals();
		List<HttpResponse<String>> answers = burst((client, i) -> client.post("/api/v1/orders", order(),
				"Authorization", bearer, "Idempotency-Key", UUID.randomUUID().toString()));

		List<HttpResponse<String>> refused = answers.stream().filter((answer) -> answer.statusCode() == 429).toList();
		long placed = answers.stream().filter((answer) -> answer.statusCode() == 201).count();
		assertThat(placed + refused.size()).as(statuses(answers)).isEqualTo(BURST);
		assertThat(placed).as(statuses(answers)).isBetween(2L, 4L);
		for (HttpResponse<String> answer : refused) {
			assertThat(JSON.readTree(answer.body()).get("code").asString()).isEqualTo("RATE_LIMITED");
			assertThat(answer.headers().firstValue("Retry-After")).hasValue("1");
			CONTRACT.assertResponse("POST", "/api/v1/orders", 429,
					answer.headers().firstValue("Content-Type").orElse(null), answer.body());
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE account_id = ?", Long.class, BOTS.get(0)))
			.as("refused orders were never queued")
			.isEqualTo(placed);
		assertThat(refusals() - counted).isEqualTo(refused.size());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM command_result WHERE principal_id = ?", Long.class,
				BOTS.get(0)))
			.isEqualTo(placed);

		// After Retry-After the bot sends a refused command again under its key, and it goes through.
		Thread.sleep(1_000);
		UUID refusedKey = refusedKey(refused);
		HttpResponse<String> retried = new TestBrowser(port).post("/api/v1/orders", order(), "Authorization", bearer,
				"Idempotency-Key", refusedKey.toString());
		assertThat(retried.statusCode()).as(retried.body()).isEqualTo(201);
	}

	@Test
	void cancelsCountToo() throws Exception {
		String bearer = "Bearer " + TOKENS.get(1).token();
		List<HttpResponse<String>> answers = burst((client, i) -> client.post(
				"/api/v1/orders/" + UUID.randomUUID() + "/cancel", "", "Authorization", bearer, "Idempotency-Key",
				UUID.randomUUID().toString()));

		long refused = answers.stream().filter((answer) -> answer.statusCode() == 429).count();
		assertThat(answers.stream().filter((answer) -> answer.statusCode() == 404).count() + refused)
			.as(statuses(answers))
			.isEqualTo(BURST);
		assertThat(refused).as(statuses(answers)).isBetween((long) BURST - 4, (long) BURST - 2);
		HttpResponse<String> one = answers.stream().filter((answer) -> answer.statusCode() == 429).findFirst().get();
		CONTRACT.assertResponse("POST", "/api/v1/orders/{orderId}/cancel", 429,
				one.headers().firstValue("Content-Type").orElse(null), one.body());
	}

	/** One bot at its limit holds back neither another bot nor a person. */
	@Test
	void theLimitIsPerBotAccountAndPeopleDoNotShareIt() throws Exception {
		String busy = "Bearer " + TOKENS.get(2).token();
		List<HttpResponse<String>> answers = burst((client, i) -> client.post("/api/v1/orders", order(),
				"Authorization", busy, "Idempotency-Key", UUID.randomUUID().toString()));
		assertThat(answers.stream().filter((answer) -> answer.statusCode() == 429).count()).isPositive();

		String quiet = "Bearer " + TOKENS.get(3).token();
		for (int i = 0; i < 2; i++) {
			HttpResponse<String> placed = new TestBrowser(port).post("/api/v1/orders", order(), "Authorization", quiet,
					"Idempotency-Key", UUID.randomUUID().toString());
			assertThat(placed.statusCode()).as(placed.body()).isEqualTo(201);
		}

		TestBrowser trader = TestBrowser.loggedIn(port, "trader", PASSWORD);
		List<HttpResponse<String>> human = burst((client, i) -> trader.post("/api/v1/orders", order(),
				"Idempotency-Key", UUID.randomUUID().toString()));
		assertThat(human).as(statuses(human)).allMatch((answer) -> answer.statusCode() == 201);
	}

	private double refusals() {
		return meters.get("mse.commands.rate.limited").counter().count();
	}

	interface Call {

		HttpResponse<String> send(TestBrowser client, int index) throws Exception;

	}

	/** Sends {@link #BURST} calls at once, each from its own client and thread. */
	private List<HttpResponse<String>> burst(Call call) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		List<Future<HttpResponse<String>>> futures = new ArrayList<>();
		try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < BURST; i++) {
				int index = i;
				futures.add(threads.submit(() -> {
					start.await();
					return call.send(new TestBrowser(port), index);
				}));
			}
			start.countDown();
			List<HttpResponse<String>> answers = new ArrayList<>();
			for (Future<HttpResponse<String>> future : futures) {
				answers.add(future.get());
			}
			return answers;
		}
	}

	/** A refused request's key, read back from the request itself. */
	private static UUID refusedKey(List<HttpResponse<String>> refused) {
		return UUID.fromString(refused.getFirst().request().headers().firstValue("Idempotency-Key").orElseThrow());
	}

	private static String statuses(List<HttpResponse<String>> answers) {
		return answers.stream().map((answer) -> Integer.toString(answer.statusCode())).toList().toString();
	}

	private static String order() {
		return "{\"instrumentId\":\"" + instrument
				+ "\",\"side\":\"BUY\",\"type\":\"LIMIT\",\"timeInForce\":\"GTC\",\"quantity\":1,\"limitPrice\":\"90.00\"}";
	}

	private void setUp() {
		UUID simulation = UUID.randomUUID();
		jdbc.update("INSERT INTO simulation (id, state, opened_at) VALUES (?, 'OPEN', now())", simulation);
		UUID issuance = UUID.randomUUID();
		jdbc.update("INSERT INTO account (id, simulation_id, kind, display_name) VALUES (?, ?, 'ISSUANCE', 'Issuance')",
				issuance, simulation);
		instrument = UUID.randomUUID();
		jdbc.update("INSERT INTO instrument (id, simulation_id, symbol, reference_price_cents) VALUES (?, ?, 'RATE', 10000)",
				instrument, simulation);
		for (int i = 0; i < TOKENS.size(); i++) {
			UUID bot = UUID.randomUUID();
			BOTS.add(bot);
			jdbc.update("INSERT INTO account (id, simulation_id, kind, display_name) VALUES (?, ?, 'BOT', ?)", bot,
					simulation, "bot-000" + (i + 1));
			fund(simulation, issuance, bot, "BOT");
			jdbc.update("""
					INSERT INTO api_token (id, account_id, account_kind, token_hash, scope, revoked_at)
					VALUES (?, ?, 'BOT', ?, 'TRADE', NULL)""", UUID.randomUUID(), bot, TOKENS.get(i).hash());
		}
		UUID trader = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO account (id, simulation_id, kind, display_name, owner_user_id)
				SELECT ?, ?, 'TRADER', 'trader', id FROM app_user WHERE username = 'trader'""", trader, simulation);
		fund(simulation, issuance, trader, "TRADER");
	}

	/** 10,000.00 and 100 shares from one genesis batch. */
	private void fund(UUID simulation, UUID issuance, UUID account, String kind) {
		UUID batch = UUID.randomUUID();
		jdbc.update("INSERT INTO journal_batch (id, simulation_id, cause, command_sequence) VALUES (?, ?, 'GENESIS', 1)",
				batch, simulation);
		String post = "INSERT INTO ledger_entry (batch_id, account_id, instrument_id, bucket, delta) VALUES (?, ?, ?, 'AVAILABLE', ?)";
		jdbc.update(post, batch, issuance, null, -1_000_000);
		jdbc.update(post, batch, account, null, 1_000_000);
		jdbc.update(post, batch, issuance, instrument, -100);
		jdbc.update(post, batch, account, instrument, 100);
		jdbc.update("""
				INSERT INTO cash_balance (account_id, account_kind, total_cents, reserved_cents)
				VALUES (?, ?, 1000000, 0)""", account, kind);
		jdbc.update("""
				INSERT INTO position (account_id, account_kind, instrument_id, total_quantity, reserved_quantity)
				VALUES (?, ?, ?, 100, 0)""", account, kind, instrument);
	}

}
