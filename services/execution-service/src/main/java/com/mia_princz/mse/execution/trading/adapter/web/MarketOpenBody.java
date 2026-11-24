package com.mia_princz.mse.execution.trading.adapter.web;

import java.time.Duration;
import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * The optional JSON body of opening the market. Unknown JSON fields are rejected before this class sees them.
 * {@code durationMinutes} left out means no duration: the market stays open until an operator closes it.
 */
record MarketOpenBody(Integer durationMinutes) {

	/** Shorter than this, a market closes before its screens have shown the time it closes at. */
	static final int MIN_MINUTES = 5;

	/** Thirty days: a simulation is a session, and one left running for weeks is what a duration is there to end. */
	static final int MAX_MINUTES = 30 * 24 * 60;

	/** The duration, or null for none. */
	Duration validate() {
		if (durationMinutes == null) {
			return null;
		}
		if (durationMinutes < MIN_MINUTES || durationMinutes > MAX_MINUTES) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The opening request is invalid",
					List.of(new ApiException.FieldError("durationMinutes", "OUT_OF_RANGE",
							"durationMinutes must be between " + MIN_MINUTES + " and " + MAX_MINUTES)));
		}
		return Duration.ofMinutes(durationMinutes);
	}

}
