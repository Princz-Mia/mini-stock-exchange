package com.mia_princz.mse.execution.trading.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/** How the portfolio's figures are rounded and written: half-even, two places, a minus when negative. */
class PortfolioFiguresTest {

	@Test
	void centsRoundHalfToEven() {
		assertThat(PortfolioController.cents(new BigDecimal("12.5"))).isEqualTo("0.12");
		assertThat(PortfolioController.cents(new BigDecimal("13.5"))).isEqualTo("0.14");
		assertThat(PortfolioController.cents(new BigDecimal("-12.5"))).isEqualTo("-0.12");
		assertThat(PortfolioController.cents(new BigDecimal("-0.4"))).isEqualTo("0.00");
		assertThat(PortfolioController.cents(new BigDecimal("1234567.000001"))).isEqualTo("12345.67");
	}

	@Test
	void percentagesRoundHalfToEvenAndAreZeroOfNothing() {
		// 1 of 800 is 0.125%, 3 of 800 is 0.375%: exact halves both ways.
		assertThat(PortfolioController.percent(BigDecimal.ONE, new BigDecimal("800"))).isEqualTo("0.12");
		assertThat(PortfolioController.percent(new BigDecimal("3"), new BigDecimal("800"))).isEqualTo("0.38");
		assertThat(PortfolioController.percent(BigDecimal.ONE.negate(), new BigDecimal("800"))).isEqualTo("-0.12");
		assertThat(PortfolioController.percent(new BigDecimal("-1"), new BigDecimal("1000000"))).isEqualTo("0.00");
		assertThat(PortfolioController.percent(new BigDecimal("300"), new BigDecimal("15150"))).isEqualTo("1.98");
		assertThat(PortfolioController.percent(BigDecimal.ZERO, BigDecimal.ZERO)).isEqualTo("0.00");
		assertThat(PortfolioController.percent(BigDecimal.TEN, BigDecimal.ZERO)).isEqualTo("0.00");
	}

}
