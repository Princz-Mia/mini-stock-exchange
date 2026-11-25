package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * A second stack on the same host, such as one on another port of localhost, gets the browser's cookies for both: with
 * its own cookie name, signing in or out here never touches the other stack's session.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "MSE_SESSION_COOKIE=" + SessionCookieNameIT.COOKIE,
				"mse.dev-users.trader-password=" + SessionCookieNameIT.PASSWORD })
class SessionCookieNameIT {

	static final String COOKIE = "MSE_SECOND_STACK";

	static final String PASSWORD = "test-trader-password";

	private static final String OTHER_STACKS_SESSION = "the-other-stacks-session";

	@LocalServerPort
	int port;

	@Test
	void signingInAndOutUsesTheConfiguredNameOnly() throws Exception {
		TestBrowser browser = new TestBrowser(port);
		browser.cookies.put("SESSION", OTHER_STACKS_SESSION);
		browser.fetchCsrfToken();

		HttpResponse<String> login = browser.post("/api/v1/auth/login",
				"{\"username\":\"trader\",\"password\":\"" + PASSWORD + "\"}");

		assertThat(login.statusCode()).isEqualTo(200);
		assertThat(cookieNames(login)).contains(COOKIE).doesNotContain("SESSION");
		assertThat(browser.get("/api/v1/me").statusCode()).isEqualTo(200);
		String session = browser.cookies.get(COOKIE);

		browser.fetchCsrfToken();
		HttpResponse<String> logout = browser.post("/api/v1/auth/logout", "");

		assertThat(logout.statusCode()).isEqualTo(204);
		assertThat(logout.headers().allValues("Set-Cookie")).as("only this stack's cookie is cleared")
			.anySatisfy((cookie) -> assertThat(cookie).startsWith(COOKIE + "=").contains("Max-Age=0"))
			.noneSatisfy((cookie) -> assertThat(cookie).startsWith("SESSION="));
		assertThat(browser.cookies.get("SESSION")).isEqualTo(OTHER_STACKS_SESSION);
		TestBrowser replay = new TestBrowser(port);
		replay.cookies.put(COOKIE, session);
		assertThat(replay.get("/api/v1/me").statusCode()).as("the session ended").isEqualTo(401);
	}

	private static List<String> cookieNames(HttpResponse<String> response) {
		return response.headers()
			.allValues("Set-Cookie")
			.stream()
			.map((cookie) -> cookie.substring(0, cookie.indexOf('=')))
			.toList();
	}

}
