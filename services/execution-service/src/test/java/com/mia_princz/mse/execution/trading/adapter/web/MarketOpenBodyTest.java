package com.mia_princz.mse.execution.trading.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The optional body of opening the market: its limits, which the operator screen checks the same way. */
class MarketOpenBodyTest {

	@Test
	void theLimitsThemselvesAreTaken() {
		assertThat(new MarketOpenBody(5).validate()).isEqualTo(Duration.ofMinutes(5));
		assertThat(new MarketOpenBody(90).validate()).isEqualTo(Duration.ofMinutes(90));
		assertThat(new MarketOpenBody(43_200).validate()).as("30 days").isEqualTo(Duration.ofDays(30));
		assertThat(new MarketOpenBody(null).validate()).as("no duration").isNull();
	}

	@Test
	void aDurationJustOutsideTheLimitsIsAFieldError() {
		for (int minutes : new int[] { 4, 43_201, 0, -5 }) {
			assertThatThrownBy(() -> new MarketOpenBody(minutes).validate()).as("%d minutes", minutes)
				.isInstanceOfSatisfying(ApiException.class, (refused) -> {
					assertThat(refused.status().value()).isEqualTo(400);
					assertThat(errors(refused)).containsExactly("durationMinutes OUT_OF_RANGE");
				});
		}
	}

	@SuppressWarnings("unchecked")
	private static List<String> errors(ApiException refused) {
		Object errors = refused.toProblem().getProperties().get("errors");
		return ((List<ApiException.FieldError>) errors).stream().map((error) -> error.field() + " " + error.code()).toList();
	}

}
