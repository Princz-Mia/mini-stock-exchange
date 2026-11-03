package com.mia_princz.mse.execution.trading.adapter.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

import com.mia_princz.mse.execution.trading.domain.InvalidAmountException;
import com.mia_princz.mse.execution.trading.domain.OrderType;
import com.mia_princz.mse.execution.trading.domain.Price;
import com.mia_princz.mse.execution.trading.domain.Quantity;
import com.mia_princz.mse.execution.trading.domain.Side;

/**
 * The JSON body of a new order, as received. {@link #validate()} turns it into exact domain values or one 400 that
 * lists every problem. Unknown JSON fields are rejected before this class sees them.
 */
record OrderBody(UUID instrumentId, String side, String type, String timeInForce, Integer quantity, String limitPrice,
		String protectionPrice) {

	private static final Pattern TOO_PRECISE = Pattern.compile("^-?[0-9]+\\.[0-9]{3,}$");

	record Valid(UUID instrumentId, Side side, OrderType type, Price bound, Quantity quantity) {

		/** Fixed field order and canonical number forms, so equal requests hash equally however they were written. */
		byte[] hash() {
			String canonical = String.join("|", instrumentId.toString(), side.name(), type.name(), bound.format(),
					Long.toString(quantity.shares()));
			try {
				return MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
			}
			catch (NoSuchAlgorithmException ex) {
				throw new IllegalStateException(ex);
			}
		}

	}

	Valid validate() {
		List<ApiException.FieldError> errors = new ArrayList<>();
		if (instrumentId == null) {
			errors.add(new ApiException.FieldError("instrumentId", "REQUIRED", "instrumentId is required"));
		}
		Side parsedSide = parse(Side.class, "side", side, errors);
		OrderType parsedType = parse(OrderType.class, "type", type, errors);
		Quantity parsedQuantity = null;
		if (quantity == null) {
			errors.add(new ApiException.FieldError("quantity", "REQUIRED", "quantity is required"));
		}
		else {
			try {
				parsedQuantity = new Quantity(quantity);
			}
			catch (InvalidAmountException ex) {
				errors.add(new ApiException.FieldError("quantity", "INVALID_QUANTITY", ex.getMessage()));
			}
		}
		Price bound = null;
		if (parsedType == OrderType.LIMIT) {
			expect(timeInForce, "GTC", errors);
			absent("protectionPrice", protectionPrice, errors);
			bound = price("limitPrice", limitPrice, errors);
		}
		else if (parsedType == OrderType.MARKET_PROTECTED) {
			expect(timeInForce, "IOC", errors);
			absent("limitPrice", limitPrice, errors);
			bound = price("protectionPrice", protectionPrice, errors);
		}
		if (!errors.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The order request is invalid", errors);
		}
		return new Valid(instrumentId, parsedSide, parsedType, bound, parsedQuantity);
	}

	private static <E extends Enum<E>> E parse(Class<E> type, String field, String value,
			List<ApiException.FieldError> errors) {
		if (value == null) {
			errors.add(new ApiException.FieldError(field, "REQUIRED", field + " is required"));
			return null;
		}
		try {
			return Enum.valueOf(type, value);
		}
		catch (IllegalArgumentException ex) {
			errors.add(new ApiException.FieldError(field, "INVALID_VALUE", field + " has an unknown value"));
			return null;
		}
	}

	private static void expect(String timeInForce, String required, List<ApiException.FieldError> errors) {
		if (!required.equals(timeInForce)) {
			errors.add(new ApiException.FieldError("timeInForce", "INVALID_COMBINATION",
					"This order type requires timeInForce " + required));
		}
	}

	private static void absent(String field, String value, List<ApiException.FieldError> errors) {
		if (value != null) {
			errors.add(new ApiException.FieldError(field, "INVALID_COMBINATION", field + " is not allowed for this order type"));
		}
	}

	private static Price price(String field, String value, List<ApiException.FieldError> errors) {
		if (value == null) {
			errors.add(new ApiException.FieldError(field, "REQUIRED", field + " is required"));
			return null;
		}
		try {
			return Price.parse(value);
		}
		catch (InvalidAmountException ex) {
			String code = TOO_PRECISE.matcher(value).matches() ? "INVALID_TICK" : "INVALID_PRICE";
			errors.add(new ApiException.FieldError(field, code, ex.getMessage()));
			return null;
		}
	}

}
