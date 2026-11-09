package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
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

import com.mia_princz.mse.execution.identity.ApiTokens;
import com.mia_princz.mse.execution.trading.application.TradingEngine;

/**
 * An operator revokes a bot's token while the market is open: the bot's next request is refused, the account list
 * says so, and revoking is sequenced, audited and idempotent like every operator command.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=bot-token-revocation",
				"mse.dev-users.trader-password=" + BotTokenRevocationIT.PASSWORD,
				"mse.dev-users.operator-password=" + BotTokenRevocationIT.PASSWORD })
class BotTokenRevocationIT {

	static final String PASSWORD = "test-bot-token-revocation-password";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final OpenApiContract CONTRACT = OpenApiContract.get();

	private static final ApiTokens.Issued TOKEN = ApiTokens.issue();

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	TradingEngine engine;

	@Test
	void aRevokedTokenIsRefusedAtOnceAndRevokingRepeatsLikeAnyCommand() throws Exception {
		UUID bot = UUID.randomUUID();
		new TransactionTemplate(transactionManager).executeWithoutResult((status) -> market(bot));
		engine.start();
		TestBrowser operator = TestBrowser.loggedIn(port, "operator", PASSWORD);
		TestBrowser trader = TestBrowser.loggedIn(port, "trader", PASSWORD);
		String[] bearer = { "Authorization", "Bearer " + TOKEN.token() };
		assertThat(new TestBrowser(port).get("/api/v1/portfolio", bearer).statusCode()).as("before").isEqualTo(200);
		assertThat(token(operator, "bot-0001")).isEqualTo("ACTIVE");

		assertThat(revoke(trader, bot, UUID.randomUUID()).statusCode()).as("a trader").isEqualTo(403);
		UUID key = UUID.randomUUID();
		HttpResponse<String> revoked = revoke(operator, bot, key);

		assertThat(revoked.statusCode()).as(revoked.body()).isEqualTo(200);
		CONTRACT.assertResponse("POST", "/api/v1/admin/bots/{accountId}/revoke-token", 200,
				revoked.headers().firstValue("Content-Type").orElse(null), revoked.body());
		assertThat(JSON.readTree(revoked.body()).get("revoked").asInt()).isEqualTo(1);
		assertThat(new TestBrowser(port).get("/api/v1/portfolio", bearer).statusCode()).as("after").isEqualTo(401);
		assertThat(token(operator, "bot-0001")).isEqualTo("REVOKED");
		assertThat(token(operator, "trader")).as("a trader has no token").isNull();

		// The same key answers the same; a new one finds nothing left to revoke; only bots can be revoked.
		assertThat(revoke(operator, bot, key).body()).isEqualTo(revoked.body());
		HttpResponse<String> again = revoke(operator, bot, UUID.randomUUID());
		assertThat(again.statusCode()).isEqualTo(200);
		assertThat(JSON.readTree(again.body()).get("revoked").asInt()).isZero();
		UUID traderAccount = jdbc.queryForObject("SELECT id FROM account WHERE kind = 'TRADER'", UUID.class);
		for (UUID notABot : List.of(traderAccount, UUID.randomUUID())) {
			HttpResponse<String> missing = revoke(operator, notABot, UUID.randomUUID());
			assertThat(missing.statusCode()).isEqualTo(404);
			assertThat(missing.body()).contains("\"code\":\"BOT_NOT_FOUND\"");
		}
		assertThat(jdbc.queryForList("SELECT outcome FROM audit_log WHERE action = 'BOT_TOKEN_REVOKE' ORDER BY command_sequence",
				String.class)).containsExactly("APPLIED", "NOT_ALLOWED", "NOT_ALLOWED", "NOT_ALLOWED");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM api_token WHERE revoked_at IS NULL", Long.class)).isZero();
	}

	private HttpResponse<String> revoke(TestBrowser browser, UUID account, UUID key) throws Exception {
		return browser.post("/api/v1/admin/bots/" + account + "/revoke-token", "", "Idempotency-Key", key.toString());
	}

	private static String token(TestBrowser operator, String username) throws Exception {
		for (JsonNode account : JSON.readTree(operator.get("/api/v1/admin/accounts").body()).get("accounts")) {
			if (account.get("username").asString().equals(username)) {
				return account.get("token").isNull() ? null : account.get("token").asString();
			}
		}
		throw new AssertionError("no account " + username);
	}

	/** An open market with one instrument, a funded bot with a token, and the dev trader's account. */
	private void market(UUID bot) {
		UUID simulation = UUID.randomUUID();
		jdbc.update("INSERT INTO simulation (id, state, opened_at) VALUES (?, 'OPEN', now())", simulation);
		UUID issuance = UUID.randomUUID();
		jdbc.update("INSERT INTO account (id, simulation_id, kind, display_name) VALUES (?, ?, 'ISSUANCE', 'Issuance')",
				issuance, simulation);
		UUID instrument = UUID.randomUUID();
		jdbc.update("INSERT INTO instrument (id, simulation_id, symbol, reference_price_cents) VALUES (?, ?, 'RVK', 10000)",
				instrument, simulation);
		jdbc.update("INSERT INTO account (id, simulation_id, kind, display_name) VALUES (?, ?, 'BOT', 'bot-0001')", bot,
				simulation);
		fund(simulation, issuance, instrument, bot, "BOT");
		UUID trader = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO account (id, simulation_id, kind, display_name, owner_user_id)
				SELECT ?, ?, 'TRADER', 'trader', id FROM app_user WHERE username = 'trader'""", trader, simulation);
		fund(simulation, issuance, instrument, trader, "TRADER");
		jdbc.update("INSERT INTO api_token (id, account_id, account_kind, token_hash, scope) VALUES (?, ?, 'BOT', ?, 'TRADE')",
				UUID.randomUUID(), bot, TOKEN.hash());
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
