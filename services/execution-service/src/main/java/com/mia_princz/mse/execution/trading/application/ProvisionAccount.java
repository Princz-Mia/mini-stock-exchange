package com.mia_princz.mse.execution.trading.application;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * An operator gives a trader their account and starting endowment while the market is in SETUP. The endowment comes
 * from the issuance account in one balanced genesis batch; nothing is granted after the market opens.
 */
public record ProvisionAccount(UUID accountId, UUID ownerUserId, String username, long cashCents,
		List<Holding> holdings, UUID actorUserId) {

	public record Holding(UUID instrumentId, long quantity) {
	}

	public ProvisionAccount {
		Objects.requireNonNull(accountId, "accountId");
		Objects.requireNonNull(ownerUserId, "ownerUserId");
		Objects.requireNonNull(username, "username");
		Objects.requireNonNull(actorUserId, "actorUserId");
		holdings = List.copyOf(holdings);
		if (cashCents < 0) {
			throw new IllegalArgumentException("cashCents must not be negative");
		}
	}

}
