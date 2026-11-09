package com.mia_princz.mse.execution.trading.application;

import java.util.UUID;

/** What revoking did: how many active tokens it revoked, none if they already were, or no such bot. */
public record TokenRevocation(Status status, UUID accountId, int revoked, long commandSequence) {

	public enum Status {

		REVOKED, ALREADY_REVOKED, NOT_A_BOT

	}

}
