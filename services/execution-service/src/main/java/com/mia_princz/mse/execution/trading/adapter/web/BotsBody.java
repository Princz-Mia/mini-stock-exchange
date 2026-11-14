package com.mia_princz.mse.execution.trading.adapter.web;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;

import com.mia_princz.mse.execution.trading.application.BotStrategy;
import com.mia_princz.mse.execution.trading.application.ProvisionAccount;
import com.mia_princz.mse.execution.trading.application.ProvisionBots;

/** The JSON body for creating bots: how many, the endowment each one gets, and optionally their strategy. */
record BotsBody(Integer count, String cash, List<Endowments.HoldingBody> holdings, String strategy) {

	/** {@code strategy} is null for untagged bots. */
	record Valid(int count, long cashCents, List<ProvisionAccount.Holding> holdings, String canonical,
			BotStrategy strategy) {

		/**
		 * Without a strategy, the hash is the one keys had before bots had tags, so the seed's stored answers replay as
		 * before. A strategy, when given, is appended last; its name can't be read as part of the endowment.
		 */
		byte[] hash() {
			String request = count + "|" + canonical;
			return CommandCalls.sha256(strategy == null ? request : request + "|" + strategy.name());
		}

	}

	Valid validate() {
		List<ApiException.FieldError> errors = new ArrayList<>();
		if (count == null || count < 1 || count > ProvisionBots.MAX_PER_COMMAND) {
			errors.add(new ApiException.FieldError("count", "OUT_OF_RANGE",
					"count must be between 1 and " + ProvisionBots.MAX_PER_COMMAND));
		}
		Endowments.Endowment endowment = Endowments.parse(cash, holdings, errors);
		BotStrategy tag = null;
		if (strategy != null) {
			try {
				tag = BotStrategy.valueOf(strategy);
			}
			catch (IllegalArgumentException ex) {
				errors.add(new ApiException.FieldError("strategy", "INVALID_STRATEGY",
						"strategy must be MARKET_MAKER, NOISE or MOMENTUM"));
			}
		}
		if (!errors.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The bot request is invalid", errors);
		}
		return new Valid(count, endowment.cashCents(), endowment.holdings(), endowment.canonical(), tag);
	}

}
