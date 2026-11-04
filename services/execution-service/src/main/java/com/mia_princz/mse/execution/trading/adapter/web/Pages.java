package com.mia_princz.mse.execution.trading.adapter.web;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

/** Small rules shared by the trading API's controllers. */
final class Pages {

	static final int MAX_LIMIT = 200;

	private Pages() {
	}

	static void checkLimit(int limit) {
		if (limit < 1 || limit > MAX_LIMIT) {
			throw ApiException.invalid("limit", "OUT_OF_RANGE", "limit must be between 1 and " + MAX_LIMIT);
		}
	}

	/** The account always comes from the session, never from the request. */
	static TradingQueries.Trader trader(TradingQueries queries, Authentication authentication) {
		return queries.trader(authentication.getName())
			.orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "NO_TRADING_ACCOUNT",
					"This user has no trading account"));
	}

}
