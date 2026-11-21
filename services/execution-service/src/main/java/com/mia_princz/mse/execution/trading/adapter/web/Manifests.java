package com.mia_princz.mse.execution.trading.adapter.web;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/**
 * The shape a worker's manifest must have. Its settings are open, so a simulator can report a new one without
 * Execution changing first, but bounded: named strategies, at most twenty settings each, plain values (printable
 * ASCII texts, numbers of at most 32 characters written out), 8 KiB in all. The body comes from the simulator's
 * credential, so it is checked like any other input. Plain values also keep PostgreSQL's text of the manifest, which
 * the size is finally measured on, close to what was sent: it writes a number such as 1e300 out in full, and refuses
 * a NUL.
 */
final class Manifests {

	private Manifests() {
	}

	static final int MAX_BYTES = 8_192;

	private static final int MAX_ENTRIES = 20;

	private static final int MAX_TEXT = 64;

	private static final int MAX_NUMBER = 32;

	private static final Pattern TEXT = Pattern.compile("[ -~]{1," + MAX_TEXT + "}");

	private static final Set<String> FIELDS = Set.of("version", "runtime", "strategies", "defaults");

	private static final Set<String> STRATEGIES = Set.of("MARKET_MAKER", "NOISE", "MOMENTUM");

	private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z.+_-]{1,64}");

	private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9]{0,39}");

	/** Adds a field error for each way the manifest is wrong; none for a manifest that wasn't sent. */
	static void check(JsonNode manifest, List<ApiException.FieldError> errors) {
		if (manifest == null) {
			return;
		}
		if (!manifest.isObject() || !FIELDS.equals(names(manifest))) {
			errors.add(new ApiException.FieldError("manifest", "INVALID_VALUE",
					"manifest must be an object of version, runtime, strategies and defaults"));
			return;
		}
		if (manifest.toString().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
			errors.add(new ApiException.FieldError("manifest", "TOO_LARGE",
					"manifest must be at most " + MAX_BYTES + " bytes"));
		}
		JsonNode version = manifest.get("version");
		if (!version.isString() || !VERSION.matcher(version.asString()).matches()) {
			errors.add(new ApiException.FieldError("manifest.version", "INVALID_VALUE",
					"manifest.version must be 1 to 64 letters, digits or . + _ -"));
		}
		if (!settings(manifest.get("runtime"), true)) {
			errors.add(new ApiException.FieldError("manifest.runtime", "INVALID_VALUE",
					"manifest.runtime must name at most " + MAX_ENTRIES + " versions, each a short text"));
		}
		JsonNode strategies = manifest.get("strategies");
		if (!strategies.isObject() || !STRATEGIES.containsAll(names(strategies))
				|| !strategies.values().stream().allMatch((each) -> settings(each, false))) {
			errors.add(new ApiException.FieldError("manifest.strategies", "INVALID_VALUE",
					"manifest.strategies must hold settings for MARKET_MAKER, NOISE or MOMENTUM only"));
		}
		if (!settings(manifest.get("defaults"), false)) {
			errors.add(new ApiException.FieldError("manifest.defaults", "INVALID_VALUE",
					"manifest.defaults must hold at most " + MAX_ENTRIES + " named plain values"));
		}
	}

	/** An object of at most twenty named values: texts only, or numbers, booleans and short texts. */
	private static boolean settings(JsonNode node, boolean textOnly) {
		if (!node.isObject() || node.size() > MAX_ENTRIES) {
			return false;
		}
		for (Map.Entry<String, JsonNode> entry : node.properties()) {
			JsonNode value = entry.getValue();
			boolean text = value.isString() && TEXT.matcher(value.asString()).matches();
			boolean plain = text || (!textOnly && (number(value) || value.isBoolean()));
			if (!NAME.matcher(entry.getKey()).matches() || !plain) {
				return false;
			}
		}
		return true;
	}

	/** A finite number of at most 32 characters written out in full, as PostgreSQL will write it. */
	private static boolean number(JsonNode value) {
		if (!value.isNumber() || (value.isFloatingPointNumber() && !Double.isFinite(value.asDouble()))) {
			return false;
		}
		return value.decimalValue().toPlainString().length() <= MAX_NUMBER;
	}

	private static Set<String> names(JsonNode node) {
		return Set.copyOf(node.propertyNames());
	}

}
