package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The migration that gives a market room for 10,000 bots' orders, met as a deployment meets it: the schema up to the
 * version before, a market in it, then the migration. A market still at the old default of 50,000 resting orders is
 * raised to 200,000; one at any other value keeps it. Each case gets its own database, copied from the empty one the
 * init script made.
 */
class OpenOrderLimitMigrationIT {

	private static final String OWNER = "execution_owner";

	private static PostgreSQLContainer postgres;

	@BeforeAll
	static void start() {
		postgres = new TestcontainersConfiguration().postgresContainer();
		postgres.start();
	}

	@AfterAll
	static void stop() {
		postgres.stop();
	}

	@Test
	void aMarketAtTheOldDefaultIsRaised() throws Exception {
		assertThat(afterMigration("open_orders_default", null)).isEqualTo(200_000);
	}

	@Test
	void aMarketWithAnotherLimitKeepsIt() throws Exception {
		assertThat(afterMigration("open_orders_other", 1_234)).isEqualTo(1_234);
	}

	/** The market's limit after V26, for a market made before it with the default or with {@code limit}. */
	private static int afterMigration(String database, Integer limit) throws Exception {
		String url = database(database);
		migrate(url, "25");
		try (Connection owner = owner(url);
				PreparedStatement insert = owner.prepareStatement(limit == null
						? "INSERT INTO simulation (id, state) VALUES (?, 'SETUP')"
						: "INSERT INTO simulation (id, state, max_open_orders_total) VALUES (?, 'SETUP', ?)")) {
			insert.setObject(1, UUID.randomUUID());
			if (limit != null) {
				insert.setInt(2, limit);
			}
			insert.executeUpdate();
		}
		try (Connection owner = owner(url); ResultSet row = owner.createStatement()
			.executeQuery("SELECT max_open_orders_total FROM simulation")) {
			assertThat(row.next()).isTrue();
			assertThat(row.getInt(1)).as("before").isEqualTo(limit == null ? 50_000 : limit);
		}
		migrate(url, "26");
		try (Connection owner = owner(url); ResultSet row = owner.createStatement()
			.executeQuery("SELECT max_open_orders_total FROM simulation")) {
			assertThat(row.next()).isTrue();
			return row.getInt(1);
		}
	}

	/** A fresh database with the init script's owner and schema grants, and its URL. */
	private static String database(String name) throws SQLException {
		try (Connection admin = DriverManager.getConnection(TestcontainersConfiguration.jdbcUrl(postgres, "postgres"),
				postgres.getUsername(), postgres.getPassword())) {
			admin.createStatement().execute("CREATE DATABASE " + name + " TEMPLATE execution_db OWNER " + OWNER);
		}
		return TestcontainersConfiguration.jdbcUrl(postgres, name);
	}

	private static Connection owner(String url) throws Exception {
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
