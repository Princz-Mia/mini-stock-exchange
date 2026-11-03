package com.mia_princz.mse.execution.trading.application;

import java.util.UUID;

import com.mia_princz.mse.execution.trading.domain.RejectionCode;

/**
 * The committed outcome of a placement. {@code state} is the stored order state; {@code reason} explains a cancelled
 * remainder; {@code rejection} is set only when the order was rejected.
 */
public record PlacementResult(UUID orderId, OrderState state, String reason, RejectionCode rejection, long filled,
		long working, long canceled, long commandSequence) {

	public enum OrderState {

		OPEN, PARTIALLY_FILLED, FILLED, CANCELED, REJECTED

	}

	public boolean rejected() {
		return state == OrderState.REJECTED;
	}

}
