package com.mia_princz.mse.execution.trading.adapter.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/**
 * What a trader's history is narrowed to: one instrument, one side and, for orders, one state. Every part is
 * optional; none at all is the whole history. Values are checked here, so queries bind them as parameters only.
 */
record HistoryFilter(UUID instrumentId, String side, String state) {

	static final HistoryFilter NONE = new HistoryFilter(null, null, null);

	private static final Set<String> SIDES = Set.of("BUY", "SELL");

	private static final Set<String> STATES = Set.of("OPEN", "PARTIALLY_FILLED", "FILLED", "CANCELED", "REJECTED");

	/** The {@code instrumentId}, {@code side} and {@code state} query parameters; {@code state} only where allowed. */
	static HistoryFilter parse(String instrumentId, String side, String state) {
		UUID instrument = null;
		if (instrumentId != null) {
			try {
				instrument = UUID.fromString(instrumentId);
			}
			catch (IllegalArgumentException ex) {
				throw ApiException.invalid("instrumentId", "INVALID_VALUE", "instrumentId must be a UUID");
			}
		}
		if (side != null && !SIDES.contains(side)) {
			throw ApiException.invalid("side", "INVALID_VALUE", "side must be BUY or SELL");
		}
		if (state != null && !STATES.contains(state)) {
			throw ApiException.invalid("state", "INVALID_VALUE",
					"state must be OPEN, PARTIALLY_FILLED, FILLED, CANCELED or REJECTED");
		}
		return new HistoryFilter(instrument, side, state);
	}

	boolean isNone() {
		return instrumentId == null && side == null && state == null;
	}

	/**
	 * A short digest of the filter, carried in its cursors: a cursor reused under another filter would start from a
	 * position in a different list. Empty for no filter.
	 */
	String fingerprint() {
		if (isNone()) {
			return "";
		}
		String canonical = "i=" + (instrumentId == null ? "" : instrumentId) + ";s=" + (side == null ? "" : side) + ";t="
				+ (state == null ? "" : state);
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest, 0, 8);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is always available", ex);
		}
	}

}
