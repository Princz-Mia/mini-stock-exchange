package com.mia_princz.mse.execution.trading.adapter.web;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

import com.mia_princz.mse.execution.identity.PasswordRules;
import com.mia_princz.mse.execution.trading.application.ProvisionAccount;

/**
 * The JSON body for adding a trader login: a username, a password, and optionally a starting endowment. Unknown JSON
 * fields are rejected before this class sees it. The password never appears in {@link #toString()}.
 */
record TraderBody(String username, String password, String cash, List<Endowments.HoldingBody> holdings) {

	static final Pattern USERNAME = Pattern.compile("[a-z0-9][a-z0-9._-]{2,31}");

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
		PasswordRules.Problem refused = PasswordRules.check(username, password);
		if (refused != null) {
			errors.add(new ApiException.FieldError("password", refused.code(), refused.message()));
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

	@Override
	public String toString() {
		return "TraderBody[username=" + username + ", cash=" + cash + ", holdings=" + holdings + "]";
	}

}
