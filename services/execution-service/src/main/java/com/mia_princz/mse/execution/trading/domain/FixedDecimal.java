package com.mia_princz.mse.execution.trading.domain;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * The only accepted text form of an amount: exactly two decimals, as in {@code 100.25}. The pattern rejects exponents,
 * NaN, signs other than a leading minus, leading zeros, whitespace and non-ASCII digits before BigDecimal sees them.
 */
final class FixedDecimal {

	private static final Pattern FORMAT = Pattern.compile("-?(0|[1-9][0-9]{0,18})\\.[0-9]{2}");

	private FixedDecimal() {
	}

	static long parseCents(String text) {
		if (text == null || !FORMAT.matcher(text).matches() || "-0.00".equals(text)) {
			throw new InvalidAmountException("Amount must be a fixed-scale decimal such as 100.25");
		}
		try {
			return new BigDecimal(text).movePointRight(2).longValueExact();
		}
		catch (ArithmeticException ex) {
			throw new InvalidAmountException("Amount is outside the supported range");
		}
	}

	static String format(long cents) {
		return BigDecimal.valueOf(cents, 2).toPlainString();
	}

}
