package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Trader logins the operator adds during setup, over real HTTP and PostgreSQL: each can sign in at once, with or
 * without an account, and its password is kept nowhere but as a bcrypt hash. One test walks the whole sequence,
 * because the market it sets up is shared.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=trader-logins", "mse.dev-users.trader-password=" + TraderLoginIT.PASSWORD,
				"mse.dev-users.operator-password=" + TraderLoginIT.PASSWORD })
@ExtendWith(OutputCaptureExtension.class)
class TraderLoginIT {

	static final String PASSWORD = "test-trader-login-password";

	private static final String MARIA_PASSWORD = "maria-first-password-2026";

	private static final String OMAR_PASSWORD = "omar-first-password-2026";

	private static final OpenApiContract CONTRACT = OpenApiContract.get();

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String TRADERS = "/api/v1/admin/traders";

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void theOperatorAddsTradersWhoCanSignInAtOnce(CapturedOutput output) throws Exception {
		TestBrowser operator = TestBrowser.loggedIn(port, "operator", PASSWORD);
		HttpResponse<String> added = operator.post("/api/v1/admin/instruments",
				"{\"symbol\":\"NOVA\",\"referencePrice\":\"100.00\"}", "Idempotency-Key", UUID.randomUUID().toString());
		String nova = JSON.readTree(added.body()).get("instrumentId").asString();

		// A trader with an endowment: the login and the funded account in one command.
		UUID mariaKey = UUID.randomUUID();
		String mariaBody = trader("maria", MARIA_PASSWORD, "25000.00", nova, 300);
		HttpResponse<String> maria = operator.post(TRADERS, mariaBody, "Idempotency-Key", mariaKey.toString());
		assertThat(maria.statusCode()).as(maria.body()).isEqualTo(201);
		CONTRACT.assertResponse("POST", TRADERS, 201, maria.headers().firstValue("Content-Type").orElse(null),
				maria.body());
		JsonNode mariaAdded = JSON.readTree(maria.body());
		assertThat(mariaAdded.get("username").asString()).isEqualTo("maria");
		assertThat(mariaAdded.get("cash").asString()).isEqualTo("25000.00");
		assertThat(mariaAdded.get("accountId").asString()).matches("[0-9a-f-]{36}");
		TestBrowser mariaSession = TestBrowser.loggedIn(port, "maria", MARIA_PASSWORD);
		JsonNode portfolio = JSON.readTree(mariaSession.get("/api/v1/portfolio").body());
		assertThat(portfolio.get("cash").get("available").asString()).isEqualTo("25000.00");
		assertThat(portfolio.get("holdings").get(0).get("total").asLong()).isEqualTo(300);

		// A trader without one: the login alone, which can sign in and be funded later in setup.
		HttpResponse<String> omar = operator.post(TRADERS, trader("omar", OMAR_PASSWORD, null, null, 0),
				"Idempotency-Key", UUID.randomUUID().toString());
		assertThat(omar.statusCode()).as(omar.body()).isEqualTo(201);
		CONTRACT.assertResponse("POST", TRADERS, 201, omar.headers().firstValue("Content-Type").orElse(null),
				omar.body());
		assertThat(JSON.readTree(omar.body()).get("accountId").isNull()).isTrue();
		assertThat(JSON.readTree(omar.body()).get("cash").isNull()).isTrue();
		TestBrowser omarSession = TestBrowser.loggedIn(port, "omar", OMAR_PASSWORD);
		assertThat(JSON.readTree(omarSession.get("/api/v1/me").body()).get("roles").toString()).contains("TRADER");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM account WHERE display_name = 'omar'", Long.class))
			.isZero();
		// The list shows every login that trades, the configured ones included (the operator also trades); the unfunded
		// ones are those setup can fund.
		HttpResponse<String> everyone = operator.get(TRADERS);
		CONTRACT.assertResponse("GET", TRADERS, 200, everyone.headers().firstValue("Content-Type").orElse(null),
				everyone.body());
		assertThat(listed(everyone)).containsExactly("operator false", "trader false", "maria true", "omar false");
		assertThat(listed(operator.get(TRADERS + "?funded=false"))).containsExactly("operator false", "trader false",
				"omar false");
		assertThat(listed(operator.get(TRADERS + "?funded=true"))).containsExactly("maria true");
		assertThat(listed(operator.get(TRADERS + "?order=newest"))).containsExactly("omar false", "maria true",
				"trader false", "operator false");
		// A page at a time, the filter kept by the cursor; a cursor from another filter is refused.
		JsonNode first = JSON.readTree(operator.get(TRADERS + "?funded=false&limit=2").body());
		assertThat(first.get("traders").get(1).get("username").asString()).isEqualTo("trader");
		String next = first.get("nextCursor").asString();
		JsonNode second = JSON.readTree(operator.get(TRADERS + "?funded=false&limit=2&cursor=" + next).body());
		assertThat(second.get("traders").get(0).get("username").asString()).isEqualTo("omar");
		assertThat(second.get("nextCursor").isNull()).isTrue();
		assertThat(fields(operator.get(TRADERS + "?funded=true&limit=2&cursor=" + next)))
			.containsExactly("cursor INVALID_CURSOR");
		assertThat(fields(operator.get(TRADERS + "?limit=2&cursor=" + next))).containsExactly("cursor INVALID_CURSOR");
		String unfiltered = JSON.readTree(operator.get(TRADERS + "?limit=1").body()).get("nextCursor").asString();
		assertThat(fields(operator.get(TRADERS + "?funded=false&limit=1&cursor=" + unfiltered)))
			.containsExactly("cursor INVALID_CURSOR");
		// Newest first, a page at a time.
		JsonNode newest = JSON.readTree(operator.get(TRADERS + "?order=newest&limit=2").body());
		assertThat(newest.get("traders").get(1).get("username").asString()).isEqualTo("maria");
		assertThat(listed(operator.get(TRADERS + "?order=newest&limit=2&cursor=" + newest.get("nextCursor").asString())))
			.containsExactly("trader false", "operator false");
		assertThat(omarSession.get(TRADERS).statusCode()).as("traders cannot list them").isEqualTo(403);

		HttpResponse<String> funded = operator.post("/api/v1/admin/accounts",
				"{\"username\":\"omar\",\"cash\":\"1000.00\"}", "Idempotency-Key", UUID.randomUUID().toString());
		assertThat(funded.statusCode()).as(funded.body()).isEqualTo(201);
		assertThat(listed(operator.get(TRADERS + "?funded=false"))).containsExactly("operator false", "trader false");

		// The same key repeats the first answer whatever password it carries: no second user, no new hash.
		String hashBefore = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE username = 'maria'",
				String.class);
		assertThat(operator
			.post(TRADERS, trader("maria", "another-password-entirely", "25000.00", nova, 300), "Idempotency-Key",
					mariaKey.toString())
			.body()).isEqualTo(maria.body());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE username = 'maria'", Long.class))
			.isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT password_hash FROM app_user WHERE username = 'maria'", String.class))
			.isEqualTo(hashBefore)
			.startsWith("{bcrypt}");
		TestBrowser.loggedIn(port, "maria", MARIA_PASSWORD);
		// Another trader or endowment under the key is a conflict.
		assertThat(code(operator.post(TRADERS, trader("maria", MARIA_PASSWORD, "1.00", null, 0), "Idempotency-Key",
				mariaKey.toString()), 409)).isEqualTo("IDEMPOTENCY_CONFLICT");

		// Refusals: a username taken, by an added trader or one from configuration; an instrument the market lacks.
		assertThat(code(operator.post(TRADERS, trader("maria", OMAR_PASSWORD, null, null, 0), "Idempotency-Key",
				UUID.randomUUID().toString()), 409)).isEqualTo("USERNAME_TAKEN");
		HttpResponse<String> operatorTaken = operator.post(TRADERS, trader("operator", OMAR_PASSWORD, null, null, 0),
				"Idempotency-Key", UUID.randomUUID().toString());
		assertThat(code(operatorTaken, 409)).isEqualTo("USERNAME_TAKEN");
		CONTRACT.assertResponse("POST", TRADERS, 409, operatorTaken.headers().firstValue("Content-Type").orElse(null),
				operatorTaken.body());
		assertThat(code(operator.post(TRADERS, trader("trader", OMAR_PASSWORD, null, null, 0), "Idempotency-Key",
				UUID.randomUUID().toString()), 409)).isEqualTo("USERNAME_TAKEN");
		assertThat(code(operator.post(TRADERS, trader("lena", OMAR_PASSWORD, "1.00", UUID.randomUUID().toString(), 5),
				"Idempotency-Key", UUID.randomUUID().toString()), 422)).isEqualTo("UNKNOWN_INSTRUMENT");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE username = 'lena'", Long.class))
			.as("nothing added with a refused endowment")
			.isZero();

		// Invalid requests are refused before the writer, every problem listed.
		assertThat(fields(operator.post(TRADERS, "{\"username\":\"Ma\",\"password\":\"short\"}", "Idempotency-Key",
				UUID.randomUUID().toString()))).containsExactly("username INVALID_USERNAME", "password INVALID_PASSWORD");
		assertThat(fields(operator.post(TRADERS, "{\"username\":\"same-as-it-12\",\"password\":\"Same-As-It-12\",\"cash\":\"x\"}",
				"Idempotency-Key", UUID.randomUUID().toString()))).containsExactly("password INVALID_PASSWORD",
						"cash INVALID_AMOUNT");
		assertThat(fields(operator.post(TRADERS, "{}", "Idempotency-Key", UUID.randomUUID().toString())))
			.containsExactly("username REQUIRED", "password REQUIRED");

		// Only the operator adds traders.
		assertThat(mariaSession.post(TRADERS, trader("sneaky", OMAR_PASSWORD, null, null, 0), "Idempotency-Key",
				UUID.randomUUID().toString()).statusCode()).isEqualTo(403);

		// Once the market opens, adding is a committed refusal.
		assertThat(operator.post("/api/v1/admin/market/open", "", "Idempotency-Key", UUID.randomUUID().toString())
			.statusCode()).isEqualTo(200);
		assertThat(code(operator.post(TRADERS, trader("late", OMAR_PASSWORD, null, null, 0), "Idempotency-Key",
				UUID.randomUUID().toString()), 409)).isEqualTo("MARKET_NOT_IN_SETUP");
		assertThat(jdbc.queryForList("SELECT outcome FROM audit_log WHERE action = 'TRADER_ADD' ORDER BY command_sequence",
				String.class))
			.containsExactly("APPLIED", "APPLIED", "NOT_ALLOWED", "NOT_ALLOWED", "NOT_ALLOWED", "NOT_ALLOWED",
					"NOT_ALLOWED");

		// The passwords are nowhere but in the hashes: not in a stored answer, a response or the log.
		for (String password : List.of(MARIA_PASSWORD, OMAR_PASSWORD, "another-password-entirely")) {
			assertThat(jdbc.queryForObject("SELECT count(*) FROM command_result WHERE response_body::text LIKE ?",
					Long.class, "%" + password + "%")).as("stored answers").isZero();
			assertThat(output.getAll()).as("the log").doesNotContain(password);
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM command_result WHERE response_body::text LIKE '%bcrypt%'",
				Long.class)).as("no hash in a stored answer").isZero();
		assertThat(maria.body() + omar.body()).doesNotContain("password").doesNotContain("bcrypt");
	}

	private static String trader(String username, String password, String cash, String instrumentId, long quantity) {
		StringBuilder body = new StringBuilder("{\"username\":\"%s\",\"password\":\"%s\"".formatted(username, password));
		if (cash != null) {
			body.append(",\"cash\":\"").append(cash).append('"');
		}
		if (instrumentId != null) {
			body.append(",\"holdings\":[{\"instrumentId\":\"%s\",\"quantity\":%d}]".formatted(instrumentId, quantity));
		}
		return body.append('}').toString();
	}

	private static List<String> listed(HttpResponse<String> page) {
		assertThat(page.statusCode()).as(page.body()).isEqualTo(200);
		List<String> traders = new ArrayList<>();
		JSON.readTree(page.body())
			.get("traders")
			.forEach((trader) -> traders.add(trader.get("username").asString() + " " + trader.get("funded").asBoolean()));
		return traders;
	}

	private static String code(HttpResponse<String> problem, int status) {
		assertThat(problem.statusCode()).as(problem.body()).isEqualTo(status);
		return JSON.readTree(problem.body()).get("code").asString();
	}

	private static List<String> fields(HttpResponse<String> problem) {
		assertThat(code(problem, 400)).isEqualTo("VALIDATION_FAILED");
		List<String> fields = new ArrayList<>();
		JSON.readTree(problem.body())
			.get("errors")
			.forEach((error) -> fields.add(error.get("field").asString() + " " + error.get("code").asString()));
		return fields;
	}

}
