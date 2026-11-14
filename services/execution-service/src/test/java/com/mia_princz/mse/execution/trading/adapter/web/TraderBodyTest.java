package com.mia_princz.mse.execution.trading.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** The rules for a trader's username and password, the request hash, and what a logged body shows. */
class TraderBodyTest {

	private static final String PASSWORD = "a-good-password";

	@Test
	void usernamesAreThreeToThirtyTwoLowerCaseCharactersFromASmallSet() {
		for (String valid : List.of("abc", "maria", "a.b", "trader-2", "x_y", "0zero", "a".repeat(32))) {
			assertThat(problems(valid, PASSWORD)).as(valid).isEmpty();
		}
		for (String invalid : List.of("ab", "a".repeat(33), "Maria", ".abc", "-abc", "_abc", "ab c", "ab@c", "mária",
				"abc\n")) {
			assertThat(problems(invalid, PASSWORD)).as(invalid).containsExactly("username INVALID_USERNAME");
		}
		assertThat(problems(null, PASSWORD)).containsExactly("username REQUIRED");
		assertThat(problems("", PASSWORD)).containsExactly("username REQUIRED");
	}

	@Test
	void everyNameOfTheAllowedCharactersAndLengthsIsAccepted() {
		Random random = new Random(20261114);
		String first = "abcdefghijklmnopqrstuvwxyz0123456789";
		String rest = first + "._-";
		for (int i = 0; i < 500; i++) {
			StringBuilder name = new StringBuilder().append(first.charAt(random.nextInt(first.length())));
			int length = 3 + random.nextInt(30);
			while (name.length() < length) {
				name.append(rest.charAt(random.nextInt(rest.length())));
			}
			assertThat(problems(name.toString(), PASSWORD)).as(name.toString()).isEmpty();
		}
	}

	@Test
	void passwordsAreTwelveToSixtyFourCharactersOfAtMostSeventyTwoBytes() {
		assertThat(problems("maria", "x".repeat(11))).containsExactly("password INVALID_PASSWORD");
		assertThat(problems("maria", "x".repeat(12))).isEmpty();
		assertThat(problems("maria", "x".repeat(64))).isEmpty();
		assertThat(problems("maria", "x".repeat(65))).containsExactly("password INVALID_PASSWORD");
		// Characters, not bytes, count toward 12, but bcrypt reads only 72 bytes: 24 three-byte characters fit, 25 don't.
		assertThat(problems("maria", "€".repeat(12))).isEmpty();
		assertThat(problems("maria", "€".repeat(24))).isEmpty();
		assertThat(problems("maria", "€".repeat(25))).containsExactly("password INVALID_PASSWORD");
		// Twelve characters that take two code units each still count as twelve.
		assertThat(problems("maria", "😀".repeat(12))).isEmpty();
		assertThat(problems("maria", "😀".repeat(11))).containsExactly("password INVALID_PASSWORD");
		assertThat(problems("maria-twelve", "MARIA-TWELVE")).containsExactly("password INVALID_PASSWORD");
		assertThat(problems("maria", null)).containsExactly("password REQUIRED");
	}

	@Test
	void aPasswordSigningInWouldRefuseOrBcryptWouldChangeIsRefused() {
		// Only spaces: signing in refuses a blank password before checking it, so the login could never be used.
		assertThat(problems("maria", " ".repeat(12))).containsExactly("password INVALID_PASSWORD");
		assertThat(problems("maria", "\t".repeat(12))).containsExactly("password INVALID_PASSWORD");
		// Spaces inside or around other characters are kept as typed, as signing in does.
		assertThat(problems("maria", "  two words here  ")).isEmpty();
		// Half a surrogate pair, which a JSON escape can carry, would be hashed as '?'.
		assertThat(problems("maria", "\ud800".repeat(12))).containsExactly("password INVALID_PASSWORD");
		assertThat(problems("maria", "a-good-pass" + "\udc00")).containsExactly("password INVALID_PASSWORD");
		assertThat(TraderBody.utf8Length("€😀a")).isEqualTo(3 + 4 + 1);
		assertThat(TraderBody.utf8Length("\ud800")).isEqualTo(-1);
	}

	@Test
	void theHashLeavesThePasswordOutAndTellsAnEndowmentApart() {
		TraderBody.Valid plain = new TraderBody("maria", PASSWORD, null, null).validate();
		assertThat(plain.funded()).isFalse();
		assertThat(plain.hash()).isEqualTo(CommandCalls.sha256("maria|-"));
		assertThat(new TraderBody("maria", "another-password", null, null).validate().hash()).isEqualTo(plain.hash());
		TraderBody.Valid funded = new TraderBody("maria", PASSWORD, "10.00", List.of()).validate();
		assertThat(funded.funded()).isTrue();
		assertThat(funded.hash()).isEqualTo(CommandCalls.sha256("maria|1000|"));
	}

	@Test
	void aLoggedBodyNeverShowsThePassword() {
		TraderBody body = new TraderBody("maria", PASSWORD, "10.00", List.of());
		assertThat(body.toString()).contains("maria").doesNotContain(PASSWORD);
		assertThat(body.validate().toString()).contains("maria").doesNotContain(PASSWORD);
	}

	@SuppressWarnings("unchecked")
	private static List<String> problems(String username, String password) {
		ApiException refused = catchThrowableOfType(ApiException.class,
				() -> new TraderBody(username, password, null, null).validate());
		if (refused == null) {
			return List.of();
		}
		List<ApiException.FieldError> errors = (List<ApiException.FieldError>) refused.toProblem()
			.getProperties()
			.get("errors");
		return errors.stream().map((error) -> error.field() + " " + error.code()).toList();
	}

}
