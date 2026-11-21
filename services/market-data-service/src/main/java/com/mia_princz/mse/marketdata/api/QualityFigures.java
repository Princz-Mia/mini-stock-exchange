package com.mia_princz.mse.marketdata.api;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * A window's market quality, from the minutes whose snapshots fall in it.
 * <ul>
 * <li>The minutes run from the first such minute to the last; a minute without a row holds the minute before it, as
 * nothing changed. A minute's book is its last snapshot's.</li>
 * <li>The average spread is the mean of the minutes' spreads, best ask minus best bid, over the minutes with both
 * sides; null when none has.</li>
 * <li>Volatility is the sample standard deviation of the one-minute returns of the last price,
 * {@code price / previous price - 1}, between consecutive minutes that both have a price, a minute that held its price
 * counting as a return of 0; not annualised. Null with fewer than two returns.</li>
 * </ul>
 */
final class QualityFigures {

	private QualityFigures() {
	}

	/** The working precision of the volatility, before it is rounded for the answer. */
	private static final MathContext PRECISION = MathContext.DECIMAL128;

	/** {@code changed} is false for a minute carried forward from the one before. */
	record Minute(Instant start, Long bidCents, Long askCents, Long lastPriceCents, boolean changed) {

		Long spreadCents() {
			return bidCents == null || askCents == null ? null : askCents - bidCents;
		}

	}

	/** {@code volatility} is null with fewer than two returns. */
	record Figures(List<Minute> minutes, long spreadSumCents, int twoSidedMinutes, BigDecimal volatility,
			int returns) {

		/** The average spread as a price with four places, half-even, from the exact mean; null without both sides. */
		String averageSpread() {
			return twoSidedMinutes == 0 ? null
					: BigDecimal.valueOf(spreadSumCents)
						.divide(BigDecimal.valueOf(100L * twoSidedMinutes), 4, RoundingMode.HALF_EVEN)
						.toPlainString();
		}

		/** The volatility as a fraction with six places, half-even; null with fewer than two returns. */
		String volatilityRatio() {
			return volatility == null ? null : volatility.setScale(6, RoundingMode.HALF_EVEN).toPlainString();
		}

	}

	/** The figures of {@code rows}, oldest minute first, one row a minute. */
	static Figures of(List<MarketQueries.SpreadMinute> rows) {
		List<Minute> minutes = new ArrayList<>();
		for (MarketQueries.SpreadMinute row : rows) {
			if (!minutes.isEmpty()) {
				Minute held = minutes.getLast();
				for (Instant gap = held.start().plus(1, ChronoUnit.MINUTES); gap.isBefore(row.minute());
						gap = gap.plus(1, ChronoUnit.MINUTES)) {
					minutes.add(new Minute(gap, held.bidCents(), held.askCents(), held.lastPriceCents(), false));
				}
			}
			minutes.add(new Minute(row.minute(), row.bidCents(), row.askCents(), row.lastPriceCents(), true));
		}
		long spreadSum = 0;
		int twoSided = 0;
		List<BigDecimal> returns = new ArrayList<>();
		Long previousPrice = null;
		for (Minute minute : minutes) {
			Long spread = minute.spreadCents();
			if (spread != null) {
				spreadSum += spread;
				twoSided++;
			}
			Long price = minute.lastPriceCents();
			if (price != null && previousPrice != null) {
				returns.add(BigDecimal.valueOf(price).divide(BigDecimal.valueOf(previousPrice), PRECISION).subtract(BigDecimal.ONE));
			}
			previousPrice = price;
		}
		return new Figures(List.copyOf(minutes), spreadSum, twoSided, deviation(returns), returns.size());
	}

	/** The sample standard deviation, or null for fewer than two values. */
	private static BigDecimal deviation(List<BigDecimal> values) {
		if (values.size() < 2) {
			return null;
		}
		BigDecimal count = BigDecimal.valueOf(values.size());
		BigDecimal mean = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(count, PRECISION);
		BigDecimal squares = values.stream()
			.map((value) -> value.subtract(mean).pow(2, PRECISION))
			.reduce(BigDecimal.ZERO, BigDecimal::add);
		return squares.divide(count.subtract(BigDecimal.ONE), PRECISION).sqrt(PRECISION);
	}

}
