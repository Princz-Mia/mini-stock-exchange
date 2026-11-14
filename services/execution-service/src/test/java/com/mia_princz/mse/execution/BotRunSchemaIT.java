package com.mia_princz.mse.execution;

import static com.mia_princz.mse.execution.SchemaFixtures.CHECK_VIOLATION;
import static com.mia_princz.mse.execution.SchemaFixtures.FOREIGN_KEY_VIOLATION;
import static com.mia_princz.mse.execution.SchemaFixtures.INSUFFICIENT_PRIVILEGE;
import static com.mia_princz.mse.execution.SchemaFixtures.UNIQUE_VIOLATION;
import static com.mia_princz.mse.execution.SchemaFixtures.assertFails;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** The bot run tables, as the runtime role sees them. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BotRunSchemaIT {

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	PlatformTransactionManager transactionManager;

	SchemaFixtures db;

	@BeforeEach
	void fixtures() {
		db = new SchemaFixtures(jdbcTemplate, transactionManager);
	}

	@Test
	void aRunWithItsInstrumentsAndBotsIsAccepted() {
		db.inRolledBackTransaction((f) -> {
			UUID simulation = f.simulation();
			UUID run = run(f, simulation, "1.50");
			f.jdbc.update("INSERT INTO bot_run_instrument (run_id, simulation_id, instrument_id) VALUES (?, ?, ?)", run,
					simulation, f.instrument(simulation, "NOVA"));
			for (String strategy : new String[] { "MARKET_MAKER", "NOISE", "MOMENTUM" }) {
				f.jdbc.update("INSERT INTO bot_assignment (run_id, account_id, account_kind, strategy) VALUES (?, ?, 'BOT', ?)",
						run, f.bot(simulation, strategy), strategy);
			}
			// The worker reports, and confirms a stop.
			f.jdbc.update("""
					UPDATE bot_run SET worker_id = 'w', heartbeat_at = now(), lease_expires_at = now(), placed = 5,
					    desired_state = 'STOPPED', stop_requested_at = now(), stopped_at = now() WHERE id = ?""", run);
			assertThat(f.jdbc.queryForObject("SELECT count(*) FROM bot_assignment WHERE run_id = ?", Long.class, run))
				.isEqualTo(3);
		});
	}

	@Test
	void onlyOneRunRunsAtATime() {
		db.inRolledBackTransaction((f) -> {
			UUID simulation = f.simulation();
			run(f, simulation, "1.00");
			assertFails(UNIQUE_VIOLATION, () -> run(f, simulation, "1.00"));
		});
		db.inRolledBackTransaction((f) -> {
			UUID simulation = f.simulation();
			UUID first = run(f, simulation, "1.00");
			f.jdbc.update("UPDATE bot_run SET desired_state = 'STOPPED', stop_requested_at = now() WHERE id = ?", first);
			run(f, simulation, "1.00");
			assertThat(f.jdbc.queryForObject("SELECT count(*) FROM bot_run", Long.class)).isEqualTo(2);
		});
	}

	@Test
	void aStopIsRecordedWithItsRequest() {
		db.inRolledBackTransaction((f) -> {
			UUID run = run(f, f.simulation(), "1.00");
			assertFails(CHECK_VIOLATION, () -> f.jdbc.update("UPDATE bot_run SET desired_state = 'STOPPED' WHERE id = ?", run));
		});
		db.inRolledBackTransaction((f) -> {
			UUID run = run(f, f.simulation(), "1.00");
			assertFails(CHECK_VIOLATION,
					() -> f.jdbc.update("UPDATE bot_run SET stop_requested_at = now() WHERE id = ?", run));
		});
		db.inRolledBackTransaction((f) -> {
			UUID run = run(f, f.simulation(), "1.00");
			assertFails(CHECK_VIOLATION, () -> f.jdbc.update("UPDATE bot_run SET stopped_at = now() WHERE id = ?", run));
		});
	}

	@Test
	void theRateIsPositiveAndBounded() {
		db.inRolledBackTransaction((f) -> assertFails(CHECK_VIOLATION, () -> run(f, f.simulation(), "0.00")));
		db.inRolledBackTransaction((f) -> assertFails(CHECK_VIOLATION, () -> run(f, f.simulation(), "200.01")));
		db.inRolledBackTransaction((f) -> run(f, f.simulation(), "200.00"));
	}

	@Test
	void onlyBotsAreAssignedAndOnlyToKnownStrategies() {
		db.inRolledBackTransaction((f) -> {
			UUID simulation = f.simulation();
			UUID run = run(f, simulation, "1.00");
			assertFails(FOREIGN_KEY_VIOLATION, () -> f.jdbc.update(
					"INSERT INTO bot_assignment (run_id, account_id, account_kind, strategy) VALUES (?, ?, 'BOT', 'NOISE')",
					run, f.trader(simulation)));
		});
		db.inRolledBackTransaction((f) -> {
			UUID simulation = f.simulation();
			UUID run = run(f, simulation, "1.00");
			assertFails(CHECK_VIOLATION, () -> f.jdbc.update(
					"INSERT INTO bot_assignment (run_id, account_id, account_kind, strategy) VALUES (?, ?, 'TRADER', 'NOISE')",
					run, f.trader(simulation)));
		});
		db.inRolledBackTransaction((f) -> {
			UUID simulation = f.simulation();
			UUID run = run(f, simulation, "1.00");
			assertFails(CHECK_VIOLATION, () -> f.jdbc.update(
					"INSERT INTO bot_assignment (run_id, account_id, account_kind, strategy) VALUES (?, ?, 'BOT', 'ARBITRAGE')",
					run, f.bot(simulation, "NOISE")));
		});
		db.inRolledBackTransaction((f) -> {
			UUID simulation = f.simulation();
			UUID run = run(f, simulation, "1.00");
			assertFails(FOREIGN_KEY_VIOLATION,
					() -> f.jdbc.update("INSERT INTO bot_run_instrument (run_id, simulation_id, instrument_id) VALUES (?, ?, ?)",
							run, simulation, UUID.randomUUID()));
		});
	}

	@Test
	void aBotRunsOnlyTheStrategyItIsTaggedWith() {
		String assign = "INSERT INTO bot_assignment (run_id, account_id, account_kind, strategy) VALUES (?, ?, 'BOT', ?)";
		db.inRolledBackTransaction((f) -> {
			UUID simulation = f.simulation();
			UUID run = run(f, simulation, "1.00");
			assertFails(FOREIGN_KEY_VIOLATION, () -> f.jdbc.update(assign, run, f.bot(simulation, "NOISE"), "MOMENTUM"));
		});
		db.inRolledBackTransaction((f) -> {
			UUID simulation = f.simulation();
			UUID run = run(f, simulation, "1.00");
			assertFails(FOREIGN_KEY_VIOLATION, () -> f.jdbc.update(assign, run, f.bot(simulation), "NOISE"));
		});
		db.inRolledBackTransaction((f) -> {
			UUID simulation = f.simulation();
			UUID run = run(f, simulation, "1.00");
			f.jdbc.update(assign, run, f.bot(simulation, "MOMENTUM"), "MOMENTUM");
		});
	}

	@Test
	void theRuntimeRoleCannotRewriteARunsConfigurationOrHistory() {
		db.inRolledBackTransaction((f) -> {
			UUID run = run(f, f.simulation(), "1.00");
			assertFails(INSUFFICIENT_PRIVILEGE, () -> f.jdbc.update("UPDATE bot_run SET seed = 8 WHERE id = ?", run));
		});
		db.inRolledBackTransaction((f) -> {
			UUID run = run(f, f.simulation(), "1.00");
			assertFails(INSUFFICIENT_PRIVILEGE,
					() -> f.jdbc.update("UPDATE bot_run SET started_at = now() - interval '1 day' WHERE id = ?", run));
		});
		db.inRolledBackTransaction((f) -> {
			UUID run = run(f, f.simulation(), "1.00");
			assertFails(INSUFFICIENT_PRIVILEGE, () -> f.jdbc.update("DELETE FROM bot_run WHERE id = ?", run));
		});
		db.inRolledBackTransaction((f) -> assertFails(INSUFFICIENT_PRIVILEGE,
				() -> f.jdbc.update("UPDATE bot_assignment SET strategy = 'NOISE'")));
		db.inRolledBackTransaction((f) -> assertFails(INSUFFICIENT_PRIVILEGE,
				() -> f.jdbc.update("DELETE FROM bot_run_instrument")));
	}

	private static UUID run(SchemaFixtures f, UUID simulation, String rate) {
		UUID id = UUID.randomUUID();
		f.jdbc.update("""
				INSERT INTO bot_run (id, simulation_id, desired_state, seed, commands_per_second, max_order_quantity,
				    max_position, started_by, started_at, start_sequence)
				VALUES (?, ?, 'RUNNING', 7, ?, 10, 100, ?, now(), 1)""", id, simulation, new BigDecimal(rate), f.user());
		return id;
	}

}
