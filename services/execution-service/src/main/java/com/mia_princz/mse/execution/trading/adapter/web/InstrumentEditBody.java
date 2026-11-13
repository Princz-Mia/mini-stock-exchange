package com.mia_princz.mse.execution.trading.adapter.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.mia_princz.mse.execution.trading.domain.InvalidAmountException;
import com.mia_princz.mse.execution.trading.domain.Price;

/**
 * The JSON body of an instrument edit: the fields an operator may change during SETUP, all of them each time. Unknown
 * JSON fields are rejected before this class sees them.
 */
record InstrumentEditBody(String referencePrice) {

	record Valid(UUID instrumentId, Price referencePrice) {

		/** The instrument is part of the request, so one key cannot edit two instruments. */
		byte[] hash() {
			return CommandCalls.sha256(instrumentId + "|" + referencePrice.format());
		}

	}

	Valid validate(UUID instrumentId) {
		ApiException.FieldError error;
		if (referencePrice == null) {
			error = new ApiException.FieldError("referencePrice", "REQUIRED", "referencePrice is required");
		}
		else {
			try {
				return new Valid(instrumentId, Price.parse(referencePrice));
			}
			catch (InvalidAmountException ex) {
				error = new ApiException.FieldError("referencePrice", "INVALID_PRICE", ex.getMessage());
			}
		}
		throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The instrument edit is invalid",
				List.of(error));
	}

}
