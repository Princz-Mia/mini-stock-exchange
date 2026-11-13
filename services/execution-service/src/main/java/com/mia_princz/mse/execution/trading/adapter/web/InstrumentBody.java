package com.mia_princz.mse.execution.trading.adapter.web;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

import com.mia_princz.mse.execution.trading.domain.DisplayName;
import com.mia_princz.mse.execution.trading.domain.InvalidAmountException;
import com.mia_princz.mse.execution.trading.domain.InvalidDisplayNameException;
import com.mia_princz.mse.execution.trading.domain.Price;

/**
 * The JSON body of a new instrument. The display name is optional. Unknown JSON fields are rejected before this class
 * sees them.
 */
record InstrumentBody(String symbol, String referencePrice, String displayName) {

	/** Upper-case letters only, as the symbol is an immutable display and routing alias. */
	private static final Pattern SYMBOL = Pattern.compile("^[A-Z]{1,8}$");

	/** {@code displayName} is null for none. */
	record Valid(String symbol, Price referencePrice, DisplayName displayName) {

		/**
		 * Without a display name, the hash is the one keys had before names existed, so a stored add answers its retry as
		 * before. A name, when given, is appended last, after the fixed-form symbol and price.
		 */
		byte[] hash() {
			String request = symbol + "|" + referencePrice.format();
			return CommandCalls.sha256(displayName == null ? request : request + "|" + displayName.value());
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
		DisplayName name = null;
		if (displayName != null) {
			try {
				name = new DisplayName(displayName);
			}
			catch (InvalidDisplayNameException ex) {
				errors.add(new ApiException.FieldError("displayName", "INVALID_DISPLAY_NAME", ex.getMessage()));
			}
		}
		if (!errors.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The instrument request is invalid",
					errors);
		}
		return new Valid(symbol, price, name);
	}

}
