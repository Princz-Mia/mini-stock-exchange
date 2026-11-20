package com.mia_princz.mse.execution.trading.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;

import com.mia_princz.mse.execution.trading.application.TransactionRunner.CommitOutcomeUnknownException;

/**
 * What a failed commit means, through the transaction manager the service really uses: only a commit the database
 * certainly rolled back (an integrity violation or a transaction rollback, such as a deferred check or a
 * serialization failure) is a plain failure; anything else, a lost connection above all, may have committed, so its
 * outcome is unknown and the writer must halt.
 */
class SpringTransactionRunnerTest {

	@ParameterizedTest
	@ValueSource(strings = { "08006", "08003", "57P01", "XX000", "53100", "40003" })
	void aCommitThatMayHaveSucceededIsAnUnknownOutcome(String sqlState) {
		Database database = new Database(new SQLException("commit failed", sqlState));

		assertThatThrownBy(() -> database.runner().inTransaction(() -> "work"))
			.isInstanceOf(CommitOutcomeUnknownException.class);
	}

	@Test
	void anErrorDuringCommitIsAnUnknownOutcome() {
		Database database = new Database(null);
		database.commitError = new OutOfMemoryError("reading the commit's answer");

		assertThatThrownBy(() -> database.runner().inTransaction(() -> "work"))
			.isInstanceOf(CommitOutcomeUnknownException.class)
			.hasCauseInstanceOf(OutOfMemoryError.class);
	}

	@Test
	void aCommitFailingWithNoStateIsAnUnknownOutcome() {
		Database database = new Database(new SQLException("commit failed"));

		assertThatThrownBy(() -> database.runner().inTransaction(() -> "work"))
			.isInstanceOf(CommitOutcomeUnknownException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = { "23514", "23505", "23503", "40001", "40P01" })
	void aCommitTheDatabaseRolledBackIsAPlainFailure(String sqlState) {
		Database database = new Database(new SQLException("refused at commit", sqlState));

		assertThatThrownBy(() -> database.runner().inTransaction(() -> "work"))
			.isNotInstanceOf(CommitOutcomeUnknownException.class)
			.isInstanceOf(RuntimeException.class)
			.hasRootCauseMessage("refused at commit");
	}

	@Test
	void aRollbackTheTransactionManagerChoseIsCertain() {
		assertThat(CommitFailures.certainlyRolledBack(new UnexpectedRollbackException("rolled back instead"))).isTrue();
		assertThat(CommitFailures.certainlyRolledBack(new IllegalStateException("no database answer"))).isFalse();
	}

	@Test
	void workThatFailsIsRolledBackAndRethrown() {
		Database database = new Database(null);
		IllegalStateException failure = new IllegalStateException("work failed");

		assertThatThrownBy(() -> database.runner().inTransaction(() -> {
			throw failure;
		})).isSameAs(failure);
		assertThat(database.calls).containsSubsequence("rollback").doesNotContain("commit");
	}

	/** A rollback that fails, as on a broken connection, must not hide why the work failed: a halt, say. */
	@Test
	void aRollbackThatFailsKeepsTheWorksOwnFailure() {
		Database database = new Database(null);
		database.rollbackFailure = new SQLException("connection lost", "08006");
		IllegalStateException failure = new IllegalStateException("work failed");

		assertThatThrownBy(() -> database.runner().inTransaction(() -> {
			throw failure;
		})).isSameAs(failure);
		assertThat(failure.getSuppressed()).hasSize(1);
		assertThat(database.calls).contains("rollback");
	}

	@Test
	void aSuccessfulCommitAnswersTheWork() {
		Database database = new Database(null);

		assertThat(database.runner().inTransaction(() -> "work")).isEqualTo("work");
		assertThat(database.calls).contains("commit");
	}

	/** A data source whose one connection fails its commit with {@code commitFailure}, or commits when null. */
	private static final class Database {

		final List<String> calls = new ArrayList<>();

		private final SQLException commitFailure;

		Error commitError;

		SQLException rollbackFailure;

		Database(SQLException commitFailure) {
			this.commitFailure = commitFailure;
		}

		SpringTransactionRunner runner() {
			DataSource dataSource = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(),
					new Class<?>[] { DataSource.class }, (proxy, method, args) -> switch (method.getName()) {
						case "getConnection" -> connection();
						case "hashCode" -> System.identityHashCode(proxy);
						case "equals" -> proxy == args[0];
						case "toString" -> "a test data source";
						case "unwrap" -> null;
						case "isWrapperFor" -> false;
						default -> throw new UnsupportedOperationException(method.getName());
					});
			return new SpringTransactionRunner(new JdbcTransactionManager(dataSource));
		}

		private Connection connection() {
			return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Connection.class },
					(proxy, method, args) -> {
						calls.add(method.getName());
						return switch (method.getName()) {
							case "hashCode" -> System.identityHashCode(proxy);
							case "equals" -> proxy == args[0];
							case "toString" -> "a test connection";
							case "getAutoCommit" -> true;
							case "isReadOnly", "isClosed", "isWrapperFor" -> false;
							case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
							case "commit" -> {
								if (commitError != null) {
									throw commitError;
								}
								if (commitFailure != null) {
									throw commitFailure;
								}
								yield null;
							}
							case "rollback" -> {
								if (rollbackFailure != null) {
									throw rollbackFailure;
								}
								yield null;
							}
							case "unwrap", "getMetaData", "getWarnings" -> null;
							default -> null;
						};
					});
		}

	}

}
