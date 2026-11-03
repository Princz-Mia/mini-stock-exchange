package com.mia_princz.mse.execution.trading.domain;

import java.util.UUID;

/** A snapshot of a resting order. */
public record BookOrder(UUID orderId, UUID accountId, Side side, Price price, long remainingQuantity,
		long acceptedSequence) {
}
