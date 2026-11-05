package com.mia_princz.mse.execution.trading.adapter.web;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;

import com.mia_princz.mse.execution.trading.application.ProvisionAccount;
import com.mia_princz.mse.execution.trading.application.ProvisionBots;

/** The JSON body for creating bots: how many, and the endowment each one gets. */
record BotsBody(Integer count, String cash, List<Endowments.HoldingBody> holdings) {

	record Valid(int count, long cashCents, List<ProvisionAccount.Holding> holdings, String canonical) {

		byte[] hash() {
			return CommandCalls.sha256(count + "|" + canonical);
		}

	}

	Valid validate() {
		List<ApiException.FieldError> errors = new ArrayList<>();
		if (count == null || count < 1 || count > ProvisionBots.MAX_PER_COMMAND) {
			errors.add(new ApiException.FieldError("count", "OUT_OF_RANGE",
					"count must be between 1 and " + ProvisionBots.MAX_PER_COMMAND));
		}
		Endowments.Endowment endowment = Endowments.parse(cash, holdings, errors);
		if (!errors.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The bot request is invalid", errors);
		}
		return new Valid(count, endowment.cashCents(), endowment.holdings(), endowment.canonical());
	}

}
