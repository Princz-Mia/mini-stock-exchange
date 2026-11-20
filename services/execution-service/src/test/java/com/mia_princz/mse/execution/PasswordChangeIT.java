package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

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
 * A trader changes their own password over real HTTP, sessions and PostgreSQL: the current one confirmed, the new one
 * under the same rules as an added trader's, other sessions ended, and wrong guesses limited like sign-ins.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=password-change", "mse.dev-users.trader-password=" + PasswordChangeIT.PASSWORD,
				"mse.dev-users.operator-password=" + PasswordChangeIT.OPERATOR_PASSWORD })
@ExtendWith(OutputCaptureExtension.class)
class PasswordChangeIT {

	static final String PASSWORD = "the-first-trader-password";

	static final String OPERATOR_PASSWORD = "the-operator-password";

	private static final String NEW_PASSWORD = "a-new-trader-password";

	private static final String ROUTE = "/api/v1/me/password";

	private static final OpenApiContract CONTRACT = OpenApiContract.get();

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void aTraderChangesTheirPasswordAndTheirOtherSessionsEnd(CapturedOutput output) throws Exception {
		TestBrowser here = TestBrowser.loggedIn(port, "trader", PASSWORD);
		TestBrowser elsewhere = TestBrowser.loggedIn(port, "trader", PASSWORD);
		String hashBefore = hash();

		// A trader whose username is long enough to be a password may not make it theirs, in any case.
		TestBrowser setup = TestBrowser.loggedIn(port, "operator", OPERATOR_PASSWORD);
		assertThat(setup.post("/api/v1/admin/traders",
				"{\"username\":\"maria.kovacs.01\",\"password\":\"" + PASSWORD + "\"}", "Idempotency-Key",
				java.util.UUID.randomUUID().toString()).statusCode()).isEqualTo(201);
		TestBrowser maria = TestBrowser.loggedIn(port, "maria.kovacs.01", PASSWORD);
		HttpResponse<String> named = change(maria, PASSWORD, "MARIA.KOVACS.01");
		assertThat(fields(named)).containsExactly("newPassword INVALID_PASSWORD");
		assertThat(named.body()).contains("must not be the username");

		// Refused, with every problem named, before anything changes.
		assertThat(fields(change(here, "", "short"))).containsExactly("currentPassword REQUIRED",
				"newPassword INVALID_PASSWORD");
		assertThat(fields(change(here, PASSWORD, PASSWORD))).containsExactly("newPassword INVALID_PASSWORD");
		assertThat(fields(change(here, PASSWORD, "TRADER"))).containsExactly("newPassword INVALID_PASSWORD");
		assertThat(fields(change(here, PASSWORD, "            "))).containsExactly("newPassword INVALID_PASSWORD");
		HttpResponse<String> wrong = change(here, "not-the-password", NEW_PASSWORD);
		assertThat(fields(wrong)).containsExactly("currentPassword WRONG_PASSWORD");
		CONTRACT.assertResponse("POST", ROUTE, 400, wrong.headers().firstValue("Content-Type").orElse(null),
				wrong.body());
		assertThat(hash()).isEqualTo(hashBefore);

		// Changed: this session goes on, under a new ID; the other one has ended.
		String sessionBefore = here.cookies.get("SESSION");
		HttpResponse<String> changed = change(here, PASSWORD, NEW_PASSWORD);
		assertThat(changed.statusCode()).as(changed.body()).isEqualTo(204);
		// The other session is gone at once, not only refused when it next asks.
		assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name = 'trader'", Long.class))
			.as("only this session left")
			.isEqualTo(1);
		assertThat(here.cookies.get("SESSION")).as("a new session ID").isNotEqualTo(sessionBefore);
		assertThat(here.get("/api/v1/me").statusCode()).isEqualTo(200);
		assertThat(elsewhere.get("/api/v1/me").statusCode()).as("signed out elsewhere").isEqualTo(401);
		assertThat(hash()).isNotEqualTo(hashBefore).startsWith("{bcrypt}");
		assertThat(jdbc.queryForObject("SELECT password_version FROM app_user WHERE username = 'trader'", Integer.class))
			.as("raised with the password")
			.isEqualTo(1);

		// The new password signs in; the old one no longer does.
		TestBrowser.loggedIn(port, "trader", NEW_PASSWORD);
		TestBrowser stale = new TestBrowser(port);
		stale.fetchCsrfToken();
		assertThat(stale.post("/api/v1/auth/login",
				"{\"username\":\"trader\",\"password\":\"" + PASSWORD + "\"}").statusCode()).isEqualTo(401);

		// A session signed in with the old password, but not among those ended, is signed out on its next request:
		// here one whose version is behind, as a sign-in still running while the password changed would be.
		TestBrowser lagging = TestBrowser.loggedIn(port, "trader", NEW_PASSWORD);
		assertThat(lagging.get("/api/v1/me").statusCode()).isEqualTo(200);
		jdbc.update("UPDATE app_user SET password_version = password_version + 1 WHERE username = 'trader'");
		assertThat(lagging.get("/api/v1/me").statusCode()).as("its version is behind").isEqualTo(401);
		assertThat(here.get("/api/v1/me").statusCode()).as("so is this one now").isEqualTo(401);
		here = TestBrowser.loggedIn(port, "trader", NEW_PASSWORD);
		assertThat(here.get("/api/v1/me").statusCode()).as("a new sign-in carries the new version").isEqualTo(200);

		// The runtime role may change a login's password and its version, and nothing else of it.
		assertThat(jdbc.queryForList("SELECT c FROM unnest(ARRAY['id', 'username', 'created_at', "
				+ "'password_hash', 'password_version']) c WHERE has_column_privilege(current_user, 'app_user', c, 'UPDATE')",
				String.class))
			.containsExactlyInAnyOrder("password_hash", "password_version");
		assertThat(jdbc.queryForObject("SELECT has_table_privilege(current_user, 'app_user', 'DELETE')", Boolean.class))
			.isFalse();

		// Only a signed-in trader changes their own password.
		TestBrowser operator = TestBrowser.loggedIn(port, "operator", OPERATOR_PASSWORD);
		assertThat(change(operator, OPERATOR_PASSWORD, "an-operator-new-password").statusCode()).isEqualTo(403);
		TestBrowser nobody = new TestBrowser(port);
		nobody.fetchCsrfToken();
		assertThat(change(nobody, PASSWORD, NEW_PASSWORD).statusCode()).isEqualTo(401);

		// Wrong current passwords count against the sign-in limit; once it is reached, even the right one waits.
		// Two failures so far, the wrong guess and the sign-in with the old password; right ones were given back. So
		// three more guesses are answered, and the next waits.
		for (int attempt = 0; attempt < 3; attempt++) {
			assertThat(fields(change(here, "still-not-the-password", "yet-another-password"))).as("guess %d", attempt + 3)
				.containsExactly("currentPassword WRONG_PASSWORD");
		}
		HttpResponse<String> limited = change(here, NEW_PASSWORD, "yet-another-password");
		assertThat(limited.statusCode()).as(limited.body()).isEqualTo(429);
		assertThat(limited.headers().firstValue("Retry-After")).isPresent();
		assertThat(JSON.readTree(limited.body()).get("code").asString()).isEqualTo("LOGIN_RATE_LIMITED");
		CONTRACT.assertResponse("POST", ROUTE, 429, limited.headers().firstValue("Content-Type").orElse(null),
				limited.body());
		// It is the sign-in limit itself: signing in as the trader waits too.
		TestBrowser later = new TestBrowser(port);
		later.fetchCsrfToken();
		assertThat(later.post("/api/v1/auth/login",
				"{\"username\":\"trader\",\"password\":\"" + NEW_PASSWORD + "\"}").statusCode()).isEqualTo(429);

		// No password reached the log.
		for (String password : List.of(PASSWORD, NEW_PASSWORD, "not-the-password")) {
			assertThat(output.getAll()).doesNotContain(password);
		}
	}

	private String hash() {
		return jdbc.queryForObject("SELECT password_hash FROM app_user WHERE username = 'trader'", String.class);
	}

	private static HttpResponse<String> change(TestBrowser browser, String current, String next) throws Exception {
		return browser.post(ROUTE, "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(current, next));
	}

	private static List<String> fields(HttpResponse<String> problem) {
		assertThat(problem.statusCode()).as(problem.body()).isEqualTo(400);
		JsonNode body = JSON.readTree(problem.body());
		assertThat(body.get("code").asString()).isEqualTo("VALIDATION_FAILED");
		List<String> fields = new ArrayList<>();
		body.get("errors").forEach((error) -> fields.add(error.get("field").asString() + " " + error.get("code").asString()));
		return fields;
	}

}
