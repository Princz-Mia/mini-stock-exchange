package com.mia_princz.mse.execution.trading.domain;

/** The size of an order in whole shares, from 1 to 10,000. */
public record Quantity(long shares) implements Comparable<Quantity> {

	public static final long MIN_SHARES = 1;

	public static final long MAX_SHARES = 10_000;

	public Quantity {
		if (shares < MIN_SHARES || shares > MAX_SHARES) {
			throw new InvalidAmountException("Quantity must be between 1 and 10000 shares");
		}
	}

	@Override
	public int compareTo(Quantity other) {
		return Long.compare(shares, other.shares);
	}

}
