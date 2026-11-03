package com.mia_princz.mse.execution.trading.application;

import java.util.List;

/** Startup found balances, the ledger or open orders out of agreement. Trading must not start; nothing is repaired. */
public final class ReconciliationException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final List<String> problems;

	public ReconciliationException(List<String> problems) {
		super("Trading state does not reconcile: " + String.join("; ", problems));
		this.problems = List.copyOf(problems);
	}

	public List<String> problems() {
		return problems;
	}

}
