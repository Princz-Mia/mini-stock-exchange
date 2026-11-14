package com.mia_princz.mse.execution.trading.application;

import java.util.List;
import java.util.UUID;

/**
 * The outcome of adding a trader login. Refusals are committed and stored like any result, so a retry with the same
 * key answers the same way. It never holds the password or its hash.
 *
 * @param accountId null for a refusal, or for a trader added without an account
 * @param cashCents the starting cash asked for, kept on a refusal too, or null when no account was asked for
 */
public record TraderResult(Status status, String username, UUID accountId, Long cashCents,
		List<ProvisionAccount.Holding> holdings, long commandSequence) {

	public enum Status {

		CREATED, NOT_IN_SETUP, USERNAME_TAKEN, UNKNOWN_INSTRUMENT

	}

}
