package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The migration that keeps each bot assignment's account creation time beside it, run as a deployment (or a restored
 * backup) meets it: assignments made before it get their account's time, and an insert that leaves the time out gets
 * the account's own, while one that gives it keeps it. It needs its own server, so no Spring context runs the
 * migrations first.
 */
class BotAssignmentOrderMigrationIT {

	private static final String OWNER = "execution_owner";

	@Test
	void everyAssignmentGetsItsAccountsCreationTime() throws Exception {
		try (PostgreSQLContainer postgres = new TestcontainersConfiguration().postgresContainer()) {
			postgres.start();
			String url = TestcontainersConfiguration.jdbcUrl(postgres, "execution_db");
			migrate(url, "40");
			try (Connection owner = owner(url)) {
				owner.createStatement().execute("""
						INSERT INTO simulation (id, state, opened_at) VALUES ('10000000-0000-0000-0000-000000000001', 'OPEN', now());
						INSERT INTO app_user (id, username, password_hash) VALUES
						    ('30000000-0000-0000-0000-000000000001', 'operator', '{bcrypt}x');
						INSERT INTO account (id, simulation_id, kind, display_name, bot_strategy, created_at)
						SELECT ('40000000-0000-0000-0000-00000000000' || n)::uuid, '10000000-0000-0000-0000-000000000001',
						    'BOT', 'bot-' || n, 'NOISE', timestamptz '2026-11-21 10:00:00+00' + make_interval(mins => 10 - n)
						FROM generate_series(1, 4) n;
						INSERT INTO bot_run (id, simulation_id, desired_state, seed, commands_per_second, max_order_quantity,
						    max_position, started_by, started_at, start_sequence)
						VALUES ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'RUNNING', 1,
						     4, 10, 100, '30000000-0000-0000-0000-000000000001', '2026-11-21 11:00:00+00', 1);
						INSERT INTO bot_assignment (run_id, account_id, account_kind, strategy)
						SELECT '20000000-0000-0000-0000-000000000001', ('40000000-0000-0000-0000-00000000000' || n)::uuid,
						    'BOT', 'NOISE'
						FROM generate_series(1, 2) n;""");
			}

			migrate(url, "41");

			try (Connection owner = owner(url)) {
				owner.createStatement().execute("""
						INSERT INTO bot_assignment (run_id, account_id, account_kind, strategy)
						VALUES ('20000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000003', 'BOT',
						    'NOISE');
						INSERT INTO bot_assignment (run_id, account_id, account_kind, strategy, account_created_at)
						VALUES ('20000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000004', 'BOT',
						    'NOISE', '2026-11-21 12:00:00+00');""");
				List<String> kept = new ArrayList<>();
				try (var rows = owner.createStatement().executeQuery("""
						SELECT right(account_id::text, 1) || ' ' || to_char(account_created_at AT TIME ZONE 'UTC', 'HH24:MI')
						FROM bot_assignment ORDER BY account_created_at, account_id""")) {
					while (rows.next()) {
						kept.add(rows.getString(1));
					}
				}
				assertThat(kept).containsExactly("3 10:07", "2 10:08", "1 10:09", "4 12:00");
			}
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
