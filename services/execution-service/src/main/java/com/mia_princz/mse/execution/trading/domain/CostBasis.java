package com.mia_princz.mse.execution.trading.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * What one holding cost, by the average-cost rule: starting shares at their reference price, each buy at its price,
 * and each sale taking shares out at the average, which it leaves unchanged. Selling out resets it. An estimate, not a
 * tax-style cost basis: a sale's share of the cost is rounded half-even to a millionth of a cent.
 *
 * <p>Derived when read, from the account's starting shares and its trades in sequence order, so settlement never
 * touches it. Not thread-safe: one is built per holding per read.
 */
public final class CostBasis {

	/** Decimal places of a cent kept in the cost, so rounding on sales stays far below the cents shown. */
	static final int SCALE = 6;

	private long quantity;

	private BigDecimal costCents = BigDecimal.ZERO;

	/** Starting shares, counted at the reference price: there was no trade to buy them at. */
	public void started(long shares, long referencePriceCents) {
		bought(shares, referencePriceCents);
	}

	public void bought(long shares, long priceCents) {
		positive(shares);
		quantity = Math.addExact(quantity, shares);
		costCents = costCents.add(BigDecimal.valueOf(priceCents).multiply(BigDecimal.valueOf(shares)));
	}

	public void sold(long shares) {
		positive(shares);
		if (shares > quantity) {
			throw new IllegalStateException("Sold " + shares + " shares of a holding of " + quantity);
		}
		long left = quantity - shares;
		costCents = left == 0 ? BigDecimal.ZERO
				: costCents.multiply(BigDecimal.valueOf(left)).divide(BigDecimal.valueOf(quantity), SCALE,
						RoundingMode.HALF_EVEN);
		quantity = left;
	}

	public long quantity() {
		return quantity;
	}

	/** The cost of the shares held, in cents, to a millionth of a cent. */
	public BigDecimal costCents() {
		return costCents;
	}

	/** The cost of one share, in cents, or empty with none held. */
	public Optional<BigDecimal> averageCents() {
		return quantity == 0 ? Optional.empty()
				: Optional.of(costCents.divide(BigDecimal.valueOf(quantity), SCALE, RoundingMode.HALF_EVEN));
	}

	private static void positive(long shares) {
		if (shares <= 0) {
			throw new IllegalArgumentException("Shares must be positive: " + shares);
		}
	}

}
