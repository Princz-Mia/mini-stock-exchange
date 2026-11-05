package com.mia_princz.mse.execution.trading.adapter.web;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;

import com.mia_princz.mse.execution.trading.application.ProvisionAccount;

/** The JSON body of a funded account. Unknown JSON fields are rejected before this class sees them. */
record AccountBody(String username, String cash, List<Endowments.HoldingBody> holdings) {

	record Valid(String username, long cashCents, List<ProvisionAccount.Holding> holdings, String canonical) {

		byte[] hash() {
			return CommandCalls.sha256(username + "|" + canonical);
		}

	}

	Valid validate() {
		List<ApiException.FieldError> errors = new ArrayList<>();
		if (username == null || username.isBlank()) {
			errors.add(new ApiException.FieldError("username", "REQUIRED", "username is required"));
		}
		Endowments.Endowment endowment = Endowments.parse(cash, holdings, errors);
		if (!errors.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The account request is invalid",
					errors);
		}
		return new Valid(username, endowment.cashCents(), endowment.holdings(), endowment.canonical());
	}

}
