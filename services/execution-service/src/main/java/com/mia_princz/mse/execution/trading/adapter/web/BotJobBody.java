package com.mia_princz.mse.execution.trading.adapter.web;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;

import com.mia_princz.mse.execution.trading.application.BotJob;
import com.mia_princz.mse.execution.trading.application.ProvisionAccount;

/**
 * The JSON body for a bot job: how many bots in all, how many of each strategy, and the endowment each one gets. The
 * strategies must add up to the count: a screen that computes them from a mix sends exactly what it showed, and a
 * request whose numbers disagree is refused rather than guessed at.
 */
record BotJobBody(Long count, Strategies strategies, String cash, List<Endowments.HoldingBody> holdings) {

	/** The most bots one job asks for: what an int, and so the population, can hold. */
	static final long MAX_COUNT = Integer.MAX_VALUE;

	record Strategies(Long marketMakers, Long noise, Long momentum) {
	}

	record Valid(BotJob.Counts requested, long cashCents, List<ProvisionAccount.Holding> holdings, String canonical) {

		byte[] hash() {
			return CommandCalls.sha256(requested.marketMakers() + "|" + requested.noise() + "|" + requested.momentum()
					+ "|" + canonical);
		}

	}

	Valid validate() {
		List<ApiException.FieldError> errors = new ArrayList<>();
		if (count == null || count < 1 || count > MAX_COUNT) {
			errors.add(new ApiException.FieldError("count", "OUT_OF_RANGE", "count must be between 1 and " + MAX_COUNT));
		}
		long[] counts = new long[3];
		if (strategies == null) {
			errors.add(new ApiException.FieldError("strategies", "REQUIRED",
					"strategies is required: how many market makers, noise and momentum bots"));
		}
		else {
			counts[0] = strategy("marketMakers", strategies.marketMakers(), errors);
			counts[1] = strategy("noise", strategies.noise(), errors);
			counts[2] = strategy("momentum", strategies.momentum(), errors);
		}
		long sum = counts[0] + counts[1] + counts[2];
		if (errors.isEmpty() && sum != count) {
			errors.add(new ApiException.FieldError("strategies", "MIX_MISMATCH",
					"the strategies add up to " + sum + ", not the count of " + count));
		}
		Endowments.Endowment endowment = Endowments.parse(cash, holdings, errors);
		if (!errors.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The bot job request is invalid", errors);
		}
		return new Valid(new BotJob.Counts((int) counts[0], (int) counts[1], (int) counts[2]), endowment.cashCents(),
				endowment.holdings(), endowment.canonical());
	}

	private static long strategy(String name, Long value, List<ApiException.FieldError> errors) {
		String field = "strategies." + name;
		if (value == null) {
			errors.add(new ApiException.FieldError(field, "REQUIRED", name + " is required; 0 for none"));
			return 0;
		}
		if (value < 0 || value > MAX_COUNT) {
			errors.add(new ApiException.FieldError(field, "OUT_OF_RANGE", name + " must be between 0 and " + MAX_COUNT));
			return 0;
		}
		return value;
	}

}
