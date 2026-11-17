package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The migration that tunes the writer's tables, on a database migrated to the latest version: the trades index that
 * duplicated the unique one is gone and the writer's recent-trades query is served by the unique index backwards; the
 * small tables changed on every command leave room for updates in place; snapshots are compressed with lz4.
 */
class WriterTablesMigrationIT {

	private static final String OWNER = "execution_owner";

	private static PostgreSQLContainer postgres;

	private static String url;

	@BeforeAll
	static void start() throws SQLException {
		postgres = new TestcontainersConfiguration().postgresContainer();
		postgres.start();
		try (Connection admin = DriverManager.getConnection(TestcontainersConfiguration.jdbcUrl(postgres, "postgres"),
				postgres.getUsername(), postgres.getPassword())) {
			admin.createStatement().execute("CREATE DATABASE writer_tables TEMPLATE execution_db OWNER " + OWNER);
		}
		url = TestcontainersConfiguration.jdbcUrl(postgres, "writer_tables");
		Flyway.configure()
			.dataSource(url, OWNER, TestcontainersConfiguration.EXECUTION_OWNER_PASSWORD)
			.locations("classpath:db/migration")
			.load()
			.migrate();
	}

	@AfterAll
	static void stop() {
		postgres.stop();
	}

	/**
	 * Sequential and bitmap scans are turned off for the plan: with few or no rows the planner would rather read the
	 * table and sort. The question is whether the unique index can serve the query in order, with no sort.
	 */
	@Test
	void recentTradesComeFromABackwardScanOfTheUniqueIndex() throws Exception {
		UUID instrument = UUID.randomUUID();
		List<String> plan = new ArrayList<>();
		try (Connection owner = owner(); Statement statement = owner.createStatement()) {
			statement.execute("SET enable_seqscan = off");
			statement.execute("SET enable_bitmapscan = off");
			try (ResultSet rows = statement.executeQuery("""
					EXPLAIN SELECT t.id, t.trade_sequence, t.price_cents, t.quantity, t.executed_at, o.side
					FROM trades t JOIN orders o ON o.id = t.taker_order_id
					WHERE t.simulation_id = (SELECT simulation_id FROM instrument WHERE id = '%s')
					    AND t.instrument_id = '%s'
					ORDER BY t.trade_sequence DESC LIMIT 50""".formatted(instrument, instrument))) {
				while (rows.next()) {
					plan.add(rows.getString(1));
				}
			}
		}

		assertThat(String.join("\n", plan))
			.contains("Index Scan Backward using trades_simulation_id_instrument_id_trade_sequence_key")
			.doesNotContain("Sort");
		assertThat(strings("SELECT indexname FROM pg_indexes WHERE tablename = 'trades'")).doesNotContain("trades_recent");
	}

	@Test
	void theWritersHotTablesLeaveRoomAndSnapshotsAreCompressedWithLz4() throws Exception {
		assertThat(strings("""
				SELECT relname FROM pg_class
				WHERE relname IN ('simulation', 'instrument', 'account', 'cash_balance', 'position')
				    AND 'fillfactor=80' = ANY (reloptions)
				ORDER BY relname""")).containsExactly("account", "cash_balance", "instrument", "position", "simulation");
		assertThat(strings("""
				SELECT attcompression::text FROM pg_attribute
				WHERE attrelid = 'outbox'::regclass AND attname = 'payload'""")).containsExactly("l");
	}

	private static List<String> strings(String sql) throws Exception {
		List<String> values = new ArrayList<>();
		try (Connection owner = owner(); ResultSet rows = owner.createStatement().executeQuery(sql)) {
			while (rows.next()) {
				values.add(rows.getString(1));
			}
		}
		return values;
	}

	private static Connection owner() throws SQLException {
		return DriverManager.getConnection(url, OWNER, TestcontainersConfiguration.EXECUTION_OWNER_PASSWORD);
	}

}
