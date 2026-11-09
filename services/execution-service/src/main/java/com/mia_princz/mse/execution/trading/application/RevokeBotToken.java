package com.mia_princz.mse.execution.trading.application;

import java.util.Objects;
import java.util.UUID;

/** Revokes every active token of one bot account, in any market state: a leaked token must be stoppable at once. */
public record RevokeBotToken(UUID accountId, UUID actorUserId) {

	public RevokeBotToken {
		Objects.requireNonNull(accountId, "accountId");
		Objects.requireNonNull(actorUserId, "actorUserId");
	}

}
