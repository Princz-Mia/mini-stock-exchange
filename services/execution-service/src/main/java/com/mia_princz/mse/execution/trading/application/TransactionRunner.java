package com.mia_princz.mse.execution.trading.application;

import java.util.function.Supplier;

/** Runs work in one database transaction: committed if it returns, rolled back if it throws. */
public interface TransactionRunner {

	<T> T inTransaction(Supplier<T> work);

	/**
	 * The work finished but the commit failed in a way that may or may not have persisted it. The caller must treat
	 * the database state as unknown.
	 */
	final class CommitOutcomeUnknownException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		public CommitOutcomeUnknownException(Throwable cause) {
			super("Commit outcome is unknown", cause);
		}

	}

}
