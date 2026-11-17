package com.mia_princz.mse.execution.trading.domain;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * How many orders each account has resting, across every book that shares this tally. The books keep it as orders
 * enter and leave them, so it changes exactly when they do: after a commit, or while they are rebuilt. It replaces a
 * count in the database for each order that would rest. Not final, so a test can make it fail.
 */
public class RestingOrders {

	private final Map<UUID, Integer> byAccount = new HashMap<>();

	/** An order of the account entered a book. */
	public void added(UUID accountId) {
		byAccount.merge(accountId, 1, Integer::sum);
	}

	/** An order of the account left a book; it must have entered one. */
	public void removed(UUID accountId) {
		Integer count = byAccount.get(accountId);
		if (count == null) {
			throw new IllegalStateException("No resting order of account " + accountId + " to remove");
		}
		if (count == 1) {
			byAccount.remove(accountId);
		}
		else {
			byAccount.put(accountId, count - 1);
		}
	}

	/** The account's resting orders, in every book that shares this tally. */
	public int of(UUID accountId) {
		return byAccount.getOrDefault(accountId, 0);
	}

	/** Every account with an order resting, and how many. */
	public Map<UUID, Integer> counts() {
		return Map.copyOf(byAccount);
	}

	/** Forgets every count, before the books are rebuilt. */
	public void clear() {
		byAccount.clear();
	}

}
