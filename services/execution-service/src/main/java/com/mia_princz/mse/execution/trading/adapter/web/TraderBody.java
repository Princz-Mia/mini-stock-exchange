package com.mia_princz.mse.execution.trading.adapter.web;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

import com.mia_princz.mse.execution.trading.application.ProvisionAccount;

/**
 * The JSON body for adding a trader login: a username, a password, and optionally a starting endowment. Unknown JSON
 * fields are rejected before this class sees it. The password never appears in {@link #toString()}.
 */
record TraderBody(String username, String password, String cash, List<Endowments.HoldingBody> holdings) {

	static final Pattern USERNAME = Pattern.compile("[a-z0-9][a-z0-9._-]{2,31}");

	static final int MIN_PASSWORD = 12;

	static final int MAX_PASSWORD = 64;

	/** bcrypt reads at most 72 bytes; a longer password would be cut without a word. */
	static final int MAX_PASSWORD_BYTES = 72;

	/** {@code funded} is false when no endowment was given; the cash and holdings are then empty. */
	record Valid(String username, String password, boolean funded, long cashCents,
			List<ProvisionAccount.Holding> holdings, String canonical) {

		/**
		 * The username and the endowment, never the password: a hash of it here would let anyone who reads the stored
		 * answers test guesses against it. A retry under the same key answers the stored result whatever password it
		 * carries.
		 */
		byte[] hash() {
			return CommandCalls.sha256(username + "|" + (funded ? canonical : "-"));
		}

		@Override
		public String toString() {
			return "TraderBody.Valid[username=" + username + ", funded=" + funded + "]";
		}

	}

	Valid validate() {
		List<ApiException.FieldError> errors = new ArrayList<>();
		if (username == null || username.isEmpty()) {
			errors.add(new ApiException.FieldError("username", "REQUIRED", "username is required"));
		}
		else if (!USERNAME.matcher(username).matches()) {
			errors.add(new ApiException.FieldError("username", "INVALID_USERNAME",
					"username must be 3 to 32 lower-case letters, digits, '.', '-' or '_', starting with a letter "
							+ "or digit"));
		}
		if (password == null || password.isEmpty()) {
			errors.add(new ApiException.FieldError("password", "REQUIRED", "password is required"));
		}
		else if (password.isBlank()) {
			// Signing in refuses a blank password before checking it, so such a login could never be used.
			errors.add(new ApiException.FieldError("password", "INVALID_PASSWORD", "password must not be only spaces"));
		}
		else if (utf8Length(password) < 0) {
			errors.add(new ApiException.FieldError("password", "INVALID_PASSWORD",
					"password must be text: it has a broken character"));
		}
		else {
			int length = password.codePointCount(0, password.length());
			if (length < MIN_PASSWORD || length > MAX_PASSWORD || utf8Length(password) > MAX_PASSWORD_BYTES) {
				errors.add(new ApiException.FieldError("password", "INVALID_PASSWORD", "password must be "
						+ MIN_PASSWORD + " to " + MAX_PASSWORD + " characters, and at most " + MAX_PASSWORD_BYTES
						+ " bytes"));
			}
			else if (username != null && password.toLowerCase(Locale.ROOT).equals(username.toLowerCase(Locale.ROOT))) {
				errors.add(new ApiException.FieldError("password", "INVALID_PASSWORD",
						"password must not be the username"));
			}
		}
		boolean funded = cash != null || (holdings != null && !holdings.isEmpty());
		Endowments.Endowment endowment = funded ? Endowments.parse(cash, holdings, errors) : null;
		if (!errors.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The trader request is invalid",
					errors);
		}
		return funded
				? new Valid(username, password, true, endowment.cashCents(), endowment.holdings(),
						endowment.canonical())
				: new Valid(username, password, false, 0, List.of(), "");
	}

	/**
	 * The password's length in UTF-8, the bytes bcrypt reads, or -1 if it has a broken character: half of a
	 * surrogate pair, which a JSON escape can carry. Encoding would quietly turn that into '?', so the stored hash
	 * would match text the operator never sent.
	 */
	static int utf8Length(String text) {
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

	@Override
	public String toString() {
		return "TraderBody[username=" + username + ", cash=" + cash + ", holdings=" + holdings + "]";
	}

}
