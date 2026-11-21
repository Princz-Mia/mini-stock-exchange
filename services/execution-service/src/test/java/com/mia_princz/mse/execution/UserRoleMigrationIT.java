package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The migration that moves roles to their own rows, run as a deployment (or a restored backup) meets it: every login
 * keeps the role it had, and the old column is gone. It needs its own server, so no Spring context runs the migrations
 * first.
 */
class UserRoleMigrationIT {

	private static final String OWNER = "execution_owner";

	@Test
	void everyLoginKeepsItsRoleAsARow() throws Exception {
		try (PostgreSQLContainer postgres = new TestcontainersConfiguration().postgresContainer()) {
			postgres.start();
			String url = TestcontainersConfiguration.jdbcUrl(postgres, "execution_db");
			migrate(url, "31");
			try (Connection owner = owner(url)) {
				user(owner, "operator", "OPERATOR");
				user(owner, "maria", "TRADER");
				user(owner, "lena", "TRADER");
			}

			migrate(url, "32");

			try (Connection owner = owner(url)) {
				List<String> roles = new ArrayList<>();
				try (var rows = owner.createStatement().executeQuery("""
						SELECT u.username || ' ' || r.role FROM app_user u JOIN user_role r ON r.user_id = u.id
						ORDER BY u.username""")) {
					while (rows.next()) {
						roles.add(rows.getString(1));
					}
				}
				assertThat(roles).containsExactly("lena TRADER", "maria TRADER", "operator OPERATOR");
				try (var rows = owner.createStatement().executeQuery("""
						SELECT count(*) FROM information_schema.columns
						WHERE table_name = 'app_user' AND column_name = 'role'""")) {
					assertThat(rows.next()).isTrue();
					assertThat(rows.getLong(1)).as("one source of truth").isZero();
				}
			}
		}
	}

	private static void user(Connection owner, String username, String role) throws SQLException {
		try (PreparedStatement insert = owner.prepareStatement("""
				INSERT INTO app_user (id, username, password_hash, role) VALUES (?, ?, '{bcrypt}x', ?)""")) {
			insert.setObject(1, UUID.randomUUID());
			insert.setString(2, username);
			insert.setString(3, role);
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
