package com.mia_princz.mse.execution.trading.adapter.web;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.mia_princz.mse.execution.trading.domain.DisplayName;
import com.mia_princz.mse.execution.trading.domain.InvalidAmountException;
import com.mia_princz.mse.execution.trading.domain.InvalidDisplayNameException;
import com.mia_princz.mse.execution.trading.domain.Price;

/**
 * The JSON body of an instrument edit: the fields an operator may change during SETUP, all of them each time, as PUT
 * replaces them. A display name left out, or null, removes the name. Unknown JSON fields are rejected before this class
 * sees them.
 */
record InstrumentEditBody(String referencePrice, String displayName) {

	/** {@code displayName} is null for none. */
	record Valid(UUID instrumentId, Price referencePrice, DisplayName displayName) {

		/**
		 * The instrument is part of the request, so one key cannot edit two instruments. Without a display name, the hash
		 * is the one keys had before names existed; a name, when given, is appended last.
		 */
		byte[] hash() {
			String request = instrumentId + "|" + referencePrice.format();
			return CommandCalls.sha256(displayName == null ? request : request + "|" + displayName.value());
		}

	}

	Valid validate(UUID instrumentId) {
		List<ApiException.FieldError> errors = new ArrayList<>();
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
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The instrument edit is invalid",
					errors);
		}
		return new Valid(instrumentId, price, name);
	}

}
