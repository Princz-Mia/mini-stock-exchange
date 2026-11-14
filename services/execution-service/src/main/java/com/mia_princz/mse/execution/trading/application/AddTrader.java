package com.mia_princz.mse.execution.trading.application;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * An operator adds a trader login while the market is in SETUP, and optionally gives it an account and starting
 * endowment in the same command. The password arrives already hashed: it is hashed before the command is queued, so
 * the writer never spends time on it, and the plaintext never reaches the writer.
 *
 * @param accountId null when the trader gets no account yet; {@code cashCents} and {@code holdings} are then unused
 * @param passwordHash the encoded password, as stored; never part of a result
 */
public record AddTrader(UUID userId, UUID accountId, String username, String passwordHash, long cashCents,
		List<ProvisionAccount.Holding> holdings, UUID actorUserId) {

	public AddTrader {
		Objects.requireNonNull(userId, "userId");
		Objects.requireNonNull(username, "username");
		Objects.requireNonNull(passwordHash, "passwordHash");
		Objects.requireNonNull(actorUserId, "actorUserId");
		holdings = List.copyOf(holdings);
		if (cashCents < 0) {
			throw new IllegalArgumentException("cashCents must not be negative");
		}
	}

	/** Whether the trader gets an account and endowment now. */
	public boolean funded() {
		return accountId != null;
	}

	/** Leaves the password hash out, so a logged command never carries it. */
	@Override
	public String toString() {
		return "AddTrader[userId=" + userId + ", accountId=" + accountId + ", username=" + username + ", cashCents="
				+ cashCents + ", holdings=" + holdings + ", actorUserId=" + actorUserId + "]";
	}

}
