package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.mia_princz.mse.execution.trading.adapter.JdbcTradingStore;

import tools.jackson.databind.json.JsonMapper;

/**
 * The migration that checks new usernames, run as a deployment meets it: logins from configuration that predate it
 * stay as they are, and every new login follows the format and is unique ignoring case. It needs its own server, so no
 * Spring context runs the migrations first.
 */
class TraderLoginMigrationIT {

	private static final String OWNER = "execution_owner";

	private static final String CHECK_VIOLATION = "23514";

	private static final String UNIQUE_VIOLATION = "23505";

	@Test
	void earlierLoginsStayAndNewOnesFollowTheRules() throws Exception {
		try (PostgreSQLContainer postgres = new TestcontainersConfiguration().postgresContainer()) {
			postgres.start();
			String url = TestcontainersConfiguration.jdbcUrl(postgres, "execution_db");
			migrate(url, "22");
			try (Connection owner = owner(url)) {
				// A name from configuration that the new format would refuse.
				user(owner, "Legacy.Trader");
				user(owner, "operator");
			}

			migrate(url, "23");

			try (Connection owner = owner(url)) {
				try (var rows = owner.createStatement()
					.executeQuery("SELECT count(*) FROM app_user WHERE username = 'Legacy.Trader'")) {
					assertThat(rows.next()).isTrue();
					assertThat(rows.getLong(1)).as("kept as it was").isEqualTo(1);
				}
				user(owner, "maria");
				// The writer's check finds the earlier name whatever its case, before the index would refuse it.
				JdbcTradingStore store = new JdbcTradingStore(JdbcClient.create(new DriverManagerDataSource(url, OWNER,
						TestcontainersConfiguration.EXECUTION_OWNER_PASSWORD)), JsonMapper.builder().build());
				assertThat(store.usernameTaken("legacy.trader")).isTrue();
				assertThat(store.usernameTaken("MARIA")).isTrue();
				assertThat(store.usernameTaken("lena")).isFalse();
				assertFails(UNIQUE_VIOLATION, () -> user(owner, "legacy.trader"));
				assertFails(CHECK_VIOLATION, () -> user(owner, "Maria2"));
				assertFails(CHECK_VIOLATION, () -> user(owner, "ab"));
				assertFails(CHECK_VIOLATION, () -> user(owner, "-maria"));
			}
		}
	}

	private static void assertFails(String state, ThrowingRunnable insert) {
		assertThatThrownBy(insert::run).isInstanceOfSatisfying(SQLException.class,
				(refused) -> assertThat(refused.getSQLState()).isEqualTo(state));
	}

	private interface ThrowingRunnable {

		void run() throws Exception;

	}

	private static void user(Connection owner, String username) throws SQLException {
		try (PreparedStatement insert = owner.prepareStatement("""
				INSERT INTO app_user (id, username, password_hash, role) VALUES (?, ?, '{bcrypt}x', 'TRADER')""")) {
			insert.setObject(1, UUID.randomUUID());
			insert.setString(2, username);
			insert.executeUpdate();
		}
	}

	private static Connection owner(String url) throws SQLException {
		return DriverManager.getConnection(url, OWNER, TestcontainersConfiguration.EXECUTION_OWNER_PASSWORD);
	}

	private static void migrate(String url, String target) {
		Flyway.configure()
			.dataSource(url, OWNER, TestcontainersConfiguration.EXECUTION_OWNER_PASSWORD)
			.locations("classpath:db/migration")
			.target(target)
			.load()
			.migrate();
	}

}
