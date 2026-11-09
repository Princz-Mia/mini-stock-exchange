package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * Failed sign-ins are limited per username. Its own context, so its failures limit no other test's logins; it runs
 * one test, since every request here comes from the same address and shares that limit too.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=login-limit", "mse.dev-users.operator-password=" + LoginLimitIT.PASSWORD,
				"mse.dev-users.trader-password=" + LoginLimitIT.PASSWORD })
class LoginLimitIT {

	static final String PASSWORD = "test-login-limit-password";

	@LocalServerPort
	int port;

	@Test
	void fiveWrongPasswordsThenEvenTheRightOneWaitsWhileOthersSignIn() throws Exception {
		for (int i = 0; i < 5; i++) {
			assertThat(login("trader", "guess-" + i).statusCode()).as("guess %d", i + 1).isEqualTo(401);
		}

		HttpResponse<String> refused = login("trader", PASSWORD);

		assertThat(refused.statusCode()).as("the right password, refused unchecked").isEqualTo(429);
		assertThat(refused.headers().firstValue("Content-Type")).hasValue("application/problem+json");
		assertThat(refused.body()).contains("\"code\":\"LOGIN_RATE_LIMITED\"");
		assertThat(refused.headers().firstValue("Retry-After").map(Long::parseLong)).hasValueSatisfying(
				(seconds) -> assertThat(seconds).isBetween(1L, 12L));
		assertThat(login("operator", PASSWORD).statusCode()).as("another person").isEqualTo(200);
	}

	private HttpResponse<String> login(String username, String password) throws Exception {
		TestBrowser browser = new TestBrowser(port);
		browser.fetchCsrfToken();
		return browser.post("/api/v1/auth/login",
				"{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
	}

}
