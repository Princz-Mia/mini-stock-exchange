package com.mia_princz.mse.execution.trading.application;

import java.util.Objects;
import java.util.UUID;

import com.mia_princz.mse.execution.trading.domain.OrderType;
import com.mia_princz.mse.execution.trading.domain.Price;
import com.mia_princz.mse.execution.trading.domain.Quantity;
import com.mia_princz.mse.execution.trading.domain.Side;

/** A validated order placement. The account comes from the authenticated principal, never from the request body. */
public record PlaceOrder(UUID orderId, UUID accountId, UUID instrumentId, Side side, OrderType type, Price bound,
		Quantity quantity) {

	public PlaceOrder {
		Objects.requireNonNull(orderId, "orderId");
		Objects.requireNonNull(accountId, "accountId");
		Objects.requireNonNull(instrumentId, "instrumentId");
		Objects.requireNonNull(side, "side");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(bound, "bound");
		Objects.requireNonNull(quantity, "quantity");
	}

}
