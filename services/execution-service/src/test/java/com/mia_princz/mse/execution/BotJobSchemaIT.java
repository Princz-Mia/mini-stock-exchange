package com.mia_princz.mse.execution;

import static com.mia_princz.mse.execution.SchemaFixtures.CHECK_VIOLATION;
import static com.mia_princz.mse.execution.SchemaFixtures.INSUFFICIENT_PRIVILEGE;
import static com.mia_princz.mse.execution.SchemaFixtures.UNIQUE_VIOLATION;
import static com.mia_princz.mse.execution.SchemaFixtures.assertFails;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** The bot job table, as the runtime role sees it. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BotJobSchemaIT {

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
	void aJobRunsMakesItsBotsAndIsDone() {
		db.inRolledBackTransaction((f) -> {
			UUID job = job(f, f.simulation(), 2, 3, 1);
			f.jdbc.update("""
					UPDATE bot_job SET created_market_makers = 2, created_noise = 3, created_momentum = 1, parts_done = 3,
					    last_part_at = now(), state = 'DONE', finished_at = now() WHERE id = ?""", job);
			assertThat(f.jdbc.queryForObject("SELECT state FROM bot_job WHERE id = ?", String.class, job))
				.isEqualTo("DONE");
		});
	}

	@Test
	void oneJobRunsAtATimeInASimulation() {
		db.inRolledBackTransaction((f) -> {
			UUID simulation = f.simulation();
			job(f, simulation, 0, 5, 0);
			assertFails(UNIQUE_VIOLATION, () -> job(f, simulation, 0, 5, 0));
		});
		db.inRolledBackTransaction((f) -> {
			UUID simulation = f.simulation();
			UUID first = job(f, simulation, 0, 5, 0);
			f.jdbc.update("UPDATE bot_job SET state = 'STOPPED', finished_at = now() WHERE id = ?", first);
			job(f, simulation, 0, 5, 0);
			assertThat(f.jdbc.queryForObject("SELECT count(*) FROM bot_job WHERE simulation_id = ?", Long.class,
					simulation)).isEqualTo(2);
		});
	}

	@Test
	void aJobsStateAgreesWithWhatItRecords() {
		// Done only with every bot made; finished exactly when not running; a failure exactly when failed.
		db.inRolledBackTransaction((f) -> {
			UUID job = job(f, f.simulation(), 1, 1, 1);
			assertFails(CHECK_VIOLATION,
					() -> f.jdbc.update("UPDATE bot_job SET state = 'DONE', finished_at = now() WHERE id = ?", job));
		});
		db.inRolledBackTransaction((f) -> {
			UUID job = job(f, f.simulation(), 1, 1, 1);
			assertFails(CHECK_VIOLATION, () -> f.jdbc.update("UPDATE bot_job SET state = 'STOPPED' WHERE id = ?", job));
		});
		db.inRolledBackTransaction((f) -> {
			UUID job = job(f, f.simulation(), 1, 1, 1);
			assertFails(CHECK_VIOLATION,
					() -> f.jdbc.update("UPDATE bot_job SET state = 'FAILED', finished_at = now() WHERE id = ?", job));
		});
		db.inRolledBackTransaction((f) -> {
			UUID job = job(f, f.simulation(), 1, 1, 1);
			assertFails(CHECK_VIOLATION, () -> f.jdbc.update("UPDATE bot_job SET created_noise = 2 WHERE id = ?", job));
		});
		db.inRolledBackTransaction((f) -> assertFails(CHECK_VIOLATION, () -> job(f, f.simulation(), 0, 0, 0)));
		db.inRolledBackTransaction(
				(f) -> assertFails(CHECK_VIOLATION, () -> job(f, f.simulation(), Integer.MAX_VALUE, 1, 0)));
	}

	@Test
	void whatAJobAskedForNeverChanges() {
		db.inRolledBackTransaction((f) -> {
			UUID job = job(f, f.simulation(), 1, 1, 1);
			assertFails(INSUFFICIENT_PRIVILEGE, () -> f.jdbc.update("UPDATE bot_job SET noise = 5 WHERE id = ?", job));
		});
		db.inRolledBackTransaction((f) -> {
			UUID job = job(f, f.simulation(), 1, 1, 1);
			assertFails(INSUFFICIENT_PRIVILEGE,
					() -> f.jdbc.update("UPDATE bot_job SET cash_cents = 5 WHERE id = ?", job));
		});
		db.inRolledBackTransaction((f) -> {
			UUID job = job(f, f.simulation(), 1, 1, 1);
			assertFails(INSUFFICIENT_PRIVILEGE, () -> f.jdbc.update("DELETE FROM bot_job WHERE id = ?", job));
		});
	}

	private static UUID job(SchemaFixtures f, UUID simulation, int makers, int noise, int momentum) {
		UUID id = UUID.randomUUID();
		f.jdbc.update("""
				INSERT INTO bot_job (id, simulation_id, started_by, idempotency_key, state, part_size, market_makers, noise,
				    momentum, created_market_makers, created_noise, created_momentum, parts_done, cash_cents, holdings,
				    started_at, run_started_at, created_at_run_start, resumed)
				VALUES (?, ?, ?, ?, 'RUNNING', 1000, ?, ?, ?, 0, 0, 0, 0, 100, '[]', now(), now(), 0, 0)""", id,
				simulation, f.user(), UUID.randomUUID(), makers, noise, momentum);
		return id;
	}

}
