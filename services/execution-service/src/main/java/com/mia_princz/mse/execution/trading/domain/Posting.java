package com.mia_princz.mse.execution.trading.domain;

import java.util.Objects;
import java.util.UUID;

/** One signed ledger delta. A null instrument means cash in cents; otherwise shares of that instrument. */
public record Posting(UUID accountId, UUID instrumentId, Bucket bucket, long delta) {

	public enum Bucket {

		AVAILABLE, RESERVED

	}

	public Posting {
		Objects.requireNonNull(accountId, "accountId");
		Objects.requireNonNull(bucket, "bucket");
		if (delta == 0) {
			throw new IllegalArgumentException("Zero postings are omitted, never recorded");
		}
	}

	public boolean isCash() {
		return instrumentId == null;
	}

}
