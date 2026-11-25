package com.mia_princz.mse.execution.trading.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** The simulation name's rule, on chosen names and on names generated from a fixed seed, so a failure repeats exactly. */
class SimulationNameTest {

	private static final long SEED = 20261123L;

	private static final String PRINTABLE = "AZaz09.,&'-()éßÖøçñÆœΩλжЯ漢字カナ한글عربيةעברית€$%#@!?/:;+";

	/** Written as escapes, so none of them hides in the source. Each breaks the rule inside a name. */
	private static final List<String> FORBIDDEN = List.of("\t", "\n", "\r", "\u0000", "\u0007", "\u007F", "\u0085",
			" ", " ", "​", "‍", "‎", "‮", " ", " ", "﻿", "",
			"\uD800", "͸", "￿");

	@Test
	void namesInAnyLanguageAreKeptAsTheyAre() {
		for (String name : List.of("A", "Thursday seminar", "Séance du jeudi", "木曜日のゼミ", "Run 2  (two spaces)",
				"x".repeat(80), "😀 demo")) {
			assertThat(new SimulationName(name).value()).isEqualTo(name);
		}
	}

	/** White space around a name shows nowhere, so it is dropped before anything is counted or compared. */
	@Test
	void whiteSpaceAroundANameIsDropped() {
		assertThat(new SimulationName("  Thursday seminar \t\n").value()).isEqualTo("Thursday seminar");
		assertThat(new SimulationName(" " + "x".repeat(80) + " ").value()).hasSize(80);
		assertThat(new SimulationName("\tA").toString()).isEqualTo("A");
	}

	@Test
	void anEmptyOrTooLongNameIsRefused() {
		for (String name : List.of("", " ", " \t\n ", "x".repeat(81), " " + "x".repeat(81) + " ")) {
			assertThatThrownBy(() -> new SimulationName(name)).as("'%s'", name)
				.isInstanceOf(InvalidSimulationNameException.class)
				.hasMessage("name must be 1 to 80 characters");
		}
		assertThatThrownBy(() -> new SimulationName(null)).isInstanceOf(NullPointerException.class);
	}

	/** Length is counted in characters, so 80 characters outside the BMP are allowed and 81 are not. */
	@Test
	void lengthCountsCharactersNotUtf16Units() {
		assertThat(new SimulationName("😀".repeat(80)).value()).hasSize(160);
		assertThatThrownBy(() -> new SimulationName("😀".repeat(81))).isInstanceOf(InvalidSimulationNameException.class);
	}

	@Test
	void generatedNamesAreKeptAndAnyForbiddenCharacterInsideBreaksThem() {
		Random random = new Random(SEED);
		for (int i = 0; i < 2_000; i++) {
			String name = generated(random);
			assertThat(new SimulationName(name).value()).isEqualTo(name);
			String forbidden = FORBIDDEN.get(random.nextInt(FORBIDDEN.size()));
			// Between two characters of the name: at either end, white space is dropped instead.
			String broken = name.charAt(0) + forbidden + name;
			assertThatThrownBy(() -> new SimulationName(broken)).as("%s with U+%04X", name, (int) forbidden.charAt(0))
				.isInstanceOf(InvalidSimulationNameException.class)
				.hasMessageContaining("printable");
		}
	}

	/** Words of printable characters separated by spaces, at most 80 characters in all, none at either end. */
	private static String generated(Random random) {
		StringBuilder name = new StringBuilder();
		int words = 1 + random.nextInt(5);
		for (int word = 0; word < words; word++) {
			if (word > 0) {
				name.append(' ');
			}
			int letters = 1 + random.nextInt(12);
			for (int letter = 0; letter < letters; letter++) {
				name.append(PRINTABLE.charAt(random.nextInt(PRINTABLE.length())));
			}
		}
		return name.length() > SimulationName.MAX_LENGTH ? name.substring(0, SimulationName.MAX_LENGTH).strip()
				: name.toString();
	}

}
