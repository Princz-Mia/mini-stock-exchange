package com.mia_princz.mse.marketdata.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

/** How session figures are written: signs, the percentage and VWAP rounded half-even, and nothing before a trade. */
class SessionViewTest {

	private static final MarketController.Feed LIVE = new MarketController.Feed("LIVE", 0);

	private static MarketQueries.Session traded(long lastCents, long volume, long notionalCents) {
		return new MarketQueries.Session(1, volume, BigDecimal.valueOf(notionalCents), lastCents, lastCents, lastCents,
				List.of(new MarketQueries.TrendPoint(Instant.parse("2026-11-12T10:15:00Z"), lastCents)));
	}

	@Test
	void writesNothingButTheReferenceBeforeTheFirstTrade() {
		MarketController.SessionView view = MarketController.session(10_000, MarketQueries.Session.NONE, LIVE);
		assertThat(view).isEqualTo(new MarketController.SessionView("100.00", null, null, null, null, null, 0, 0, null,
				List.of(), LIVE));
	}

	@Test
	void writesTheChangeWithItsSignAndTheTrend() {
		MarketController.SessionView up = MarketController.session(10_000, traded(10_103, 1, 10_103), LIVE);
		assertThat(up.change()).isEqualTo("1.03");
		assertThat(up.changePercent()).isEqualTo("1.03");
		assertThat(up.trend()).containsExactly(
				new MarketController.TrendPointView(Instant.parse("2026-11-12T10:15:00Z"), "101.03"));

		MarketController.SessionView down = MarketController.session(10_000, traded(9_950, 1, 9_950), LIVE);
		assertThat(down.change()).isEqualTo("-0.50");
		assertThat(down.changePercent()).isEqualTo("-0.50");

		MarketController.SessionView flat = MarketController.session(10_000, traded(10_000, 1, 10_000), LIVE);
		assertThat(flat.change()).isEqualTo("0.00");
		assertThat(flat.changePercent()).isEqualTo("0.00");
	}

	@Test
	void roundsThePercentageHalfEvenToTwoPlaces() {
		// Against 200.00, a cent is exactly 0.005%: ties go to the even neighbour, both ways.
		assertThat(MarketController.session(20_000, traded(20_001, 1, 20_001), LIVE).changePercent()).isEqualTo("0.00");
		assertThat(MarketController.session(20_000, traded(20_003, 1, 20_003), LIVE).changePercent()).isEqualTo("0.02");
		assertThat(MarketController.session(20_000, traded(19_999, 1, 19_999), LIVE).changePercent()).isEqualTo("0.00");
		assertThat(MarketController.session(20_000, traded(19_997, 1, 19_997), LIVE).changePercent()).isEqualTo("-0.02");
		// Not a tie: a third of a cent rounds to the nearest.
		assertThat(MarketController.session(30_000, traded(30_001, 1, 30_001), LIVE).changePercent()).isEqualTo("0.00");
		assertThat(MarketController.session(30_000, traded(30_002, 1, 30_002), LIVE).changePercent()).isEqualTo("0.01");
	}

	@Test
	void roundsTheVwapHalfEvenToTheCent() {
		// 25 cents over 2 shares is 12.5 cents: to 12; 35 over 2 is 17.5: to 18.
		assertThat(MarketController.session(100, traded(12, 2, 25), LIVE).vwap()).isEqualTo("0.12");
		assertThat(MarketController.session(100, traded(18, 2, 35), LIVE).vwap()).isEqualTo("0.18");
		assertThat(MarketController.session(100, traded(13, 3, 40), LIVE).vwap()).isEqualTo("0.13");
	}

}
