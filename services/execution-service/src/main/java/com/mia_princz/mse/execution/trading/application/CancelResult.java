package com.mia_princz.mse.execution.trading.application;

import java.util.UUID;

/** The committed outcome of a cancel. {@code canceledQuantity} is what this command cancelled, zero otherwise. */
public record CancelResult(UUID orderId, Status status, long canceledQuantity, long commandSequence) {

	public enum Status {

		/** The working remainder was cancelled and its hold released. */
		CANCELED,

		/** The order was already cancelled; nothing changed, and that is a success. */
		ALREADY_CANCELED,

		/** The order is filled or was rejected, so there is nothing to cancel. */
		NOT_CANCELABLE,

		/** No such order for this account. Another account's order looks exactly the same. */
		NOT_FOUND

	}

}
