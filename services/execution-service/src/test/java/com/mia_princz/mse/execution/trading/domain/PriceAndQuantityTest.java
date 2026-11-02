package com.mia_princz.mse.execution.trading.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.List;

import org.junit.jupiter.api.Test;

class PriceAndQuantityTest {

	@Test
	void everyPriceInRangeRoundTrips() {
		assertThat(Samples.between(Price.MIN_CENTS, Price.MAX_CENTS)).allSatisfy((cents) -> {
			Price price = new Price(cents);
			assertThat(Price.parse(price.format())).isEqualTo(price);
		});
	}

	@Test
	void everyPriceOutOfRangeIsRejected() {
		assertThat(Samples.outside(Price.MIN_CENTS, Price.MAX_CENTS)).isNotEmpty().allSatisfy(
				(cents) -> assertThatThrownBy(() -> new Price(cents)).isInstanceOf(InvalidAmountException.class));
	}

	@Test
	void everyQuantityOutOfRangeIsRejected() {
		assertThat(Samples.outside(Quantity.MIN_SHARES, Quantity.MAX_SHARES)).isNotEmpty().allSatisfy(
				(shares) -> assertThatThrownBy(() -> new Quantity(shares)).isInstanceOf(InvalidAmountException.class));
	}

	@Test
	void notionalIsExactAcrossTheWholeRange() {
		List<Long> prices = Samples.between(Price.MIN_CENTS, Price.MAX_CENTS);
		List<Long> quantities = Samples.between(Quantity.MIN_SHARES, Quantity.MAX_SHARES);

		for (int i = 0; i < prices.size(); i++) {
			long cents = prices.get(i);
			long shares = quantities.get(i);
			assertThat(BigInteger.valueOf(new Price(cents).times(new Quantity(shares)).cents())).as("%d x %d", cents, shares)
				.isEqualTo(BigInteger.valueOf(cents).multiply(BigInteger.valueOf(shares)));
		}
	}

	@Test
	void boundsMatchTheContract() {
		assertThat(new Price(Price.MIN_CENTS).format()).isEqualTo("0.01");
		assertThat(new Price(Price.MAX_CENTS).format()).isEqualTo("1000000.00");
		assertThatThrownBy(() -> Price.parse("0.00")).isInstanceOf(InvalidAmountException.class);
		assertThatThrownBy(() -> Price.parse("1000000.01")).isInstanceOf(InvalidAmountException.class);
		assertThatThrownBy(() -> Price.parse("-1.00")).isInstanceOf(InvalidAmountException.class);
		assertThatThrownBy(() -> new Quantity(0)).isInstanceOf(InvalidAmountException.class);
		assertThatThrownBy(() -> new Quantity(10_001)).isInstanceOf(InvalidAmountException.class);
	}

	@Test
	void maximumNotionalIsTenBillionDollars() {
		Money maximum = new Price(Price.MAX_CENTS).times(new Quantity(Quantity.MAX_SHARES));

		assertThat(maximum.format()).isEqualTo("10000000000.00");
	}

	/** The golden fixture: fills of 3 and 4 at 100.00 and 3 at 101.00 against a buyer bound of 101.00. */
	@Test
	void goldenFillsSpendAndPriceImprovement() {
		Price bound = Price.parse("101.00");
		Money spend = Price.parse("100.00")
			.times(new Quantity(3))
			.plus(Price.parse("100.00").times(new Quantity(4)))
			.plus(Price.parse("101.00").times(new Quantity(3)));
		Money reserved = bound.times(new Quantity(10));

		assertThat(spend.cents()).isEqualTo(100_300);
		assertThat(reserved.minus(spend).cents()).as("price improvement").isEqualTo(700);
	}

	/** The alternative fixture: 6 fill at 100.00, 4 keep resting, and cancelling releases the rest of the hold. */
	@Test
	void goldenPartialFillHold() {
		Price bound = Price.parse("101.00");
		Money spend = Price.parse("100.00").times(new Quantity(6));

		assertThat(spend.cents()).isEqualTo(60_000);
		assertThat(bound.times(new Quantity(6)).minus(spend).cents()).as("released").isEqualTo(600);
		assertThat(bound.times(new Quantity(4)).cents()).as("held for working shares").isEqualTo(40_400);
	}

}
