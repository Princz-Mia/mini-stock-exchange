package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
 * The private account stream over real HTTP: each trader hears only their own account's changes, a trade tells both
 * sides, and a stream ends when its session does, by logging out or by expiring. Heartbeats run every five seconds
 * here: long enough that a stream ending within a moment of logging out was closed by the logout itself.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=account-events", "mse.dev-users.trader-password=" + AccountEventsIT.PASSWORD,
				"mse.dev-users.operator-password=" + AccountEventsIT.PASSWORD, "mse.account-events.heartbeat=5s" })
class AccountEventsIT {

	static final String PASSWORD = "test-account-events-password";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final ApiTokens.Issued BOT_TOKEN = ApiTokens.issue();

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	TradingEngine engine;

	private record Event(String name, String data) {
	}

	/** One open stream, its events queued as they arrive; {@code null} in the queue means it ended. */
	private final class Listener {

		final BlockingQueue<Object> events = new LinkedBlockingQueue<>();

		final int status;

		Listener(TestBrowser browser) throws Exception {
			HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/account-events"))
				.header("Accept", "text/event-stream");
			if (!browser.cookies.isEmpty()) {
				request.header("Cookie", browser.cookies.entrySet()
					.stream()
					.map((cookie) -> cookie.getKey() + "=" + cookie.getValue())
					.collect(Collectors.joining("; ")));
			}
			HttpResponse<java.util.stream.Stream<String>> response = HttpClient.newHttpClient()
				.send(request.GET().build(), HttpResponse.BodyHandlers.ofLines());
			status = response.statusCode();
			Thread.ofVirtual().start(() -> {
				String name = null;
				try {
					for (String line : (Iterable<String>) response.body()::iterator) {
						if (line.startsWith("event:")) {
							name = line.substring("event:".length()).strip();
						}
						else if (line.startsWith("data:")) {
							events.add(new Event(name, line.substring("data:".length()).strip()));
						}
					}
				}
				catch (RuntimeException ex) {
					// The connection closed under the reader.
				}
				events.add("ended");
			});
		}

		/** The next account frame's version, skipping heartbeats; fails if none comes in time. */
		String nextVersion() throws Exception {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (System.nanoTime() < deadline) {
				Object next = events.poll(100, TimeUnit.MILLISECONDS);
				if (next instanceof Event event && "account".equals(event.name())) {
					return JSON.readTree(event.data()).get("accountVersion").asString();
				}
				if ("ended".equals(next)) {
					throw new AssertionError("the stream ended");
				}
			}
			throw new AssertionError("no account frame in 5 seconds");
		}

		/** Only heartbeats for the given time: no account frame, and still open. */
		void hearsNothingFor(Duration quiet) throws Exception {
			long deadline = System.nanoTime() + quiet.toNanos();
			while (System.nanoTime() < deadline) {
				Object next = events.poll(100, TimeUnit.MILLISECONDS);
				assertThat(next).as("a frame for someone else, or an end").satisfiesAnyOf(
						(event) -> assertThat(event).isNull(),
						(event) -> assertThat(event).isInstanceOfSatisfying(Event.class,
								(frame) -> assertThat(frame.name()).isEqualTo("heartbeat")));
			}
		}

		/** The stream ends within the given time. */
		void endsWithin(Duration time) throws Exception {
			long deadline = System.nanoTime() + time.toNanos();
			while (System.nanoTime() < deadline) {
				if ("ended".equals(events.poll(100, TimeUnit.MILLISECONDS))) {
					return;
				}
			}
			throw new AssertionError("the stream is still open after " + time);
		}

	}

	@Test
	void eachTraderHearsOnlyTheirOwnChangesUntilTheirSessionEnds() throws Exception {
		UUID instrument = UUID.randomUUID();
		new TransactionTemplate(transactionManager).executeWithoutResult((status) -> market(instrument));
		engine.start();
		TestBrowser trader = TestBrowser.loggedIn(port, "trader", PASSWORD);
		TestBrowser other = TestBrowser.loggedIn(port, "other", PASSWORD);

		Listener traderStream = new Listener(trader);
		Listener otherStream = new Listener(other);
		assertThat(traderStream.status).isEqualTo(200);
		assertThat(traderStream.nextVersion()).as("the first frame").isEqualTo("0");
		assertThat(otherStream.nextVersion()).isEqualTo("0");

		// The trader's resting order changes only the trader's account.
		String sell = place(trader, instrument, "SELL", "100.00");
		assertThat(traderStream.nextVersion()).isEqualTo(sell);
		otherStream.hearsNothingFor(Duration.ofMillis(1500));

		// A trade changes both sides, at the same command.
		String buy = place(other, instrument, "BUY", "100.00");
		assertThat(otherStream.nextVersion()).isEqualTo(buy);
		assertThat(traderStream.nextVersion()).as("the seller hears the fill").isEqualTo(buy);

		// Logging out ends the stream at once; an expired session's stream ends at the next heartbeat.
		assertThat(trader.post("/api/v1/auth/logout", "").statusCode()).isEqualTo(204);
		traderStream.endsWithin(Duration.ofMillis(300));
		jdbc.update("UPDATE spring_session SET last_access_time = 0, expiry_time = 0");
		otherStream.endsWithin(Duration.ofSeconds(7));

		// Only a trader's session opens one: not the operator, not a bot token, not without a session.
		assertThat(new Listener(TestBrowser.loggedIn(port, "operator", PASSWORD)).status).isEqualTo(403);
		assertThat(new Listener(new TestBrowser(port)).status).isEqualTo(401);
		HttpResponse<String> bot = new TestBrowser(port).get("/api/v1/account-events", "Authorization",
				"Bearer " + BOT_TOKEN.token());
		assertThat(bot.statusCode()).as("a bot token").isEqualTo(403);
	}

	private static String place(TestBrowser browser, UUID instrument, String side, String price) throws Exception {
		HttpResponse<String> placed = browser.post("/api/v1/orders", "{\"instrumentId\":\"" + instrument + "\",\"side\":\""
				+ side + "\",\"type\":\"LIMIT\",\"timeInForce\":\"GTC\",\"quantity\":1,\"limitPrice\":\"" + price + "\"}",
				"Idempotency-Key", UUID.randomUUID().toString());
		assertThat(placed.statusCode()).as(placed.body()).isEqualTo(201);
		return JSON.readTree(placed.body()).get("commandSequence").asString();
	}

	/** An open market with one instrument, two funded traders and a bot with a token. */
	private void market(UUID instrument) {
		UUID simulation = UUID.randomUUID();
		jdbc.update("INSERT INTO simulation (id, state, opened_at) VALUES (?, 'OPEN', now())", simulation);
		UUID issuance = UUID.randomUUID();
		jdbc.update("INSERT INTO account (id, simulation_id, kind, display_name) VALUES (?, ?, 'ISSUANCE', 'Issuance')",
				issuance, simulation);
		jdbc.update("INSERT INTO instrument (id, simulation_id, symbol, reference_price_cents) VALUES (?, ?, 'EVT', 10000)",
				instrument, simulation);
		jdbc.update("INSERT INTO app_user (id, username, password_hash, role) VALUES (?, 'other', ?, 'TRADER')",
				UUID.randomUUID(), TestBrowser.passwordHash(PASSWORD));
		for (String name : new String[] { "trader", "other" }) {
			UUID account = UUID.randomUUID();
			jdbc.update("""
					INSERT INTO account (id, simulation_id, kind, display_name, owner_user_id)
					SELECT ?, ?, 'TRADER', ?, id FROM app_user WHERE username = ?""", account, simulation, name, name);
			fund(simulation, issuance, instrument, account, "TRADER");
		}
		UUID bot = UUID.randomUUID();
		jdbc.update("INSERT INTO account (id, simulation_id, kind, display_name) VALUES (?, ?, 'BOT', 'bot-0001')", bot,
				simulation);
		fund(simulation, issuance, instrument, bot, "BOT");
		jdbc.update("INSERT INTO api_token (id, account_id, account_kind, token_hash, scope) VALUES (?, ?, 'BOT', ?, 'TRADE')",
				UUID.randomUUID(), bot, BOT_TOKEN.hash());
	}

	private void fund(UUID simulation, UUID issuance, UUID instrument, UUID account, String kind) {
		UUID batch = UUID.randomUUID();
		jdbc.update("INSERT INTO journal_batch (id, simulation_id, cause, command_sequence) VALUES (?, ?, 'GENESIS', 1)",
				batch, simulation);
		String post = "INSERT INTO ledger_entry (batch_id, account_id, instrument_id, bucket, delta) VALUES (?, ?, ?, 'AVAILABLE', ?)";
		jdbc.update(post, batch, issuance, null, -1_000_000);
		jdbc.update(post, batch, account, null, 1_000_000);
		jdbc.update(post, batch, issuance, instrument, -100);
		jdbc.update(post, batch, account, instrument, 100);
		jdbc.update("INSERT INTO cash_balance (account_id, account_kind, total_cents, reserved_cents) VALUES (?, ?, 1000000, 0)",
				account, kind);
		jdbc.update("""
				INSERT INTO position (account_id, account_kind, instrument_id, total_quantity, reserved_quantity)
				VALUES (?, ?, ?, 100, 0)""", account, kind, instrument);
	}

}
