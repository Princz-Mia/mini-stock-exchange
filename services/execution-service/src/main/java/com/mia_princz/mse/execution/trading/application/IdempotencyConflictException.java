package com.mia_princz.mse.execution.trading.application;

/** The idempotency key was already used for a different request. Nothing was changed. */
public final class IdempotencyConflictException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public IdempotencyConflictException(CommandKey key) {
		super("Idempotency key " + key.idempotencyKey() + " was already used for a different request");
	}

}
