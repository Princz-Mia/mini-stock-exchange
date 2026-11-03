package com.mia_princz.mse.execution.trading.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * An accepted order arriving at the book. {@code bound} is the limit price of a limit order and the protection price of
 * a protected market order: a ceiling for a buy, a floor for a sell. The sequence comes from the single writer and
 * decides time priority; wall-clock time never does.
 */
public record OrderRequest(UUID orderId, UUID accountId, Side side, OrderType type, Price bound, Quantity quantity,
		long acceptedSequence) {

	public OrderRequest {
		Objects.requireNonNull(orderId, "orderId");
		Objects.requireNonNull(accountId, "accountId");
		Objects.requireNonNull(side, "side");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(bound, "bound");
		Objects.requireNonNull(quantity, "quantity");
	}

	/** Whether a resting order at this price can trade with this order. */
	boolean crosses(long restingPriceCents) {
		return side == Side.BUY ? restingPriceCents <= bound.cents() : restingPriceCents >= bound.cents();
	}

}
