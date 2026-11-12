package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The tour flag over real HTTP, sessions, CSRF and PostgreSQL: each user's own, kept across sessions, and only ever
 * raised. The property gives this class its own database, so every user starts with no tour seen.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = "mse.test.database=tour")
class TourIT {

	static final String PASSWORD = "test-tour-password";

	private static final String TOUR = "/api/v1/me/tour";

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	/** A new user of the given role, so each test starts from no tour seen whatever ran before it. */
	private TestBrowser newUser(String role) throws Exception {
		String username = role.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8);
		jdbc.update("INSERT INTO app_user (id, username, password_hash, role) VALUES (?, ?, ?, ?)", UUID.randomUUID(),
				username, TestBrowser.passwordHash(PASSWORD), role);
		return TestBrowser.loggedIn(port, username, PASSWORD);
	}

	private static String seen(int version) {
		return "{\"seenVersion\":" + version + "}";
	}

	@Test
	void aUserSeesNoTourUntilOneIsRecordedAndKeepsItInEverySession() throws Exception {
		TestBrowser trader = newUser("TRADER");
		assertThat(trader.get(TOUR).body()).isEqualTo(seen(0));

		HttpResponse<String> recorded = trader.put(TOUR, seen(2));

		assertThat(recorded.statusCode()).isEqualTo(200);
		assertThat(recorded.body()).isEqualTo(seen(2));
		assertThat(trader.get(TOUR).body()).isEqualTo(seen(2));
		// Another browser, another session: the flag is the user's, not the browser's.
		String username = trader.get("/api/v1/me").body().replaceAll(".*\"username\":\"([^\"]+)\".*", "$1");
		assertThat(TestBrowser.loggedIn(port, username, PASSWORD).get(TOUR).body()).isEqualTo(seen(2));
	}

	@Test
	void theVersionOnlyRises() throws Exception {
		TestBrowser operator = newUser("OPERATOR");
		assertThat(operator.put(TOUR, seen(3)).body()).isEqualTo(seen(3));

		// Replaying an older tour, or a tab that finished it late, never brings back a newer one.
		HttpResponse<String> older = operator.put(TOUR, seen(1));

		assertThat(older.statusCode()).isEqualTo(200);
		assertThat(older.body()).isEqualTo(seen(3));
		assertThat(operator.put(TOUR, seen(4)).body()).isEqualTo(seen(4));
		assertThat(operator.get(TOUR).body()).isEqualTo(seen(4));
	}

	@Test
	void eachUserHasTheirOwn() throws Exception {
		TestBrowser newcomer = newUser("TRADER");
		TestBrowser operator = newUser("OPERATOR");
		TestBrowser trader = newUser("TRADER");

		assertThat(operator.put(TOUR, seen(900)).body()).isEqualTo(seen(900));
		assertThat(trader.put(TOUR, seen(7)).body()).isEqualTo(seen(7));

		assertThat(newcomer.get(TOUR).body()).isEqualTo(seen(0));
		assertThat(operator.get(TOUR).body()).isEqualTo(seen(900));
		assertThat(trader.get(TOUR).body()).isEqualTo(seen(7));
	}

	@Test
	void refusesVersionsOutsideItsRangeAndRequestsWithoutASessionOrToken() throws Exception {
		TestBrowser trader = newUser("TRADER");
		// Only a JSON whole number in range: no string, fraction, null, extra field or missing body, nothing coerced.
		for (String body : new String[] { seen(0), seen(-1), seen(1001), "{}", "{\"seenVersion\":\"one\"}",
				"{\"seenVersion\":\"7\"}", "{\"seenVersion\":1.5}", "{\"seenVersion\":1000.9}", "{\"seenVersion\":null}",
				"{\"seenVersion\":1,\"other\":2}", "[1]", "", "not json" }) {
			HttpResponse<String> refused = trader.put(TOUR, body);
			assertThat(refused.statusCode()).as(body).isEqualTo(400);
			assertThat(refused.body()).as(body).contains("\"code\":\"VALIDATION_FAILED\"").contains("\"field\":\"seenVersion\"");
		}
		assertThat(trader.get(TOUR).body()).isEqualTo(seen(0));

		assertThat(new TestBrowser(port).get(TOUR).statusCode()).isEqualTo(401);
		TestBrowser anonymous = new TestBrowser(port);
		anonymous.fetchCsrfToken();
		assertThat(anonymous.put(TOUR, seen(1)).statusCode()).isEqualTo(401);
		trader.csrfToken = null;
		assertThat(trader.put(TOUR, seen(1)).statusCode()).isEqualTo(403);
	}

	/** A session that outlived its user stores nothing and is answered as if it had no session. */
	@Test
	void aSessionWhoseUserIsGoneStoresNothing() throws Exception {
		TestBrowser gone = newUser("TRADER");
		String username = gone.get("/api/v1/me").body().replaceAll(".*\"username\":\"([^\"]+)\".*", "$1");
		// The runtime role cannot rename users; the owner can, as an operator fixing a mistake might.
		try (Connection app = jdbc.getDataSource().getConnection();
				Connection owner = DriverManager.getConnection(app.getMetaData().getURL(), "execution_owner",
						TestcontainersConfiguration.EXECUTION_OWNER_PASSWORD);
				PreparedStatement rename = owner.prepareStatement("UPDATE app_user SET username = ? WHERE username = ?")) {
			rename.setString(1, username + "-renamed");
			rename.setString(2, username);
			assertThat(rename.executeUpdate()).isEqualTo(1);
		}

		assertThat(gone.put(TOUR, seen(1)).statusCode()).isEqualTo(401);
		assertThat(gone.get(TOUR).body()).isEqualTo(seen(0));
	}

	/** The runtime role may read, add and raise a preference, never remove one. */
	@Test
	void theRuntimeRoleCannotRemovePreferences() {
		for (String privilege : new String[] { "SELECT", "INSERT", "UPDATE" }) {
			assertThat(jdbc.queryForObject("SELECT has_table_privilege(current_user, 'user_preference', ?)", Boolean.class,
					privilege))
				.as(privilege)
				.isTrue();
		}
		for (String privilege : new String[] { "DELETE", "TRUNCATE" }) {
			assertThat(jdbc.queryForObject("SELECT has_table_privilege(current_user, 'user_preference', ?)", Boolean.class,
					privilege))
				.as(privilege)
				.isFalse();
		}
	}

}
