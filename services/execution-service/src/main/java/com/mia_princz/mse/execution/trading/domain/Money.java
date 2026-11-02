package com.mia_princz.mse.execution.trading.domain;

/**
 * A signed amount of virtual USD in whole cents. Signed because journal deltas are negative; whether a balance may go
 * negative is a rule of the ledger, not of this type. Overflow throws {@link ArithmeticException} instead of wrapping.
 */
public record Money(long cents) implements Comparable<Money> {

	public static final Money ZERO = new Money(0);

	public static Money parse(String text) {
		return new Money(FixedDecimal.parseCents(text));
	}

	public Money plus(Money other) {
		return new Money(Math.addExact(cents, other.cents));
	}

	public Money minus(Money other) {
		return new Money(Math.subtractExact(cents, other.cents));
	}

	public Money negated() {
		return new Money(Math.negateExact(cents));
	}

	public boolean isNegative() {
		return cents < 0;
	}

	public String format() {
		return FixedDecimal.format(cents);
	}

	@Override
	public int compareTo(Money other) {
		return Long.compare(cents, other.cents);
	}

}
