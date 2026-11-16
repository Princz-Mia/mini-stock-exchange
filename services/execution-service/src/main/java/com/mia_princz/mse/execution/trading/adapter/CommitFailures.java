package com.mia_princz.mse.execution.trading.adapter;

import java.sql.SQLException;
import java.util.Set;

import org.springframework.transaction.UnexpectedRollbackException;

/**
 * What a failed commit means. Only a failure the database answers with a definite rollback leaves the transaction
 * certainly undone: an integrity violation (SQLSTATE class 23, as a deferred check raises at commit) or a transaction
 * rollback (a serialization failure or a deadlock). Anything else, and a lost connection above all, may have committed
 * before the answer was lost, so its outcome is unknown. Note that SQLSTATE 40003 is "statement completion unknown",
 * which is not a rollback.
 */
final class CommitFailures {

	/** Transaction rollback states that do mean rolled back. */
	private static final Set<String> ROLLED_BACK = Set.of("40000", "40001", "40002", "40P01");

	private CommitFailures() {
	}

	static boolean certainlyRolledBack(Throwable failure) {
		if (failure instanceof UnexpectedRollbackException) {
			return true;
		}
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof SQLException sql) {
				String state = sql.getSQLState();
				return state != null && (state.startsWith("23") || ROLLED_BACK.contains(state));
			}
		}
		return false;
	}

}
