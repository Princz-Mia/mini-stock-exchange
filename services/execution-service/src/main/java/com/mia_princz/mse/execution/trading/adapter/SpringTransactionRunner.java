package com.mia_princz.mse.execution.trading.adapter;

import java.util.function.Supplier;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import com.mia_princz.mse.execution.trading.application.TransactionRunner;

/**
 * Explicit transactions, not {@code @Transactional}: the writer thread calls this directly, so there is no proxy to
 * miss and no propagation across threads. A failed commit after successful work is reported as an unknown outcome,
 * whatever exception the transaction manager translated it to, unless the database certainly rolled it back
 * ({@link CommitFailures}).
 */
public final class SpringTransactionRunner implements TransactionRunner {

	private final PlatformTransactionManager transactionManager;

	public SpringTransactionRunner(PlatformTransactionManager transactionManager) {
		this.transactionManager = transactionManager;
	}

	@Override
	public <T> T inTransaction(Supplier<T> work) {
		TransactionStatus status = transactionManager.getTransaction(new DefaultTransactionDefinition());
		T result;
		try {
			result = work.get();
		}
		catch (RuntimeException | Error ex) {
			try {
				transactionManager.rollback(status);
			}
			catch (RuntimeException | Error rollback) {
				// The work's own failure says what went wrong, a halt above all; a lost connection undoes the
				// transaction anyway.
				ex.addSuppressed(rollback);
			}
			throw ex;
		}
		try {
			transactionManager.commit(status);
		}
		catch (RuntimeException ex) {
			if (CommitFailures.certainlyRolledBack(ex)) {
				throw ex;
			}
			throw new CommitOutcomeUnknownException(ex);
		}
		catch (Error ex) {
			// Out of memory while reading the commit's answer, say: the database may well have committed.
			throw new CommitOutcomeUnknownException(ex);
		}
		return result;
	}

}
