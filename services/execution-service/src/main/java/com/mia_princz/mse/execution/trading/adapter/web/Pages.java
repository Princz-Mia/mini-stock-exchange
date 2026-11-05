package com.mia_princz.mse.execution.trading.adapter.web;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

import com.mia_princz.mse.execution.identity.BotPrincipal;

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

	/**
	 * The account always comes from the credential, never from the request. A bot has no user, so its account is also
	 * its principal for idempotency keys.
	 */
	static TradingQueries.Trader trader(TradingQueries queries, Authentication authentication) {
		if (authentication.getPrincipal() instanceof BotPrincipal bot) {
			return new TradingQueries.Trader(bot.accountId(), bot.accountId());
		}
		return queries.trader(authentication.getName())
			.orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "NO_TRADING_ACCOUNT",
					"This user has no trading account"));
	}

}
