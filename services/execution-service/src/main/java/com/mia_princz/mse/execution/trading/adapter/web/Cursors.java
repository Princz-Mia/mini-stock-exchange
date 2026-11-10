package com.mia_princz.mse.execution.trading.adapter.web;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;

/**
 * Opaque, versioned page cursors. Clients pass back what they were given. A {@code v2} cursor names the order it was
 * issued in, and is refused under the other one, since its position means nothing there. A {@code v1} cursor, from
 * before lists could be read either way, stands for the list's default order. Anything else is one answer: not a
 * cursor we issued.
 */
final class Cursors {

	private Cursors() {
	}

	static String orders(TradingQueries.OrderPosition position, PageOrder order) {
		return encode(order, position.createdAt() + "|" + position.id());
	}

	static TradingQueries.OrderPosition orders(String cursor, PageOrder order) {
		String[] parts = decode(cursor, order, PageOrder.NEWEST);
		return new TradingQueries.OrderPosition(instant(parts[0]), uuid(parts[1]));
	}

	static String accounts(TradingQueries.AccountPosition position, PageOrder order) {
		return encode(order, position.createdAt() + "|" + position.id());
	}

	static TradingQueries.AccountPosition accounts(String cursor, PageOrder order) {
		String[] parts = decode(cursor, order, PageOrder.OLDEST);
		return new TradingQueries.AccountPosition(instant(parts[0]), uuid(parts[1]));
	}

	static String trades(TradingQueries.TradePosition position, PageOrder order) {
		return encode(order, position.commandSequence() + "|" + position.tradeSequence());
	}

	static TradingQueries.TradePosition trades(String cursor, PageOrder order) {
		String[] parts = decode(cursor, order, PageOrder.NEWEST);
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

	/** Where a page of bot runs ends: by start time, then by ID. */
	record RunPosition(Instant startedAt, UUID id) {
	}

	static String runs(RunPosition position, PageOrder order) {
		return encode(order, position.startedAt() + "|" + position.id());
	}

	static RunPosition runs(String cursor, PageOrder order) {
		String[] parts = decode(cursor, order, PageOrder.NEWEST);
		return new RunPosition(instant(parts[0]), uuid(parts[1]));
	}

	private static String encode(PageOrder order, String position) {
		String value = "v2:" + order.wire() + "|" + position;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	/** The cursor's two position parts, if it was issued in {@code order}; a v1 cursor stands for {@code v1Order}. */
	private static String[] decode(String cursor, PageOrder order, PageOrder v1Order) {
		String text;
		try {
			text = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
		}
		catch (IllegalArgumentException ex) {
			throw invalid();
		}
		if (text.startsWith("v1:")) {
			String[] parts = text.substring(3).split("\\|", -1);
			if (parts.length != 2 || order != v1Order) {
				throw invalid();
			}
			return parts;
		}
		String[] parts = text.startsWith("v2:") ? text.substring(3).split("\\|", -1) : new String[0];
		if (parts.length != 3 || !parts[0].equals(order.wire())) {
			throw invalid();
		}
		return new String[] { parts[1], parts[2] };
	}

	/** Positions we issue are row timestamps; anything outside years 1 to 9999 cannot be one, nor be queried. */
	private static final Instant EARLIEST = Instant.parse("0001-01-01T00:00:00Z");

	private static final Instant LATEST = Instant.parse("9999-12-31T23:59:59.999999Z");

	private static Instant instant(String text) {
		Instant parsed;
		try {
			parsed = Instant.parse(text);
		}
		catch (DateTimeParseException ex) {
			throw invalid();
		}
		if (parsed.isBefore(EARLIEST) || parsed.isAfter(LATEST)) {
			throw invalid();
		}
		return parsed;
	}

	private static UUID uuid(String text) {
		try {
			return UUID.fromString(text);
		}
		catch (IllegalArgumentException ex) {
			throw invalid();
		}
	}

	private static ApiException invalid() {
		return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The request is invalid",
				List.of(new ApiException.FieldError("cursor", "INVALID_CURSOR", "cursor is not one this API issued")));
	}

}
