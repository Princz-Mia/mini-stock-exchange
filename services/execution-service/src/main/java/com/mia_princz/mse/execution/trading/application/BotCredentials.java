package com.mia_princz.mse.execution.trading.application;

import java.util.Collection;
import java.util.UUID;

/**
 * Issues a bot's API token. The token itself goes once to the simulator's secret store; only its hash comes back, to be
 * stored. Called inside the provisioning transaction before it commits, so if handing the token over fails, nothing is
 * committed.
 */
public interface BotCredentials {

	byte[] issue(UUID accountId);

	/**
	 * Removes the tokens handed over for these accounts from the secret store, once they have been revoked for good.
	 * Tidying, not security: a revoked token authenticates nothing wherever it is still written down. So it never
	 * fails: whatever cannot be removed is left.
	 */
	default void discard(Collection<UUID> accountIds) {
	}

}
