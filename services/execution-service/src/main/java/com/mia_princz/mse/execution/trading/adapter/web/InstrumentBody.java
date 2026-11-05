package com.mia_princz.mse.execution.trading.adapter.web;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

import com.mia_princz.mse.execution.trading.domain.InvalidAmountException;
import com.mia_princz.mse.execution.trading.domain.Price;

/** The JSON body of a new instrument. Unknown JSON fields are rejected before this class sees them. */
record InstrumentBody(String symbol, String referencePrice) {

	/** Upper-case letters only, as the symbol is an immutable display and routing alias. */
	private static final Pattern SYMBOL = Pattern.compile("^[A-Z]{1,8}$");

	record Valid(String symbol, Price referencePrice) {

		byte[] hash() {
			return CommandCalls.sha256(symbol + "|" + referencePrice.format());
		}

	}

	Valid validate() {
		List<ApiException.FieldError> errors = new ArrayList<>();
		if (symbol == null) {
			errors.add(new ApiException.FieldError("symbol", "REQUIRED", "symbol is required"));
		}
		else if (!SYMBOL.matcher(symbol).matches()) {
			errors.add(new ApiException.FieldError("symbol", "INVALID_SYMBOL", "symbol must be 1 to 8 letters A-Z"));
		}
		Price price = null;
		if (referencePrice == null) {
			errors.add(new ApiException.FieldError("referencePrice", "REQUIRED", "referencePrice is required"));
		}
		else {
			try {
				price = Price.parse(referencePrice);
			}
			catch (InvalidAmountException ex) {
				errors.add(new ApiException.FieldError("referencePrice", "INVALID_PRICE", ex.getMessage()));
			}
		}
		if (!errors.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The instrument request is invalid",
					errors);
		}
		return new Valid(symbol, price);
	}

}
