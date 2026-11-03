package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Inserts rows as the runtime role, each test inside a transaction that is always rolled back, so tests share one
 * database without seeing each other. A failing statement aborts its transaction, so each expected failure comes last.
 */
final class SchemaFixtures {

	static final String CHECK_VIOLATION = "23514";

	static final String FOREIGN_KEY_VIOLATION = "23503";

	static final String UNIQUE_VIOLATION = "23505";

	static final String NOT_NULL_VIOLATION = "23502";

	static final String INSUFFICIENT_PRIVILEGE = "42501";

	final JdbcTemplate jdbc;

	private final TransactionTemplate transactions;

	SchemaFixtures(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	void inRolledBackTransaction(Consumer<SchemaFixtures> work) {
		transactions.executeWithoutResult((status) -> {
			status.setRollbackOnly();
			work.accept(this);
		});
	}

	static void assertFails(String sqlState, Runnable statement) {
		assertThatThrownBy(statement::run).satisfies((thrown) -> {
			Throwable cause = NestedExceptionUtils.getMostSpecificCause(thrown);
			assertThat(cause).isInstanceOf(SQLException.class);
			assertThat(((SQLException) cause).getSQLState()).as(cause.getMessage()).isEqualTo(sqlState);
		});
	}

	UUID simulation() {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO simulation (id, state) VALUES (?, 'SETUP')", id);
		return id;
	}

	UUID user() {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO app_user (id, username, password_hash, role) VALUES (?, ?, '{noop}x', 'TRADER')", id,
				"user-" + id);
		return id;
	}

	UUID trader(UUID simulation) {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO account (id, simulation_id, kind, owner_user_id, display_name) VALUES (?, ?, 'TRADER', ?, 'Trader')",
				id, simulation, user());
		return id;
	}

	UUID bot(UUID simulation) {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO account (id, simulation_id, kind, display_name) VALUES (?, ?, 'BOT', 'Bot')", id,
				simulation);
		return id;
	}

	UUID issuance(UUID simulation) {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO account (id, simulation_id, kind, display_name) VALUES (?, ?, 'ISSUANCE', 'Issuance')",
				id, simulation);
		return id;
	}

	UUID instrument(UUID simulation, String symbol) {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO instrument (id, simulation_id, symbol, reference_price_cents) VALUES (?, ?, ?, 10000)", id,
				simulation, symbol);
		return id;
	}

}
