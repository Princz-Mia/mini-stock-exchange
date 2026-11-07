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
 * At the default settings a person's account may send ten trading commands a second and a bot's two. Each test fires
 * a burst of commands from one fresh account at the same moment: at least a second's worth pass, and the rest are
 * refused before they reach the writer. The upper bound adds what the limit refills while the burst is in flight, so
 * a slow machine cannot make the test fail.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=account-rate-limit",
				"mse.dev-users.trader-password=" + AccountRateLimitIT.PASSWORD,
				"mse.dev-users.operator-password=" + AccountRateLimitIT.PASSWORD })
class AccountRateLimitIT {

	static final String PASSWORD = "test-rate-limit-password";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final OpenApiContract CONTRACT = OpenApiContract.get();

	private static final int BOT_RATE = 2;

	private static final int HUMAN_RATE = 10;

	private static final List<ApiTokens.Issued> TOKENS = List.of(ApiTokens.issue(), ApiTokens.issue(),
			ApiTokens.issue(), ApiTokens.issue());

	private static UUID instrument;

	private static final List<UUID> BOTS = new ArrayList<>();

	private static UUID trader;

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
		synchronized (AccountRateLimitIT.class) {
			if (instrument == null) {
				new TransactionTemplate(transactionManager).executeWithoutResult((status) -> setUp());
				engine.start();
			}
		}
	}

	@Test
	void aBotsBurstOfOrdersPassesTwoAndRefusesTheRestUncommitted() throws Exception {
		String bearer = "Bearer " + TOKENS.get(0).token();
		refusedUncommitted(BOTS.get(0), BOT_RATE, "bot", 10, (client, key) -> client.post("/api/v1/orders", order(),
				"Authorization", bearer, "Idempotency-Key", key));
	}

	@Test
	void aPersonsBurstOfOrdersPassesTenAndRefusesTheRestUncommitted() throws Exception {
		TestBrowser browser = TestBrowser.loggedIn(port, "trader", PASSWORD);
		// Twenty: with at least one refused, the open orders, retry included, stay within the per-account cap of 20.
		refusedUncommitted(trader, HUMAN_RATE, "human", 20,
				(client, key) -> browser.post("/api/v1/orders", order(), "Idempotency-Key", key));
	}

	@Test
	void cancelsCountToo() throws Exception {
		String bearer = "Bearer " + TOKENS.get(1).token();
		Burst burst = burst(10, (client, key) -> client.post("/api/v1/orders/" + UUID.randomUUID() + "/cancel", "",
				"Authorization", bearer, "Idempotency-Key", key));

		long refused = burst.count(429);
		assertThat(burst.count(404) + refused).as(burst.statuses()).isEqualTo(10);
		assertThat(10 - refused).as(burst.statuses()).isBetween((long) BOT_RATE, burst.mostPassing(BOT_RATE));
		HttpResponse<String> one = burst.with(429).getFirst();
		CONTRACT.assertResponse("POST", "/api/v1/orders/{orderId}/cancel", 429,
				one.headers().firstValue("Content-Type").orElse(null), one.body());
	}

	/** One account at its limit holds back neither another bot nor a person. */
	@Test
	void eachAccountHasItsOwnLimit() throws Exception {
		String busy = "Bearer " + TOKENS.get(2).token();
		Burst exhausted = burst(10, (client, key) -> client.post("/api/v1/orders", order(), "Authorization", busy,
				"Idempotency-Key", key));
		assertThat(exhausted.count(429)).as(exhausted.statuses()).isPositive();

		String quiet = "Bearer " + TOKENS.get(3).token();
		Burst bot = burst(BOT_RATE, (client, key) -> client.post("/api/v1/orders", order(), "Authorization", quiet,
				"Idempotency-Key", key));
		assertThat(bot.count(201)).as(bot.statuses()).isEqualTo(BOT_RATE);

		TestBrowser other = TestBrowser.loggedIn(port, "other", PASSWORD);
		Burst person = burst(HUMAN_RATE,
				(client, key) -> other.post("/api/v1/orders", order(), "Idempotency-Key", key));
		assertThat(person.count(201)).as(person.statuses()).isEqualTo(HUMAN_RATE);
	}

	/**
	 * A burst from one fresh account: a second's worth or a little more passes; the rest are 429s that match the
	 * contract, are counted, and left no order or command result. After Retry-After a refused key goes through.
	 */
	private void refusedUncommitted(UUID account, int rate, String kind, int size, Call call) throws Exception {
		double counted = refusals(kind);
		Burst burst = burst(size, call);

		List<HttpResponse<String>> refused = burst.with(429);
		long placed = burst.count(201);
		assertThat(placed + refused.size()).as(burst.statuses()).isEqualTo(size);
		assertThat(placed).as(burst.statuses()).isBetween((long) rate, burst.mostPassing(rate));
		for (HttpResponse<String> answer : refused) {
			assertThat(JSON.readTree(answer.body()).get("code").asString()).isEqualTo("RATE_LIMITED");
			assertThat(answer.headers().firstValue("Retry-After")).hasValue("1");
			CONTRACT.assertResponse("POST", "/api/v1/orders", 429,
					answer.headers().firstValue("Content-Type").orElse(null), answer.body());
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE account_id = ?", Long.class, account))
			.as("refused orders were never queued")
			.isEqualTo(placed);
		assertThat(refusals(kind) - counted).isEqualTo(refused.size());

		Thread.sleep(1_000);
		String refusedKey = refused.getFirst().request().headers().firstValue("Idempotency-Key").orElseThrow();
		HttpResponse<String> retried = call.send(new TestBrowser(port), refusedKey);
		assertThat(retried.statusCode()).as(retried.body()).isEqualTo(201);
	}

	private double refusals(String kind) {
		return meters.get("mse.commands.rate.limited").tag("account", kind).counter().count();
	}

	/** One command under the given idempotency key. */
	interface Call {

		HttpResponse<String> send(TestBrowser client, String key) throws Exception;

	}

	record Burst(List<HttpResponse<String>> answers, long nanos) {

		long count(int status) {
			return with(status).size();
		}

		List<HttpResponse<String>> with(int status) {
			return answers.stream().filter((answer) -> answer.statusCode() == status).toList();
		}

		/** A second's worth, plus what refilled while the burst was in flight, plus one for rounding. */
		long mostPassing(int rate) {
			return rate + nanos * rate / 1_000_000_000L + 1;
		}

		String statuses() {
			return answers.stream().map((answer) -> Integer.toString(answer.statusCode())).toList() + " in "
					+ nanos / 1_000_000 + " ms";
		}

	}

	/** Sends the calls at once, each from its own client and thread, and times them from release to last answer. */
	private Burst burst(int size, Call call) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		List<Future<HttpResponse<String>>> futures = new ArrayList<>();
		try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < size; i++) {
				futures.add(threads.submit(() -> {
					start.await();
					return call.send(new TestBrowser(port), UUID.randomUUID().toString());
				}));
			}
			long began = System.nanoTime();
			start.countDown();
			List<HttpResponse<String>> answers = new ArrayList<>();
			for (Future<HttpResponse<String>> future : futures) {
				answers.add(future.get());
			}
			return new Burst(answers, System.nanoTime() - began);
		}
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
		jdbc.update("INSERT INTO app_user (id, username, password_hash, role) VALUES (?, 'other', ?, 'TRADER')",
				UUID.randomUUID(), "{noop}" + PASSWORD);
		for (String username : List.of("trader", "other")) {
			UUID account = UUID.randomUUID();
			jdbc.update("""
					INSERT INTO account (id, simulation_id, kind, display_name, owner_user_id)
					SELECT ?, ?, 'TRADER', ?, id FROM app_user WHERE username = ?""", account, simulation, username,
					username);
			fund(simulation, issuance, account, "TRADER");
			if (username.equals("trader")) {
				trader = account;
			}
		}
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
