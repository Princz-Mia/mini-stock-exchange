package com.mia_princz.mse.execution.identity;

import java.util.Objects;
import java.util.UUID;

/** A bot authenticated by its API token. It acts only for the one account the token belongs to. */
public record BotPrincipal(UUID tokenId, UUID accountId) {

	public BotPrincipal {
		Objects.requireNonNull(tokenId, "tokenId");
		Objects.requireNonNull(accountId, "accountId");
	}

}
