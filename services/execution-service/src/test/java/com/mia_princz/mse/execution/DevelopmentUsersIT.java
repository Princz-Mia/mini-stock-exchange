package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Logins and how they are stored. Development logins come only from configuration: a password left blank creates no
 * user, so there is never a login with a default or empty password. Here the operator has one and the trader doesn't.
 * And only bcrypt hashes match: a login stored any other way, plaintext included, never signs anyone in.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=development-users",
				"mse.dev-users.operator-password=test-development-users-password", "mse.dev-users.trader-password=" })
class DevelopmentUsersIT {

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void aBlankPasswordCreatesNoUser() {
		assertThat(jdbc.queryForList(
				"SELECT username FROM app_user WHERE username IN ('operator', 'trader') ORDER BY username", String.class))
			.containsExactly("operator");
	}

	@Test
	void aPasswordStoredInAnyOtherFormNeverMatches() throws Exception {
		jdbc.update("INSERT INTO app_user (id, username, password_hash, role) VALUES "
				+ "('10000000-0000-0000-0000-0000000000a1', 'plaintext', '{noop}plain-password', 'TRADER'), "
				+ "('10000000-0000-0000-0000-0000000000a2', 'hashed', ?, 'TRADER')",
				TestBrowser.passwordHash("hashed-password"));

		assertThat(login("plaintext", "plain-password")).as("stored as plaintext").isEqualTo(401);
		assertThat(login("hashed", "hashed-password")).as("stored as bcrypt").isEqualTo(200);
	}

	private int login(String username, String password) throws Exception {
		TestBrowser browser = new TestBrowser(port);
		browser.fetchCsrfToken();
		HttpResponse<String> login = browser.post("/api/v1/auth/login",
				"{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
		return login.statusCode();
	}

}
