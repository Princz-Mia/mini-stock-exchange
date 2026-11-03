package com.mia_princz.mse.execution.trading.adapter;

import java.util.function.Supplier;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import com.mia_princz.mse.execution.trading.application.TransactionRunner;

/**
 * Explicit transactions, not {@code @Transactional}: the writer thread calls this directly, so there is no proxy to
 * miss and no propagation across threads. A failed commit after successful work is reported as an unknown outcome.
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
			transactionManager.rollback(status);
			throw ex;
		}
		try {
			transactionManager.commit(status);
		}
		catch (TransactionSystemException ex) {
			throw new CommitOutcomeUnknownException(ex);
		}
		return result;
	}

}
