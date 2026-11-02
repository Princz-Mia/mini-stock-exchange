package com.mia_princz.mse.execution.trading.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

import org.junit.jupiter.api.Test;

class MoneyTest {

	private static final BigInteger MIN = BigInteger.valueOf(Long.MIN_VALUE);

	private static final BigInteger MAX = BigInteger.valueOf(Long.MAX_VALUE);

	@Test
	void formatAndParseRoundTripForEveryLong() {
		assertThat(Samples.longs()).allSatisfy((cents) -> {
			Money money = new Money(cents);
			assertThat(Money.parse(money.format())).isEqualTo(money);
		});
	}

	@Test
	void formatAlwaysHasExactlyTwoDecimalsAndNoExponent() {
		assertThat(Samples.longs())
			.allSatisfy((cents) -> assertThat(new Money(cents).format()).matches("-?[0-9]+\\.[0-9]{2}"));
	}

	@Test
	void formattedValueIsExactlyTheCentsOverOneHundred() {
		assertThat(Samples.longs()).allSatisfy(
				(cents) -> assertThat(new BigDecimal(new Money(cents).format()).movePointRight(2).toBigIntegerExact())
					.isEqualTo(BigInteger.valueOf(cents)));
	}

	@Test
	void additionIsExactOrThrows() {
		assertThat(Samples.longPairs()).allSatisfy((pair) -> {
			Money left = new Money(pair[0]);
			Money right = new Money(pair[1]);
			BigInteger exact = BigInteger.valueOf(pair[0]).add(BigInteger.valueOf(pair[1]));
			if (inRange(exact)) {
				assertThat(left.plus(right).cents()).isEqualTo(exact.longValueExact());
			}
			else {
				assertThatThrownBy(() -> left.plus(right)).isInstanceOf(ArithmeticException.class);
			}
		});
	}

	@Test
	void subtractionIsExactOrThrows() {
		assertThat(Samples.longPairs()).allSatisfy((pair) -> {
			Money left = new Money(pair[0]);
			Money right = new Money(pair[1]);
			BigInteger exact = BigInteger.valueOf(pair[0]).subtract(BigInteger.valueOf(pair[1]));
			if (inRange(exact)) {
				assertThat(left.minus(right).cents()).isEqualTo(exact.longValueExact());
			}
			else {
				assertThatThrownBy(() -> left.minus(right)).isInstanceOf(ArithmeticException.class);
			}
		});
	}

	@Test
	void overflowAtTheEdgesThrowsInsteadOfWrapping() {
		assertThatThrownBy(() -> new Money(Long.MAX_VALUE).plus(new Money(1))).isInstanceOf(ArithmeticException.class);
		assertThatThrownBy(() -> new Money(Long.MIN_VALUE).minus(new Money(1))).isInstanceOf(ArithmeticException.class);
		assertThatThrownBy(() -> new Money(Long.MIN_VALUE).negated()).isInstanceOf(ArithmeticException.class);
	}

	@Test
	void formatsTheExtremes() {
		assertThat(new Money(Long.MAX_VALUE).format()).isEqualTo("92233720368547758.07");
		assertThat(new Money(Long.MIN_VALUE).format()).isEqualTo("-92233720368547758.08");
		assertThat(new Money(5).format()).isEqualTo("0.05");
		assertThat(new Money(-5).format()).isEqualTo("-0.05");
		assertThat(Money.ZERO.format()).isEqualTo("0.00");
	}

	@Test
	void rejectsEveryNonCanonicalForm() {
		List<String> invalid = List.of("", " ", "100", "100.2", "100.255", "100.", ".25", "+1.00", "01.00", "00.00", "-0.00",
				"1e2", "1E+2", "1.00e0", "NaN", "Infinity", "-Infinity", "0x10.00", " 1.00", "1.00 ", "1,00", "1_000.00",
				"١٠٠.٢٥", "92233720368547758.08", "-92233720368547758.09", "9999999999999999999.00",
				"99999999999999999999999999.00");

		assertThat(invalid).allSatisfy(
				(text) -> assertThatThrownBy(() -> Money.parse(text)).as("'%s'", text).isInstanceOf(InvalidAmountException.class));
		assertThatThrownBy(() -> Money.parse(null)).isInstanceOf(InvalidAmountException.class);
	}

	private static boolean inRange(BigInteger value) {
		return value.compareTo(MIN) >= 0 && value.compareTo(MAX) <= 0;
	}

}
