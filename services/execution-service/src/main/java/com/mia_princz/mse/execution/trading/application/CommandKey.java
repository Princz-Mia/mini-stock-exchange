package com.mia_princz.mse.execution.trading.application;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Identifies one client intent: the principal's idempotency key, plus the route and a SHA-256 hash of the canonical
 * request, so a key reused for a different request can be told apart from a retry.
 */
public record CommandKey(UUID principalId, UUID idempotencyKey, String route, byte[] requestHash) {

	public CommandKey {
		Objects.requireNonNull(principalId, "principalId");
		Objects.requireNonNull(idempotencyKey, "idempotencyKey");
		Objects.requireNonNull(route, "route");
		if (requestHash == null || requestHash.length != 32) {
			throw new IllegalArgumentException("requestHash must be a SHA-256 digest");
		}
		requestHash = requestHash.clone();
	}

	@Override
	public byte[] requestHash() {
		return requestHash.clone();
	}

	/** Whether a stored result was produced by this same request. */
	public boolean sameRequest(String storedRoute, byte[] storedHash) {
		return route.equals(storedRoute) && Arrays.equals(requestHash, storedHash);
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof CommandKey key && principalId.equals(key.principalId)
				&& idempotencyKey.equals(key.idempotencyKey) && route.equals(key.route)
				&& Arrays.equals(requestHash, key.requestHash);
	}

	@Override
	public int hashCode() {
		return Objects.hash(principalId, idempotencyKey, route, Arrays.hashCode(requestHash));
	}

	@Override
	public String toString() {
		return "CommandKey[principalId=" + principalId + ", idempotencyKey=" + idempotencyKey + ", route=" + route + "]";
	}

}
