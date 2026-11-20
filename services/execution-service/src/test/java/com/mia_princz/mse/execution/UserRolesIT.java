package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A login holding both roles: one session holds both, it funds its own trader account in setup and trades with it,
 * and operating and trading stay apart. An operator command never reaches a trader account through an account id, and
 * its stored answers never come back through the trading API. Its password can't be changed through the trading API,
 * as an operator's comes from configuration. One test walks it all, on one market.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=user-roles", "mse.dev-users.operator-password=" + UserRolesIT.PASSWORD,
				"mse.dev-users.trader-password=" + UserRolesIT.PASSWORD })
class UserRolesIT {

	static final String PASSWORD = "test-user-roles-password";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void aLoginWithBothRolesTradesWithItsOwnAccountAndOperatingStaysApart() throws Exception {
		TestUsers.insert(jdbc, UUID.randomUUID(), "both", TestBrowser.passwordHash(PASSWORD), "OPERATOR", "TRADER");
		TestBrowser operator = TestBrowser.loggedIn(port, "both", PASSWORD);
		TestBrowser trader = TestBrowser.loggedIn(port, "trader", PASSWORD);
		assertThat(operator.get("/api/v1/me").body()).isEqualTo("{\"username\":\"both\",\"roles\":[\"OPERATOR\",\"TRADER\"]}");
		assertThat(TestBrowser.loggedIn(port, "operator", PASSWORD).get("/api/v1/me").body())
			.isEqualTo("{\"username\":\"operator\",\"roles\":[\"OPERATOR\"]}");
		assertThat(trader.get("/api/v1/me").body()).isEqualTo("{\"username\":\"trader\",\"roles\":[\"TRADER\"]}");

		// Without a trader account it can't trade yet; operating works as before.
		String nova = post(operator, "/api/v1/admin/instruments", "{\"symbol\":\"NOVA\",\"referencePrice\":\"100.00\"}", 201)
			.get("instrumentId")
			.asString();
		assertThat(operator.get("/api/v1/portfolio").statusCode()).isEqualTo(403);
		assertThat(JSON.readTree(operator.get("/api/v1/portfolio").body()).get("code").asString())
			.isEqualTo("NO_TRADING_ACCOUNT");

		// It is listed among the logins that trade, and funds its own account in setup like any trader's.
		assertThat(operator.get("/api/v1/admin/traders?limit=200").body()).contains("\"username\":\"both\"");
		String funding = """
				{"username":"%s","cash":"10000.00","holdings":[{"instrumentId":"%s","quantity":10}]}""";
		post(operator, "/api/v1/admin/accounts", funding.formatted("both", nova), 201);
		post(operator, "/api/v1/admin/accounts", funding.formatted("trader", nova), 201);
		post(operator, "/api/v1/admin/market/open", "", 200);
		UUID own = jdbc.queryForObject("""
				SELECT a.id FROM account a JOIN app_user u ON u.id = a.owner_user_id WHERE u.username = 'both'""",
				UUID.class);

		// It trades with that account, as its own trader.
		JsonNode sold = post(operator, "/api/v1/orders", """
				{"instrumentId":"%s","side":"SELL","type":"LIMIT","timeInForce":"GTC","quantity":2,"limitPrice":"100.00"}"""
			.formatted(nova), 201);
		JsonNode bought = post(trader, "/api/v1/orders", """
				{"instrumentId":"%s","side":"BUY","type":"LIMIT","timeInForce":"GTC","quantity":2,"limitPrice":"100.00"}"""
			.formatted(nova), 201);
		assertThat(bought.get("filledQuantity").asLong()).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT account_id FROM orders WHERE id = ?", UUID.class,
				UUID.fromString(sold.get("orderId").asString()))).isEqualTo(own);

		// An operator command never reaches its trader account through an account id.
		long tokens = jdbc.queryForObject("SELECT count(*) FROM api_token", Long.class);
		HttpResponse<String> revoked = operator.post("/api/v1/admin/bots/" + own + "/revoke-token", "",
				"Idempotency-Key", UUID.randomUUID().toString());
		assertThat(revoked.statusCode()).as(revoked.body()).isEqualTo(404);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM api_token", Long.class)).isEqualTo(tokens);

		// One key space across both: an operator command's stored answer never comes back through the trading API.
		UUID operatorKey = UUID.randomUUID();
		assertThat(operator.post("/api/v1/admin/market/halt", "", "Idempotency-Key", operatorKey.toString()).statusCode())
			.isEqualTo(200);
		HttpResponse<String> looked = operator.get("/api/v1/commands/" + operatorKey);
		assertThat(looked.statusCode()).as(looked.body()).isEqualTo(404);
		assertThat(JSON.readTree(looked.body()).get("code").asString()).isEqualTo("COMMAND_NOT_FOUND");

		// Its password comes from configuration: refused like an operator-only session, even though it also trades.
		HttpResponse<String> change = operator.post("/api/v1/me/password",
				"{\"currentPassword\":\"%s\",\"newPassword\":\"a-new-password-123\"}".formatted(PASSWORD));
		assertThat(change.statusCode()).as(change.body()).isEqualTo(403);
		assertThat(TestBrowser.loggedIn(port, "both", PASSWORD).get("/api/v1/me").statusCode()).isEqualTo(200);

		// A trader added in setup holds the trader role alone; a login with no role can't sign in.
		assertThat(jdbc.queryForList("""
				SELECT r.role FROM user_role r JOIN app_user u ON u.id = r.user_id WHERE u.username = 'trader'""",
				String.class)).containsExactly("TRADER");
		TestUsers.insert(jdbc, UUID.randomUUID(), "nobody", TestBrowser.passwordHash(PASSWORD));
		TestBrowser nobody = new TestBrowser(port);
		nobody.fetchCsrfToken();
		assertThat(nobody.post("/api/v1/auth/login", "{\"username\":\"nobody\",\"password\":\"%s\"}".formatted(PASSWORD))
			.statusCode()).isEqualTo(401);

		// Roles are added, never changed or removed, by the runtime.
		assertThatThrownBy(() -> jdbc.update("DELETE FROM user_role WHERE role = 'TRADER'")).rootCause()
			.hasMessageContaining("permission denied");
		assertThatThrownBy(() -> jdbc.update("UPDATE user_role SET role = 'OPERATOR'")).rootCause()
			.hasMessageContaining("permission denied");
	}

	private static JsonNode post(TestBrowser browser, String path, String body, int status) throws Exception {
		HttpResponse<String> sent = browser.post(path, body, "Idempotency-Key", UUID.randomUUID().toString());
		assertThat(sent.statusCode()).as(path + ": " + sent.body()).isEqualTo(status);
		return JSON.readTree(sent.body());
	}

}
