package com.mia_princz.mse.execution.trading.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** The display name's rule, on chosen names and on names generated from a fixed seed, so a failure repeats exactly. */
class DisplayNameTest {

	private static final long SEED = 20261113L;

	/** Letters, digits and punctuation from several scripts: every one may appear in a name. */
	private static final String PRINTABLE = "AZaz09.,&'-()éßÖøçñÆœΩλжЯ漢字カナ한글عربيةעברית€$%#@!?/:;+";

	/**
	 * Each one breaks the rule wherever it appears: controls, invisible formatting, other spaces, private use, a lone
	 * surrogate, and unassigned code points (U+0378 is unassigned in every Unicode version; U+FFFF is a noncharacter).
	 * Written as escapes, so none of them hides in the source.
	 */
	private static final List<String> FORBIDDEN = List.of("\t", "\n", "\r", "\u0000", "\u0007", "\u007F", "\u0085",
			"\u00A0", "\u2007", "\u200B", "\u200D", "\u200E", "\u202E", "\u2028", "\u2029", "\uFEFF", "\uE000",
			"\uD800", "\u0378", "\uFFFF");

	@Test
	void namesInAnyLanguageWithSingleSpacesAreKept() {
		for (String name : List.of("N", "Nova Dynamics", "Société Générale", "Ørsted A/S", "東京エレクトロン",
				"Samsung 삼성전자", "АО «Газпром»", "x".repeat(100), "😀 Emoji Co")) {
			assertThat(new DisplayName(name).value()).isEqualTo(name);
		}
	}

	@Test
	void eachBrokenRuleIsRefusedAsItIs() {
		for (String name : List.of("", " Nova", "Nova ", "Nova  Dynamics", "x".repeat(101), " ")) {
			assertThatThrownBy(() -> new DisplayName(name)).as("'%s'", name).isInstanceOf(InvalidDisplayNameException.class);
		}
	}

	/** Length is counted in characters, so a name of 100 characters outside the BMP is allowed and 101 is not. */
	@Test
	void lengthCountsCharactersNotUtf16Units() {
		assertThat(new DisplayName("😀".repeat(100)).value()).hasSize(200);
		assertThatThrownBy(() -> new DisplayName("😀".repeat(101))).isInstanceOf(InvalidDisplayNameException.class);
	}

	@Test
	void generatedNamesAreKeptAndAnyForbiddenCharacterBreaksThem() {
		Random random = new Random(SEED);
		for (int i = 0; i < 2_000; i++) {
			String name = generated(random);
			assertThat(new DisplayName(name).value()).isEqualTo(name);
			String forbidden = FORBIDDEN.get(random.nextInt(FORBIDDEN.size()));
			int at = random.nextInt(name.length() + 1);
			String broken = name.substring(0, at) + forbidden + name.substring(at);
			assertThatThrownBy(() -> new DisplayName(broken)).as("%s with U+%04X", name, (int) forbidden.charAt(0))
				.isInstanceOf(InvalidDisplayNameException.class);
		}
	}

	/** Words of printable characters separated by single spaces, at most 100 characters in all. */
	private static String generated(Random random) {
		StringBuilder name = new StringBuilder();
		int words = 1 + random.nextInt(5);
		for (int word = 0; word < words; word++) {
			if (word > 0) {
				name.append(' ');
			}
			int letters = 1 + random.nextInt(15);
			for (int letter = 0; letter < letters; letter++) {
				name.append(PRINTABLE.charAt(random.nextInt(PRINTABLE.length())));
			}
		}
		return name.length() > DisplayName.MAX_LENGTH ? name.substring(0, DisplayName.MAX_LENGTH).strip() : name.toString();
	}

}
