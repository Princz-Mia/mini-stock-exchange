package com.mia_princz.mse.execution.trading.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** The average-cost rule against an exact oracle of fractions, with the edges written out by hand. */
class CostBasisTest {

	private static final long SEED = 20261112L;

	@Test
	void startingSharesCountAtTheReferencePrice() {
		CostBasis basis = new CostBasis();
		basis.started(100, 10_000);
		assertThat(basis.quantity()).isEqualTo(100);
		assertThat(basis.averageCents()).hasValueSatisfying((average) -> assertThat(average).isEqualByComparingTo("10000"));
	}

	@Test
	void buysAverageInAndSalesLeaveTheAverageAlone() {
		CostBasis basis = new CostBasis();
		basis.started(100, 10_000);
		basis.bought(50, 10_300);
		// (100 x 100.00 + 50 x 103.00) / 150 = 101.00
		assertThat(basis.averageCents()).hasValueSatisfying((average) -> assertThat(average).isEqualByComparingTo("10100"));
		basis.sold(30);
		assertThat(basis.quantity()).isEqualTo(120);
		assertThat(basis.averageCents()).hasValueSatisfying((average) -> assertThat(average).isEqualByComparingTo("10100"));
		assertThat(basis.costCents()).isEqualByComparingTo("1212000");
	}

	@Test
	void partialFillsAddUpToTheSameAsOneFill() {
		CostBasis split = new CostBasis();
		split.bought(4, 10_001);
		split.bought(6, 10_001);
		CostBasis whole = new CostBasis();
		whole.bought(10, 10_001);
		assertThat(split.costCents()).isEqualByComparingTo(whole.costCents());
		assertThat(split.averageCents()).isEqualTo(whole.averageCents());
	}

	@Test
	void sellingOutResetsTheCostSoTheNextBuyStartsAfresh() {
		CostBasis basis = new CostBasis();
		basis.bought(3, 10_000);
		basis.bought(4, 9_001);
		basis.sold(7);
		assertThat(basis.quantity()).isZero();
		assertThat(basis.costCents()).isEqualByComparingTo("0");
		assertThat(basis.averageCents()).isEmpty();
		basis.bought(2, 5_000);
		assertThat(basis.averageCents()).hasValueSatisfying((average) -> assertThat(average).isEqualByComparingTo("5000"));
	}

	@Test
	void refusesToSellMoreThanItHoldsOrNothing() {
		CostBasis basis = new CostBasis();
		basis.bought(2, 100);
		assertThatThrownBy(() -> basis.sold(3)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> basis.bought(0, 100)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> basis.sold(-1)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void theAverageIsRoundedHalfToEvenAtAMillionthOfACent() {
		CostBasis basis = new CostBasis();
		basis.bought(2, 1);
		basis.bought(1, 3);
		// 5 cents over 3 shares: 1.6666666..., up to 1.666667, not cut to 1.666666.
		assertThat(basis.averageCents()).hasValueSatisfying((average) -> assertThat(average).isEqualByComparingTo("1.666667"));
	}

	@Test
	void aSaleRoundsItsShareOfTheCostHalfToEvenAtAMillionthOfACent() {
		// 2,000,001 cents for 2,000,000 shares; keeping one share keeps 1.0000005 cents, an exact half, which goes to the
		// even 1.000000 (half-up would give 1.000001).
		CostBasis even = new CostBasis();
		even.bought(1_999_999, 1);
		even.bought(1, 2);
		even.sold(1_999_999);
		assertThat(even.costCents()).isEqualByComparingTo("1.000000");
		// 2,000,003 cents: 1.0000015 goes up to the even 1.000002 (half-down would give 1.000001).
		CostBasis odd = new CostBasis();
		odd.bought(1_999_999, 1);
		odd.bought(1, 4);
		odd.sold(1_999_999);
		assertThat(odd.costCents()).isEqualByComparingTo("1.000002");
	}

	/** Exact cost as a fraction: buys add, a sale keeps (held - sold) / held of it. */
	private record Fraction(BigInteger numerator, BigInteger denominator) {

		static final Fraction ZERO = new Fraction(BigInteger.ZERO, BigInteger.ONE);

		Fraction plus(long whole) {
			return new Fraction(numerator.add(BigInteger.valueOf(whole).multiply(denominator)), denominator).reduced();
		}

		Fraction times(long top, long bottom) {
			return new Fraction(numerator.multiply(BigInteger.valueOf(top)), denominator.multiply(BigInteger.valueOf(bottom)))
				.reduced();
		}

		BigDecimal value() {
			return new BigDecimal(numerator).divide(new BigDecimal(denominator), 20, java.math.RoundingMode.HALF_EVEN);
		}

		Fraction reduced() {
			BigInteger gcd = numerator.gcd(denominator);
			return gcd.signum() == 0 ? this : new Fraction(numerator.divide(gcd), denominator.divide(gcd));
		}

	}

	@Test
	void thousandsOfSeededHistoriesStayWithinAMillionthOfACentPerSaleOfTheExactCost() {
		Random random = new Random(SEED);
		for (int run = 0; run < 400; run++) {
			CostBasis basis = new CostBasis();
			Fraction exact = Fraction.ZERO;
			long held = 0;
			int sales = 0;
			if (random.nextBoolean()) {
				long start = 1 + random.nextInt(1_000);
				long reference = 1 + random.nextInt(1_000_000);
				basis.started(start, reference);
				exact = exact.plus(start * reference);
				held = start;
			}
			for (int step = 0; step < 60; step++) {
				if (held > 0 && random.nextInt(3) == 0) {
					long shares = 1 + (long) random.nextInt((int) Math.min(held, 10_000));
					basis.sold(shares);
					exact = held == shares ? Fraction.ZERO : exact.times(held - shares, held);
					held -= shares;
					sales++;
				}
				else {
					long shares = 1 + random.nextInt(10_000);
					long price = random.nextInt(5) == 0 ? 1 + random.nextInt(100_000_000) : 9_000 + random.nextInt(2_000);
					basis.bought(shares, price);
					exact = exact.plus(shares * price);
					held += shares;
				}
				assertThat(basis.quantity()).as("run %d step %d", run, step).isEqualTo(held);
				// Each sale rounds by at most half a millionth of a cent; a later sale scales the earlier error down.
				BigDecimal tolerance = new BigDecimal("0.0000005").multiply(BigDecimal.valueOf(Math.max(sales, 1)));
				assertThat(basis.costCents().subtract(exact.value()).abs()).as("run %d step %d cost", run, step)
					.isLessThanOrEqualTo(tolerance);
			}
		}
	}

}
