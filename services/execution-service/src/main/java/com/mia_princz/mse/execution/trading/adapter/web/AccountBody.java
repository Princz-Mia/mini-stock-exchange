package com.mia_princz.mse.execution.trading.adapter.web;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;

import com.mia_princz.mse.execution.trading.application.ProvisionAccount;
import com.mia_princz.mse.execution.trading.domain.InvalidAmountException;
import com.mia_princz.mse.execution.trading.domain.Money;

/** The JSON body of a funded account. Unknown JSON fields are rejected before this class sees them. */
record AccountBody(String username, String cash, List<HoldingBody> holdings) {

	/** One billion dollars: far above any sensible endowment, and far below the ledger's limits. */
	static final long MAX_CASH_CENTS = 100_000_000_000L;

	static final long MAX_SHARES = 1_000_000;

	static final int MAX_HOLDINGS = 50;

	record HoldingBody(UUID instrumentId, Long quantity) {
	}

	record Valid(String username, long cashCents, List<ProvisionAccount.Holding> holdings) {

		/** Holdings in instrument order, so the same endowment hashes the same however it was listed. */
		byte[] hash() {
			String canonicalHoldings = holdings.stream()
				.sorted(Comparator.comparing(ProvisionAccount.Holding::instrumentId))
				.map((holding) -> holding.instrumentId() + ":" + holding.quantity())
				.collect(Collectors.joining(","));
			return CommandCalls.sha256(username + "|" + cashCents + "|" + canonicalHoldings);
		}

	}

	Valid validate() {
		List<ApiException.FieldError> errors = new ArrayList<>();
		if (username == null || username.isBlank()) {
			errors.add(new ApiException.FieldError("username", "REQUIRED", "username is required"));
		}
		long cents = 0;
		if (cash == null) {
			errors.add(new ApiException.FieldError("cash", "REQUIRED", "cash is required"));
		}
		else {
			try {
				cents = Money.parse(cash).cents();
				if (cents < 0 || cents > MAX_CASH_CENTS) {
					errors.add(new ApiException.FieldError("cash", "OUT_OF_RANGE",
							"cash must be between 0.00 and 1000000000.00"));
				}
			}
			catch (InvalidAmountException ex) {
				errors.add(new ApiException.FieldError("cash", "INVALID_AMOUNT", ex.getMessage()));
			}
		}
		List<ProvisionAccount.Holding> parsed = new ArrayList<>();
		List<HoldingBody> listed = holdings == null ? List.of() : holdings;
		if (listed.size() > MAX_HOLDINGS) {
			errors.add(new ApiException.FieldError("holdings", "TOO_MANY", "at most " + MAX_HOLDINGS + " holdings"));
		}
		Set<UUID> seen = new HashSet<>();
		for (int i = 0; i < listed.size(); i++) {
			HoldingBody holding = listed.get(i);
			String field = "holdings[" + i + "]";
			if (holding == null || holding.instrumentId() == null) {
				errors.add(new ApiException.FieldError(field + ".instrumentId", "REQUIRED", "instrumentId is required"));
				continue;
			}
			if (!seen.add(holding.instrumentId())) {
				errors.add(new ApiException.FieldError(field + ".instrumentId", "DUPLICATE",
						"each instrument may be listed once"));
			}
			if (holding.quantity() == null || holding.quantity() < 1 || holding.quantity() > MAX_SHARES) {
				errors.add(new ApiException.FieldError(field + ".quantity", "OUT_OF_RANGE",
						"quantity must be between 1 and " + MAX_SHARES));
				continue;
			}
			parsed.add(new ProvisionAccount.Holding(holding.instrumentId(), holding.quantity()));
		}
		if (errors.isEmpty() && cents == 0 && parsed.isEmpty()) {
			errors.add(new ApiException.FieldError("cash", "EMPTY_ENDOWMENT", "an account needs cash or shares"));
		}
		if (!errors.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The account request is invalid",
					errors);
		}
		return new Valid(username, cents, parsed);
	}

}
