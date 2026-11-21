package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The migration that records when each run's window ended, run as a deployment (or a restored backup) meets it: a run
 * its worker ended, ended at the confirmed stop; one the next start ended, when that start began, though its worker
 * confirmed later; one still going, or recorded before windows were kept, has no end. It needs its own server, so no
 * Spring context runs the migrations first.
 */
class BotRunEndMigrationIT {

	private static final String OWNER = "execution_owner";

	@Test
	void eachEndedRunGetsTheTimeItsWindowEnded() throws Exception {
		try (PostgreSQLContainer postgres = new TestcontainersConfiguration().postgresContainer()) {
			postgres.start();
			String url = TestcontainersConfiguration.jdbcUrl(postgres, "execution_db");
			migrate(url, "33");
			try (Connection owner = owner(url)) {
				owner.createStatement().execute("""
						INSERT INTO simulation (id, state, opened_at) VALUES ('10000000-0000-0000-0000-000000000001', 'OPEN', now());
						INSERT INTO app_user (id, username, password_hash) VALUES
						    ('30000000-0000-0000-0000-000000000001', 'operator', '{bcrypt}x');
						INSERT INTO bot_run (id, simulation_id, desired_state, seed, commands_per_second, max_order_quantity,
						    max_position, started_by, started_at, start_sequence, stop_requested_at, stopped_at, end_sequence,
						    ended_by)
						VALUES
						    -- Its worker confirmed the stop at 10:10, ending its window there.
						    ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'STOPPED', 1, 4,
						     10, 100, '30000000-0000-0000-0000-000000000001', '2026-11-21 10:00:00+00', 1,
						     '2026-11-21 10:09:00+00', '2026-11-21 10:10:00+00', 5, 'WORKER'),
						    -- Ended by the next start at 10:30; its worker came back and confirmed at 11:00.
						    ('20000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', 'STOPPED', 1, 4,
						     10, 100, '30000000-0000-0000-0000-000000000001', '2026-11-21 10:20:00+00', 6,
						     '2026-11-21 10:25:00+00', '2026-11-21 11:00:00+00', 9, 'NEXT_START'),
						    -- Recorded before windows were kept.
						    ('20000000-0000-0000-0000-000000000004', '10000000-0000-0000-0000-000000000001', 'STOPPED', 1, 4,
						     10, 100, '30000000-0000-0000-0000-000000000001', '2026-11-21 09:00:00+00', 1,
						     '2026-11-21 09:05:00+00', '2026-11-21 09:05:00+00', NULL, 'NOT_KEPT');
						-- The run whose start ended the second, still going.
						INSERT INTO bot_run (id, simulation_id, desired_state, seed, commands_per_second, max_order_quantity,
						    max_position, started_by, started_at, start_sequence)
						VALUES ('20000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000001', 'RUNNING', 1,
						     4, 10, 100, '30000000-0000-0000-0000-000000000001', '2026-11-21 10:30:00+00', 10);""");
			}

			migrate(url, "34");

			try (Connection owner = owner(url)) {
				List<String> ends = new ArrayList<>();
				try (var rows = owner.createStatement().executeQuery("""
						SELECT right(id::text, 1) || ' ' || coalesce(to_char(ended_at AT TIME ZONE 'UTC', 'HH24:MI'), '-')
						FROM bot_run ORDER BY id""")) {
					while (rows.next()) {
						ends.add(rows.getString(1));
					}
				}
				assertThat(ends).containsExactly("1 10:10", "2 10:30", "3 -", "4 -");
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
