package com.mia_princz.mse.execution.trading.adapter.web;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;

/**
 * Opaque, versioned page cursors. Clients pass back what they were given; the {@code v1} prefix lets the format
 * change later without breaking cursors already handed out. Anything else is one answer: not a cursor we issued.
 */
final class Cursors {

	private Cursors() {
	}

	static String orders(TradingQueries.OrderPosition position) {
		return encode("v1:" + position.createdAt() + "|" + position.id());
	}

	static TradingQueries.OrderPosition orders(String cursor) {
		String[] parts = decode(cursor);
		try {
			return new TradingQueries.OrderPosition(Instant.parse(parts[0]), UUID.fromString(parts[1]));
		}
		catch (DateTimeParseException | IllegalArgumentException ex) {
			throw invalid();
		}
	}

	static String accounts(TradingQueries.AccountPosition position) {
		return encode("v1:" + position.createdAt() + "|" + position.id());
	}

	static TradingQueries.AccountPosition accounts(String cursor) {
		TradingQueries.OrderPosition position = orders(cursor);
		return new TradingQueries.AccountPosition(position.createdAt(), position.id());
	}

	static String trades(TradingQueries.TradePosition position) {
		return encode("v1:" + position.commandSequence() + "|" + position.tradeSequence());
	}

	static TradingQueries.TradePosition trades(String cursor) {
		String[] parts = decode(cursor);
		try {
			long command = Long.parseLong(parts[0]);
			long trade = Long.parseLong(parts[1]);
			if (command > 0 && trade > 0) {
				return new TradingQueries.TradePosition(command, trade);
			}
		}
		catch (NumberFormatException ex) {
			// Answered below, like any other cursor we did not issue.
		}
		throw invalid();
	}

	private static String encode(String value) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	private static String[] decode(String cursor) {
		String text;
		try {
			text = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
		}
		catch (IllegalArgumentException ex) {
			throw invalid();
		}
		String[] parts = text.startsWith("v1:") ? text.substring(3).split("\\|", -1) : new String[0];
		if (parts.length != 2) {
			throw invalid();
		}
		return parts;
	}

	private static ApiException invalid() {
		return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The request is invalid",
				List.of(new ApiException.FieldError("cursor", "INVALID_CURSOR", "cursor is not one this API issued")));
	}

}
