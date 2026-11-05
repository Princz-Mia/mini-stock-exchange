package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import tools.jackson.databind.json.JsonMapper;

import com.mia_princz.mse.execution.trading.application.TradingEngine;

/**
 * This service against {@code api/openapi.yaml}: every route it serves is in the contract under its tag, and every
 * status it answers with, success or problem, has the body the contract describes.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=api-contract", "mse.dev-users.trader-password=" + ApiContractIT.PASSWORD,
				"mse.dev-users.operator-password=" + ApiContractIT.PASSWORD })
class ApiContractIT {

	static final String PASSWORD = "test-contract-password";

	private static final OpenApiContract CONTRACT = OpenApiContract.get();

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** Served by the security filter chain, not a controller, so absent from the handler mappings. */
	private static final Set<String> FILTER_ROUTES = Set.of("POST /api/v1/auth/logout");

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	TradingEngine engine;

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	RequestMappingHandlerMapping mappings;

	@Test
	void servedRoutesAndContractOperationsAreTheSame() {
		Set<String> served = new TreeSet<>(FILTER_ROUTES);
		mappings.getHandlerMethods().keySet().forEach((info) -> info.getPathPatternsCondition().getPatterns()
			.stream()
			.map((pattern) -> pattern.getPatternString())
			.filter((path) -> path.startsWith("/api/") || path.startsWith("/internal/"))
			.forEach((path) -> info.getMethodsCondition().getMethods()
				.forEach((method) -> served.add(method.name() + " " + OpenApiContract.blankVariables(path)))));

		assertThat(served).isEqualTo(CONTRACT.operations("execution"));
	}

	@Test
	void everyOperationAndProblemMatchesTheContract() throws Exception {
		TestBrowser anonymous = new TestBrowser(port);
		TestBrowser operator = TestBrowser.loggedIn(port, "operator", PASSWORD);
		String open = "/api/v1/admin/market/open";
		check(anonymous.get("/api/v1/market"), "GET", "/api/v1/market", 200);
		check(operator.post(open, "", "Idempotency-Key", UUID.randomUUID().toString()), "POST", open, 409);

		UUID instrument = new TransactionTemplate(transactionManager).execute((status) -> market());
		engine.start();

		check(anonymous.get("/api/v1/auth/csrf"), "GET", "/api/v1/auth/csrf", 200);
		check(anonymous.get("/api/v1/me"), "GET", "/api/v1/me", 401);
		check(anonymous.post("/api/v1/auth/login", login("trader")), "POST", "/api/v1/auth/login", 403);
		anonymous.fetchCsrfToken();
		check(anonymous.post("/api/v1/auth/login", "{\"username\":\"trader\",\"password\":\"wrong\"}"), "POST",
				"/api/v1/auth/login", 401);
		check(anonymous.post("/api/v1/auth/login", "{\"username\":\"\",\"password\":\"x\"}"), "POST",
				"/api/v1/auth/login", 400);
		check(anonymous.post("/api/v1/auth/login", login("trader")), "POST", "/api/v1/auth/login", 200);

		TestBrowser trader = TestBrowser.loggedIn(port, "trader", PASSWORD);
		TestBrowser other = TestBrowser.loggedIn(port, "other", PASSWORD);
		check(trader.get("/api/v1/me"), "GET", "/api/v1/me", 200);

		String place = "/api/v1/orders";
		UUID restingKey = UUID.randomUUID();
		HttpResponse<String> resting = trader.post(place, limit(instrument, "BUY", 2, "90.00"), "Idempotency-Key",
				restingKey.toString());
		check(resting, "POST", place, 201);
		String restingId = JSON.readTree(resting.body()).get("orderId").asString();
		check(trader.post(place, limit(instrument, "BUY", 2, "90.00")), "POST", place, 400);
		check(trader.post(place, limit(instrument, "BUY", 2, "90.005"), "Idempotency-Key",
				UUID.randomUUID().toString()), "POST", place, 400);
		check(trader.post(place, limit(instrument, "BUY", 3, "90.00"), "Idempotency-Key", restingKey.toString()),
				"POST", place, 409);
		check(trader.post(place, limit(instrument, "BUY", 10_000, "500.00"), "Idempotency-Key",
				UUID.randomUUID().toString()), "POST", place, 422);
		check(trader.post(place, limit(UUID.randomUUID(), "BUY", 1, "90.00"), "Idempotency-Key",
				UUID.randomUUID().toString()), "POST", place, 422);

		String read = "/api/v1/orders/{orderId}";
		check(trader.get("/api/v1/orders/" + restingId), "GET", read, 200);
		check(trader.get("/api/v1/orders/" + UUID.randomUUID()), "GET", read, 404);

		String cancel = "/api/v1/orders/{orderId}/cancel";
		UUID cancelKey = UUID.randomUUID();
		check(trader.post("/api/v1/orders/" + restingId + "/cancel", "", "Idempotency-Key", cancelKey.toString()),
				"POST", cancel, 200);
		check(trader.post("/api/v1/orders/" + UUID.randomUUID() + "/cancel", "", "Idempotency-Key",
				UUID.randomUUID().toString()), "POST", cancel, 404);
		HttpResponse<String> sold = trader.post(place, limit(instrument, "SELL", 1, "100.00"), "Idempotency-Key",
				UUID.randomUUID().toString());
		other.post(place, limit(instrument, "BUY", 1, "100.00"), "Idempotency-Key", UUID.randomUUID().toString());
		String soldId = JSON.readTree(sold.body()).get("orderId").asString();
		check(trader.post("/api/v1/orders/" + soldId + "/cancel", "", "Idempotency-Key",
				UUID.randomUUID().toString()), "POST", cancel, 409);

		check(trader.get("/api/v1/orders"), "GET", "/api/v1/orders", 200);
		check(trader.get("/api/v1/orders?status=active"), "GET", "/api/v1/orders", 200);
		check(trader.get("/api/v1/orders?limit=0"), "GET", "/api/v1/orders", 400);
		check(trader.get("/api/v1/portfolio"), "GET", "/api/v1/portfolio", 200);
		check(trader.get("/api/v1/portfolio/trades"), "GET", "/api/v1/portfolio/trades", 200);
		check(trader.get("/api/v1/portfolio/trades?cursor=nope"), "GET", "/api/v1/portfolio/trades", 400);

		String command = "/api/v1/commands/{key}";
		check(trader.get("/api/v1/commands/" + restingKey), "GET", command, 201);
		check(trader.get("/api/v1/commands/" + cancelKey), "GET", command, 200);
		check(trader.get("/api/v1/commands/" + UUID.randomUUID()), "GET", command, 404);

		check(operator.get("/api/v1/orders/" + restingId), "GET", read, 403);
		check(operator.get("/api/v1/portfolio"), "GET", "/api/v1/portfolio", 403);
		check(operator.post(place, limit(instrument, "BUY", 1, "90.00"), "Idempotency-Key",
				UUID.randomUUID().toString()), "POST", place, 403);

		String halt = "/api/v1/admin/market/halt";
		String resume = "/api/v1/admin/market/resume";
		check(anonymous.get("/api/v1/market"), "GET", "/api/v1/market", 200);
		check(operator.post(open, "", "Idempotency-Key", UUID.randomUUID().toString()), "POST", open, 409);
		check(operator.post(halt, ""), "POST", halt, 400);
		check(operator.post(halt, "", "Idempotency-Key", UUID.randomUUID().toString()), "POST", halt, 200);
		check(trader.post(resume, "", "Idempotency-Key", UUID.randomUUID().toString()), "POST", resume, 403);
		check(operator.post(resume, "", "Idempotency-Key", UUID.randomUUID().toString()), "POST", resume, 200);

		String instruments = "/api/v1/admin/instruments";
		check(operator.post(instruments, "{\"symbol\":\"late\",\"referencePrice\":\"1\"}", "Idempotency-Key",
				UUID.randomUUID().toString()), "POST", instruments, 400);
		check(operator.post(instruments, "{\"symbol\":\"LATE\",\"referencePrice\":\"1.00\"}", "Idempotency-Key",
				UUID.randomUUID().toString()), "POST", instruments, 409);

		String accounts = "/api/v1/admin/accounts";
		check(operator.get(accounts), "GET", accounts, 200);
		check(operator.get(accounts + "?cursor=nope"), "GET", accounts, 400);
		check(operator.post(accounts, "{\"username\":\"trader\",\"cash\":\"-1\"}", "Idempotency-Key",
				UUID.randomUUID().toString()), "POST", accounts, 400);
		check(operator.post(accounts, "{\"username\":\"nobody\",\"cash\":\"1.00\"}", "Idempotency-Key",
				UUID.randomUUID().toString()), "POST", accounts, 422);
		check(operator.post(accounts, "{\"username\":\"trader\",\"cash\":\"1.00\"}", "Idempotency-Key",
				UUID.randomUUID().toString()), "POST", accounts, 409);

		trader.csrfToken = null;
		check(trader.post("/api/v1/auth/logout", ""), "POST", "/api/v1/auth/logout", 403);
		trader.fetchCsrfToken();
		check(trader.post("/api/v1/auth/logout", ""), "POST", "/api/v1/auth/logout", 204);
	}

	private static void check(HttpResponse<String> response, String method, String template, int status) {
		assertThat(response.statusCode()).as(method + " " + template + ": " + response.body()).isEqualTo(status);
		CONTRACT.assertResponse(method, template, status, response.headers().firstValue("Content-Type").orElse(null),
				response.body());
	}

	private static String login(String username) {
		return "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}";
	}

	private static String limit(UUID instrument, String side, long quantity, String price) {
		return """
				{"instrumentId":"%s","side":"%s","type":"LIMIT","timeInForce":"GTC","quantity":%d,"limitPrice":"%s"}"""
			.formatted(instrument, side, quantity, price);
	}

	private UUID market() {
		UUID simulation = UUID.randomUUID();
		UUID issuance = UUID.randomUUID();
		UUID instrument = UUID.randomUUID();
		jdbc.update("INSERT INTO simulation (id, state, opened_at) VALUES (?, 'OPEN', now())", simulation);
		jdbc.update("INSERT INTO account (id, simulation_id, kind, display_name) VALUES (?, ?, 'ISSUANCE', 'Issuance')",
				issuance, simulation);
		jdbc.update("INSERT INTO instrument (id, simulation_id, symbol, reference_price_cents) VALUES (?, ?, 'API', 10000)",
				instrument, simulation);
		jdbc.update("INSERT INTO app_user (id, username, password_hash, role) VALUES (?, 'other', ?, 'TRADER')",
				UUID.randomUUID(), "{noop}" + PASSWORD);
		for (String username : List.of("trader", "other")) {
			UUID account = UUID.randomUUID();
			jdbc.update("""
					INSERT INTO account (id, simulation_id, kind, display_name, owner_user_id)
					SELECT ?, ?, 'TRADER', ?, id FROM app_user WHERE username = ?""", account, simulation, username,
					username);
			UUID batch = UUID.randomUUID();
			jdbc.update(
					"INSERT INTO journal_batch (id, simulation_id, cause, command_sequence) VALUES (?, ?, 'GENESIS', 1)",
					batch, simulation);
			String post = "INSERT INTO ledger_entry (batch_id, account_id, instrument_id, bucket, delta) VALUES (?, ?, ?, 'AVAILABLE', ?)";
			jdbc.update(post, batch, issuance, null, -1_000_000);
			jdbc.update(post, batch, account, null, 1_000_000);
			jdbc.update(post, batch, issuance, instrument, -100);
			jdbc.update(post, batch, account, instrument, 100);
			jdbc.update("""
					INSERT INTO cash_balance (account_id, account_kind, total_cents, reserved_cents)
					VALUES (?, 'TRADER', 1000000, 0)""", account);
			jdbc.update("""
					INSERT INTO position (account_id, account_kind, instrument_id, total_quantity, reserved_quantity)
					VALUES (?, 'TRADER', ?, 100, 0)""", account, instrument);
		}
		return instrument;
	}

}
