package com.mia_princz.mse.execution.trading.domain;

import java.util.Objects;

/**
 * The name an operator gives a simulation, to tell it from the ones before it: 1 to 80 printable characters in any
 * language. White space around it is dropped, since it shows nowhere and would make two names that read the same
 * differ. Inside it, spaces are plain spaces; control, invisible formatting, private-use and unassigned characters are
 * refused, since they would show as nothing, or as something else, on another screen.
 */
public record SimulationName(String value) {

	public static final int MAX_LENGTH = 80;

	public SimulationName {
		Objects.requireNonNull(value, "value");
		value = value.strip();
		int length = value.codePointCount(0, value.length());
		if (length < 1 || length > MAX_LENGTH) {
			throw new InvalidSimulationNameException("name must be 1 to " + MAX_LENGTH + " characters");
		}
		if (!value.codePoints().allMatch(SimulationName::printable)) {
			throw new InvalidSimulationNameException(
					"name must have only printable characters, with plain spaces between words");
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
