package com.mia_princz.mse.execution.trading.application;

import java.util.Objects;
import java.util.UUID;

/** Cancels an order on behalf of the authenticated account; another account's order is treated as not found. */
public record CancelOrder(UUID orderId, UUID accountId) {

	public CancelOrder {
		Objects.requireNonNull(orderId, "orderId");
		Objects.requireNonNull(accountId, "accountId");
	}

}
