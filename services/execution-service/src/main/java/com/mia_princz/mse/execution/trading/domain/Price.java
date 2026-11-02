package com.mia_princz.mse.execution.trading.domain;

/**
 * An order price or protection bound, from USD 0.01 to USD 1,000,000.00. The tick is one cent, so every value in range
 * is on a tick. The bounds imply a maximum notional of USD 10,000,000,000.00, which a long holds comfortably.
 */
public record Price(long cents) implements Comparable<Price> {

	public static final long MIN_CENTS = 1;

	public static final long MAX_CENTS = 100_000_000;

	public Price {
		if (cents < MIN_CENTS || cents > MAX_CENTS) {
			throw new InvalidAmountException("Price must be between 0.01 and 1000000.00");
		}
	}

	public static Price parse(String text) {
		return new Price(FixedDecimal.parseCents(text));
	}

	public Money times(Quantity quantity) {
		return new Money(Math.multiplyExact(cents, quantity.shares()));
	}

	public String format() {
		return FixedDecimal.format(cents);
	}

	@Override
	public int compareTo(Price other) {
		return Long.compare(cents, other.cents);
	}

}
