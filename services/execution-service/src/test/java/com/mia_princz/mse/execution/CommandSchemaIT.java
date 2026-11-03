package com.mia_princz.mse.execution;

import static com.mia_princz.mse.execution.SchemaFixtures.CHECK_VIOLATION;
import static com.mia_princz.mse.execution.SchemaFixtures.INSUFFICIENT_PRIVILEGE;
import static com.mia_princz.mse.execution.SchemaFixtures.UNIQUE_VIOLATION;
import static com.mia_princz.mse.execution.SchemaFixtures.assertFails;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CommandSchemaIT {

	private static final String INSERT_RESULT = """
			INSERT INTO command_result (simulation_id, principal_id, idempotency_key, route, request_hash, response_status,
			    response_body, command_sequence)
			VALUES (?, ?, ?, 'POST /api/v1/orders', ?, 201, '{"orderId":"x"}'::jsonb, ?)""";

	private static final String INSERT_EVENT = """
			INSERT INTO outbox (event_id, topic, event_key, schema_fingerprint, payload, command_sequence)
			VALUES (?, ?, 'key', 42, ?, 1)""";

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
	void oneResultPerPrincipalAndKey() {
		db.inRolledBackTransaction((f) -> {
			UUID simulation = f.simulation();
			UUID principal = UUID.randomUUID();
			UUID key = UUID.randomUUID();
			f.jdbc.update(INSERT_RESULT, simulation, principal, key, new byte[32], 1L);
			assertFails(UNIQUE_VIOLATION, () -> f.jdbc.update(INSERT_RESULT, simulation, principal, key, new byte[32], 2L));
		});
		db.inRolledBackTransaction((f) -> {
			UUID simulation = f.simulation();
			UUID key = UUID.randomUUID();
			f.jdbc.update(INSERT_RESULT, simulation, UUID.randomUUID(), key, new byte[32], 1L);
			f.jdbc.update(INSERT_RESULT, simulation, UUID.randomUUID(), key, new byte[32], 2L);
			assertThat(f.jdbc.queryForObject("SELECT count(*) FROM command_result", Long.class))
				.as("the same key is fine for another principal")
				.isEqualTo(2);
		});
	}

	@Test
	void eachCommandSequenceHasOneResultAndHashesAreSha256Sized() {
		db.inRolledBackTransaction((f) -> {
			UUID simulation = f.simulation();
			f.jdbc.update(INSERT_RESULT, simulation, UUID.randomUUID(), UUID.randomUUID(), new byte[32], 5L);
			assertFails(UNIQUE_VIOLATION,
					() -> f.jdbc.update(INSERT_RESULT, simulation, UUID.randomUUID(), UUID.randomUUID(), new byte[32], 5L));
		});
		db.inRolledBackTransaction((f) -> assertFails(CHECK_VIOLATION, () -> f.jdbc.update(INSERT_RESULT, f.simulation(),
				UUID.randomUUID(), UUID.randomUUID(), new byte[16], 1L)));
	}

	@Test
	void storedResultsNeverChange() {
		db.inRolledBackTransaction((f) -> {
			UUID simulation = f.simulation();
			UUID key = UUID.randomUUID();
			f.jdbc.update(INSERT_RESULT, simulation, UUID.randomUUID(), key, new byte[32], 1L);
			assertFails(INSUFFICIENT_PRIVILEGE,
					() -> f.jdbc.update("UPDATE command_result SET response_status = 500 WHERE idempotency_key = ?", key));
		});
	}

	@Test
	void outboxIsReadInOrderAndPublishedOnce() {
		db.inRolledBackTransaction((f) -> {
			UUID first = event(f);
			UUID second = event(f);
			List<UUID> pending = f.jdbc.queryForList(
					"SELECT event_id FROM outbox WHERE published_at IS NULL ORDER BY id", UUID.class);
			assertThat(pending).containsExactly(first, second);

			f.jdbc.update("UPDATE outbox SET published_at = now() WHERE event_id = ?", first);
			assertThat(f.jdbc.queryForList("SELECT event_id FROM outbox WHERE published_at IS NULL ORDER BY id", UUID.class))
				.containsExactly(second);
			assertFails(CHECK_VIOLATION,
					() -> f.jdbc.update("UPDATE outbox SET published_at = NULL WHERE event_id = ?", first));
		});
	}

	@Test
	void outboxPayloadAndIdentityAreImmutable() {
		db.inRolledBackTransaction((f) -> {
			UUID event = event(f);
			assertFails(INSUFFICIENT_PRIVILEGE,
					() -> f.jdbc.update("UPDATE outbox SET payload = '\\x01'::bytea WHERE event_id = ?", event));
		});
		db.inRolledBackTransaction((f) -> {
			UUID event = event(f);
			assertFails(INSUFFICIENT_PRIVILEGE, () -> f.jdbc.update("DELETE FROM outbox WHERE event_id = ?", event));
		});
		db.inRolledBackTransaction((f) -> {
			UUID event = event(f);
			assertFails(UNIQUE_VIOLATION,
					() -> f.jdbc.update(INSERT_EVENT, event, "mse.trades.v1", new byte[] { (byte) 0xC3, 1 }));
		});
	}

	/**
	 * History is protected twice: the runtime role lacks the privileges, and triggers reject changes for any role. Both
	 * layers raise the same error, so this checks the privileges directly; otherwise either layer could vanish unnoticed.
	 */
	@Test
	void runtimeRoleHasNoPrivilegeToRewriteHistory() {
		List<String> historyTables = List.of("journal_batch", "ledger_entry", "initial_allocation", "trades",
				"command_result");
		for (String table : historyTables) {
			for (String privilege : List.of("UPDATE", "DELETE", "TRUNCATE")) {
				assertThat(db.jdbc.queryForObject("SELECT has_table_privilege(current_user, ?, ?)", Boolean.class, table,
						privilege))
					.as("%s on %s", privilege, table)
					.isFalse();
			}
		}
		assertThat(db.jdbc.queryForObject("SELECT has_table_privilege(current_user, 'outbox', 'DELETE')", Boolean.class))
			.isFalse();
		assertThat(db.jdbc.queryForObject("SELECT has_column_privilege(current_user, 'outbox', 'payload', 'UPDATE')",
				Boolean.class))
			.isFalse();
	}

	@Test
	void topicsFollowTheNamingScheme() {
		db.inRolledBackTransaction((f) -> assertFails(CHECK_VIOLATION,
				() -> f.jdbc.update(INSERT_EVENT, UUID.randomUUID(), "trades", new byte[] { 1 })));
	}

	private static UUID event(SchemaFixtures f) {
		UUID id = UUID.randomUUID();
		f.jdbc.update(INSERT_EVENT, id, "mse.trades.v1", new byte[] { (byte) 0xC3, 1 });
		return id;
	}

}
