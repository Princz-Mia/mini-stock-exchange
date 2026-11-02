package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Drives the real filter chain over HTTP with cookies handled by hand, so rotation can be observed. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.dev-users.trader-password=" + AuthenticationIT.PASSWORD,
				"mse.dev-users.operator-password=" + AuthenticationIT.OPERATOR_PASSWORD })
class AuthenticationIT {

	static final String PASSWORD = "test-trader-password";

	static final String OPERATOR_PASSWORD = "test-operator-password";

	private static final String TRADER_LOGIN = "{\"username\":\"trader\",\"password\":\"" + PASSWORD + "\"}";

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Test
	void loginRequiresACsrfToken() throws Exception {
		assertThat(new Browser().post("/api/v1/auth/login", TRADER_LOGIN).statusCode()).isEqualTo(403);
	}

	@Test
	void identityRequiresASession() throws Exception {
		assertThat(new Browser().get("/api/v1/me").statusCode()).isEqualTo(401);
	}

	@Test
	void loginRotatesTheSessionAndTheCsrfToken() throws Exception {
		Browser browser = new Browser();
		browser.fetchCsrfToken();
		String anonymousSession = browser.cookies.get("SESSION");
		String anonymousToken = browser.csrfToken;

		HttpResponse<String> login = browser.post("/api/v1/auth/login", TRADER_LOGIN);

		assertThat(login.statusCode()).isEqualTo(200);
		String identity = "{\"username\":\"trader\",\"roles\":[\"TRADER\"]}";
		assertThat(login.body()).isEqualTo(identity);
		assertThat(browser.cookies.get("SESSION")).isNotEqualTo(anonymousSession);
		HttpResponse<String> me = browser.get("/api/v1/me");
		assertThat(me.statusCode()).isEqualTo(200);
		assertThat(me.body()).as("after the session round trip").isEqualTo(identity);

		Browser attacker = new Browser();
		attacker.cookies.put("SESSION", anonymousSession);
		assertThat(attacker.get("/api/v1/me").statusCode()).as("pre-login session is useless").isEqualTo(401);

		browser.csrfToken = anonymousToken;
		assertThat(browser.post("/api/v1/auth/logout", "").statusCode()).as("pre-login token is useless").isEqualTo(403);
	}

	@Test
	void sessionCookieIsHttpOnlyAndSameSiteLax() throws Exception {
		Browser browser = new Browser();
		browser.fetchCsrfToken();

		HttpResponse<String> login = browser.post("/api/v1/auth/login", TRADER_LOGIN);

		String cookie = login.headers()
			.allValues("Set-Cookie")
			.stream()
			.filter((value) -> value.startsWith("SESSION="))
			.findFirst()
			.orElseThrow();
		assertThat(cookie).contains("HttpOnly").contains("SameSite=Lax");
		assertThat(browser.get("/api/v1/auth/csrf").headers().allValues("Set-Cookie").toString())
			.as("no readable CSRF cookie")
			.doesNotContain("XSRF");
	}

	@Test
	void sessionsLiveInTheDatabase() throws Exception {
		Browser browser = new Browser();
		browser.fetchCsrfToken();
		browser.post("/api/v1/auth/login", TRADER_LOGIN);

		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM spring_session WHERE principal_name = 'trader'", Long.class))
			.isPositive();
	}

	@Test
	void wrongPasswordAndUnknownUserGetTheSameAnswer() throws Exception {
		Browser browser = new Browser();
		browser.fetchCsrfToken();

		HttpResponse<String> wrongPassword = browser.post("/api/v1/auth/login",
				"{\"username\":\"trader\",\"password\":\"not-the-password\"}");
		HttpResponse<String> unknownUser = browser.post("/api/v1/auth/login",
				"{\"username\":\"nobody\",\"password\":\"not-the-password\"}");

		assertThat(wrongPassword.statusCode()).isEqualTo(401);
		assertThat(wrongPassword.headers().firstValue("Content-Type")).hasValue("application/problem+json");
		assertThat(wrongPassword.body()).contains("\"code\":\"INVALID_CREDENTIALS\"");
		assertThat(unknownUser.statusCode()).isEqualTo(401);
		assertThat(unknownUser.body()).isEqualTo(wrongPassword.body());
	}

	@Test
	void malformedLoginIsRejected() throws Exception {
		Browser browser = new Browser();
		browser.fetchCsrfToken();

		assertThat(browser.post("/api/v1/auth/login", "{\"username\":\"\",\"password\":\"x\"}").statusCode())
			.isEqualTo(400);
		assertThat(browser
			.post("/api/v1/auth/login", "{\"username\":\"trader\",\"password\":\"x\",\"accountId\":\"someone\"}")
			.statusCode()).as("unknown fields").isEqualTo(400);
	}

	@Test
	void logoutRequiresACsrfTokenAndEndsTheSession() throws Exception {
		Browser browser = new Browser();
		browser.fetchCsrfToken();
		browser.post("/api/v1/auth/login", TRADER_LOGIN);
		String session = browser.cookies.get("SESSION");

		browser.csrfToken = null;
		assertThat(browser.post("/api/v1/auth/logout", "").statusCode()).isEqualTo(403);
		assertThat(browser.get("/api/v1/me").statusCode()).as("still logged in").isEqualTo(200);

		browser.fetchCsrfToken();
		assertThat(browser.post("/api/v1/auth/logout", "").statusCode()).isEqualTo(204);

		Browser replay = new Browser();
		replay.cookies.put("SESSION", session);
		assertThat(replay.get("/api/v1/me").statusCode()).isEqualTo(401);
	}

	@Test
	void passwordsAreStoredHashedAndRolesAreSeparate() {
		List<Map<String, Object>> users = jdbcTemplate
			.queryForList("SELECT username, password_hash, role FROM app_user ORDER BY username");

		assertThat(users).extracting((user) -> user.get("username")).containsExactly("operator", "trader");
		assertThat(users).extracting((user) -> user.get("role")).containsExactly("OPERATOR", "TRADER");
		assertThat(users).allSatisfy((user) -> assertThat((String) user.get("password_hash")).startsWith("{bcrypt}"));
	}

	private final class Browser {

		private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");

		final Map<String, String> cookies = new LinkedHashMap<>();

		String csrfToken;

		void fetchCsrfToken() throws Exception {
			Matcher matcher = TOKEN.matcher(get("/api/v1/auth/csrf").body());
			assertThat(matcher.find()).isTrue();
			csrfToken = matcher.group(1);
		}

		HttpResponse<String> get(String path) throws Exception {
			return send(request(path).GET());
		}

		HttpResponse<String> post(String path, String json) throws Exception {
			HttpRequest.Builder request = request(path).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json));
			if (csrfToken != null) {
				request.header("X-CSRF-TOKEN", csrfToken);
			}
			return send(request);
		}

		private HttpRequest.Builder request(String path) {
			HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
			if (!cookies.isEmpty()) {
				request.header("Cookie", cookies.entrySet()
					.stream()
					.map((cookie) -> cookie.getKey() + "=" + cookie.getValue())
					.collect(Collectors.joining("; ")));
			}
			return request;
		}

		private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
			try (HttpClient client = HttpClient.newHttpClient()) {
				HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
				for (String header : response.headers().allValues("Set-Cookie")) {
					String pair = header.split(";", 2)[0];
					int separator = pair.indexOf('=');
					cookies.put(pair.substring(0, separator), pair.substring(separator + 1));
				}
				return response;
			}
		}

	}

}
