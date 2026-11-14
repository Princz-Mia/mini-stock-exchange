package com.mia_princz.mse.execution.trading.domain;

import java.util.Objects;

/**
 * A name to show beside an instrument's symbol: 1 to 100 printable characters in any language. Spaces are plain spaces,
 * one at a time and never at either end; control, invisible formatting, private-use and unassigned characters are
 * refused, since they would show as nothing, or as something else, on another screen. A name breaking a rule is
 * refused as it is, never trimmed or cleaned up.
 */
public record DisplayName(String value) {

	public static final int MAX_LENGTH = 100;

	public DisplayName {
		Objects.requireNonNull(value, "value");
		int length = value.codePointCount(0, value.length());
		if (length < 1 || length > MAX_LENGTH) {
			throw new InvalidDisplayNameException("displayName must be 1 to " + MAX_LENGTH + " characters");
		}
		if (value.startsWith(" ") || value.endsWith(" ")) {
			throw new InvalidDisplayNameException("displayName must not start or end with a space");
		}
		if (value.contains("  ")) {
			throw new InvalidDisplayNameException("displayName must not have two spaces in a row");
		}
		if (!value.codePoints().allMatch(DisplayName::printable)) {
			throw new InvalidDisplayNameException(
					"displayName must have only printable characters, with plain spaces between words");
		}
	}

	private static boolean printable(int codePoint) {
		if (codePoint == ' ') {
			return true;
		}
		return switch (Character.getType(codePoint)) {
			case Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE,
					Character.UNASSIGNED, Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR,
					Character.PARAGRAPH_SEPARATOR ->
				false;
			default -> true;
		};
	}

	@Override
	public String toString() {
		return value;
	}

}
