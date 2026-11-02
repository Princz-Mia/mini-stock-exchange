package com.mia_princz.mse.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Runs against real PostgreSQL roles: the context has already migrated as owner and connected as the app role. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DatabaseIsolationIT {

	private static final String INSUFFICIENT_PRIVILEGE = "42501";

	private static final String OWN_DB = "market_data_db";

	private static final String OTHER_DB = "execution_db";

	@Autowired
	PostgreSQLContainer postgres;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Test
	void runtimeConnectionUsesTheLeastPrivilegeRole() {
		assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class)).isEqualTo("market_data_app");
		assertThat(jdbcTemplate.queryForObject("SELECT current_database()", String.class)).isEqualTo(OWN_DB);
	}

	@Test
	void flywayMigratedAsOwnerIntoItsOwnHistory() throws SQLException {
		assertThat(jdbcTemplate.queryForObject("SELECT service_name FROM service_identity", String.class))
			.isEqualTo("market-data-service");
		try (Connection owner = connect(OWN_DB, "market_data_owner", TestcontainersConfiguration.MARKET_DATA_OWNER_PASSWORD);
				Statement statement = owner.createStatement();
				ResultSet history = statement
					.executeQuery("SELECT installed_by, success FROM flyway_schema_history WHERE version = '1'")) {
			assertThat(history.next()).isTrue();
			assertThat(history.getString("installed_by")).isEqualTo("market_data_owner");
			assertThat(history.getBoolean("success")).isTrue();
		}
	}

	@Test
	void runtimeRoleCannotChangeSchemaOrMigrationHistory() throws SQLException {
		try (Connection app = connect(OWN_DB, "market_data_app", TestcontainersConfiguration.MARKET_DATA_APP_PASSWORD)) {
			assertDenied(app, "CREATE TABLE intruder (id INT)");
			assertDenied(app, "DROP TABLE service_identity");
			assertDenied(app, "INSERT INTO service_identity (service_name) VALUES ('intruder')");
			assertDenied(app, "DELETE FROM flyway_schema_history");
			assertDenied(app, "SELECT * FROM flyway_schema_history");
		}
	}

	@Test
	void everyRoleIsDeniedTheOtherServicesDatabase() {
		List<String[]> attempts = List.of(new String[] { OTHER_DB, "market_data_owner", TestcontainersConfiguration.MARKET_DATA_OWNER_PASSWORD },
				new String[] { OTHER_DB, "market_data_app", TestcontainersConfiguration.MARKET_DATA_APP_PASSWORD },
				new String[] { OWN_DB, "execution_owner", TestcontainersConfiguration.EXECUTION_OWNER_PASSWORD },
				new String[] { OWN_DB, "execution_app", TestcontainersConfiguration.EXECUTION_APP_PASSWORD });

		assertThat(attempts).allSatisfy((attempt) -> assertThatThrownBy(() -> connect(attempt[0], attempt[1], attempt[2]))
			.isInstanceOfSatisfying(SQLException.class,
					(ex) -> assertThat(ex.getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE)));
	}

	@Test
	void ownerCannotInstallCrossDatabaseExtensions() throws SQLException {
		try (Connection owner = connect(OWN_DB, "market_data_owner", TestcontainersConfiguration.MARKET_DATA_OWNER_PASSWORD)) {
			assertDenied(owner, "CREATE EXTENSION dblink");
			assertDenied(owner, "CREATE EXTENSION postgres_fdw");
		}
	}

	private Connection connect(String database, String user, String password) throws SQLException {
		return DriverManager.getConnection(TestcontainersConfiguration.jdbcUrl(postgres, database), user, password);
	}

	private static void assertDenied(Connection connection, String sql) {
		assertThatThrownBy(() -> {
			try (Statement statement = connection.createStatement()) {
				statement.execute(sql);
			}
		}).as(sql).isInstanceOfSatisfying(SQLException.class,
				(ex) -> assertThat(ex.getSQLState()).as(sql).isEqualTo(INSUFFICIENT_PRIVILEGE));
	}

}
