package com.mia_princz.mse.execution.trading.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Issues a bot's API token. The token itself goes once to the simulator's secret store; only its hash comes back, to be
 * stored. Called before the provisioning transaction commits, so if handing a token over fails, nothing is committed.
 */
public interface BotCredentials {

	byte[] issue(UUID accountId);

	/**
	 * Issues a token for each account, in order, and returns their hashes in the same order. Once it returns, every
	 * token is in the secret store for good; if it throws, the caller commits none of the accounts. A store that can
	 * hand many tokens over at once for less than one at a time does so here.
	 */
	default List<byte[]> issueAll(List<UUID> accountIds) {
		List<byte[]> hashes = new ArrayList<>(accountIds.size());
		for (UUID accountId : accountIds) {
			hashes.add(issue(accountId));
		}
		return hashes;
	}

	/**
	 * Removes the tokens handed over for these accounts from the secret store: once they have been revoked for good,
	 * or for accounts that were never committed. Tidying, not security: a revoked token, or one for an account that
	 * does not exist, authenticates nothing wherever it is still written down. So it never fails: whatever cannot be
	 * removed is left.
	 */
	default void discard(Collection<UUID> accountIds) {
	}

}
