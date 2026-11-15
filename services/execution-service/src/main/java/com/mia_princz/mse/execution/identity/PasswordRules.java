package com.mia_princz.mse.execution.identity;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * What a trader's password must be, whether the operator sets it or the trader changes it: 12 to 64 characters and at
 * most 72 bytes in UTF-8, the most bcrypt reads; not only spaces, since signing in refuses a blank password; no broken
 * character, which encoding would quietly turn into '?'; and not the username. Nothing else is asked of it.
 */
public final class PasswordRules {

	public static final int MIN = 12;

	public static final int MAX = 64;

	/** bcrypt reads at most 72 bytes; a longer password would be cut without a word. */
	public static final int MAX_BYTES = 72;

	/** Why a password may not be used: {@code REQUIRED} or {@code INVALID_PASSWORD}, and the message to show. */
	public record Problem(String code, String message) {
	}

	private PasswordRules() {
	}

	/** Null if {@code password} may be the password of {@code username}, or why not. */
	public static Problem check(String username, String password) {
		if (password == null || password.isEmpty()) {
			return new Problem("REQUIRED", "password is required");
		}
		if (password.isBlank()) {
			return new Problem("INVALID_PASSWORD", "password must not be only spaces");
		}
		int bytes = utf8Length(password);
		if (bytes < 0) {
			return new Problem("INVALID_PASSWORD", "password must be text: it has a broken character");
		}
		int length = password.codePointCount(0, password.length());
		if (length < MIN || length > MAX || bytes > MAX_BYTES) {
			return new Problem("INVALID_PASSWORD",
					"password must be " + MIN + " to " + MAX + " characters, and at most " + MAX_BYTES + " bytes");
		}
		if (username != null && password.toLowerCase(Locale.ROOT).equals(username.toLowerCase(Locale.ROOT))) {
			return new Problem("INVALID_PASSWORD", "password must not be the username");
		}
		return null;
	}

	/**
	 * The text's length in UTF-8, the bytes bcrypt reads, or -1 if it has a broken character: half of a surrogate
	 * pair, which a JSON escape can carry.
	 */
	public static int utf8Length(String text) {
		try {
			ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.encode(CharBuffer.wrap(text));
			return bytes.remaining();
		}
		catch (CharacterCodingException ex) {
			return -1;
		}
	}

}
