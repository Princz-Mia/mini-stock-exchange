package com.mia_princz.mse.execution.trading.adapter.web;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;

import com.mia_princz.mse.execution.trading.domain.InvalidAmountException;
import com.mia_princz.mse.execution.trading.domain.Money;

/** The JSON body for starting a bot run. Unknown JSON fields are rejected before this class sees them. */
record BotRunBody(Integer marketMakers, Integer noise, Integer momentum, Long seed, String commandsPerSecond,
		Integer maxOrderQuantity, Integer maxPosition, Integer durationMinutes, List<UUID> instrumentIds) {

	static final int MAX_PER_STRATEGY = 1_000;

	static final long MAX_HUNDREDTHS_PER_SECOND = 20_000;

	record Valid(int marketMakers, int noise, int momentum, Long seed, long commandsPerSecondHundredths,
			int maxOrderQuantity, int maxPosition, Duration duration, List<UUID> instrumentIds) {

		/** The request as sent: a seed left out is chosen later, and does not change the request. */
		byte[] hash() {
			return CommandCalls.sha256(marketMakers + "|" + noise + "|" + momentum + "|" + seed + "|"
					+ commandsPerSecondHundredths + "|" + maxOrderQuantity + "|" + maxPosition + "|"
					+ (duration == null ? "" : duration.toMinutes()) + "|"
					+ instrumentIds.stream().sorted().map(UUID::toString).collect(Collectors.joining(",")));
		}

	}

	Valid validate() {
		List<ApiException.FieldError> errors = new ArrayList<>();
		int makers = count("marketMakers", marketMakers, errors);
		int noisy = count("noise", noise, errors);
		int trending = count("momentum", momentum, errors);
		if (errors.isEmpty() && makers + noisy + trending < 1) {
			errors.add(new ApiException.FieldError("noise", "NO_BOTS", "a run needs at least one bot"));
		}
		if (seed != null && seed < 0) {
			errors.add(new ApiException.FieldError("seed", "OUT_OF_RANGE", "seed must not be negative"));
		}
		long hundredths = 0;
		try {
			hundredths = commandsPerSecond == null ? -1 : Money.parse(commandsPerSecond).cents();
		}
		catch (InvalidAmountException ex) {
			hundredths = -1;
		}
		if (hundredths < 1 || hundredths > MAX_HUNDREDTHS_PER_SECOND) {
			errors.add(new ApiException.FieldError("commandsPerSecond", "OUT_OF_RANGE",
					"commandsPerSecond must be a decimal from 0.01 to 200.00"));
		}
		int quantity = bounded("maxOrderQuantity", maxOrderQuantity, 10, 1, 10_000, errors);
		int position = bounded("maxPosition", maxPosition, 100, 1, 1_000_000, errors);
		Duration duration = null;
		if (durationMinutes != null) {
			if (durationMinutes < 1 || durationMinutes > 1_440) {
				errors.add(new ApiException.FieldError("durationMinutes", "OUT_OF_RANGE",
						"durationMinutes must be between 1 and 1440"));
			}
			else {
				duration = Duration.ofMinutes(durationMinutes);
			}
		}
		List<UUID> instruments = instrumentIds == null ? List.of() : instrumentIds;
		if (instruments.size() > 50 || instruments.stream().anyMatch(Objects::isNull)
				|| new HashSet<>(instruments).size() != instruments.size()) {
			errors.add(new ApiException.FieldError("instrumentIds", "INVALID_VALUE",
					"instrumentIds must be at most 50 distinct instruments"));
		}
		if (!errors.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The bot run request is invalid",
					errors);
		}
		return new Valid(makers, noisy, trending, seed, hundredths, quantity, position, duration, instruments);
	}

	private static int count(String field, Integer value, List<ApiException.FieldError> errors) {
		if (value == null) {
			return 0;
		}
		if (value < 0 || value > MAX_PER_STRATEGY) {
			errors.add(new ApiException.FieldError(field, "OUT_OF_RANGE",
					field + " must be between 0 and " + MAX_PER_STRATEGY));
			return 0;
		}
		return value;
	}

	private static int bounded(String field, Integer value, int fallback, int min, int max,
			List<ApiException.FieldError> errors) {
		if (value == null) {
			return fallback;
		}
		if (value < min || value > max) {
			errors.add(new ApiException.FieldError(field, "OUT_OF_RANGE",
					field + " must be between " + min + " and " + max));
			return fallback;
		}
		return value;
	}

}
