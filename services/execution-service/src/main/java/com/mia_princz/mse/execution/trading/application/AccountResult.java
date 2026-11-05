package com.mia_princz.mse.execution.trading.application;

import java.util.List;
import java.util.UUID;

/**
 * The outcome of provisioning an account. Refusals are committed and stored like any result, so a retry with the same
 * key answers the same way. {@code accountId} is null for a refusal.
 */
public record AccountResult(Status status, UUID accountId, String username, long cashCents,
		List<ProvisionAccount.Holding> holdings, long commandSequence) {

	public enum Status {

		CREATED, NOT_IN_SETUP, ACCOUNT_EXISTS, UNKNOWN_INSTRUMENT

	}

}
