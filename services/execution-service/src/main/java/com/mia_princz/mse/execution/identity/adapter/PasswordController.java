package com.mia_princz.mse.execution.identity.adapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mia_princz.mse.execution.identity.PasswordRules;

/**
 * A signed-in trader changes their own password, confirming the current one. A wrong current password counts against
 * the sign-in limiter, so the form cannot be used to guess it. Once changed, the trader's other sessions end, and this
 * one gets a new ID; any session signed in with the old password, even one still signing in, ends on its next
 * request ({@link PasswordVersionFilter}). There is no reset: a forgotten password stays forgotten.
 */
@RestController
@RequestMapping("/api/v1")
class PasswordController {

	private static final Logger log = LoggerFactory.getLogger(PasswordController.class);

	private final JdbcClient jdbc;

	private final PasswordEncoder passwords;

	private final FailedLoginLimiter failedLogins;

	private final FindByIndexNameSessionRepository<? extends Session> sessions;

	PasswordController(JdbcClient jdbc, PasswordEncoder passwords, FailedLoginLimiter failedLogins,
			FindByIndexNameSessionRepository<? extends Session> sessions) {
		this.jdbc = jdbc;
		this.passwords = passwords;
		this.failedLogins = failedLogins;
		this.sessions = sessions;
	}

	/** Neither password ever appears in {@link #toString()}. */
	record PasswordChange(String currentPassword, String newPassword) {

		@Override
		public String toString() {
			return "PasswordChange[]";
		}

	}

	record FieldError(String field, String code, String message) {
	}

	@PostMapping("/me/password")
	ResponseEntity<?> change(@RequestBody PasswordChange change, Authentication authentication,
			HttpServletRequest request) {
		String username = authentication.getName();
		List<FieldError> errors = new ArrayList<>();
		if (change.currentPassword() == null || change.currentPassword().isEmpty()) {
			errors.add(new FieldError("currentPassword", "REQUIRED", "currentPassword is required"));
		}
		PasswordRules.Problem rule = PasswordRules.check(username, change.newPassword());
		if (rule != null) {
			errors.add(new FieldError("newPassword", rule.code(), rule.message().replace("password", "the new password")));
		}
		else if (change.newPassword().equals(change.currentPassword())) {
			errors.add(new FieldError("newPassword", "INVALID_PASSWORD",
					"the new password must differ from the current one"));
		}
		if (!errors.isEmpty()) {
			return invalid(errors);
		}
		// Counted as a failure before the current password is checked, and given back if it is right, so guesses sent
		// at once cannot all pass the limit while the first is being hashed.
		long refused = failedLogins.reserve(username, request.getRemoteAddr());
		if (refused > 0) {
			long seconds = Math.max(1, (refused + 999_999_999L) / 1_000_000_000L);
			ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
					"Too many wrong passwords. Try again in " + seconds + " seconds.");
			problem.setProperty("code", "LOGIN_RATE_LIMITED");
			return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
				.header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
				.body(problem);
		}
		String hash = jdbc.sql("SELECT password_hash FROM app_user WHERE username = ? AND role = 'TRADER'")
			.param(username)
			.query(String.class)
			.optional()
			.orElse(null);
		if (hash == null || !passwords.matches(change.currentPassword(), hash)) {
			return wrongPassword();
		}
		failedLogins.succeeded(username, request.getRemoteAddr());
		// Only over the hash just checked: of two changes made at once, the second finds the hash changed and is
		// refused, rather than both answering as if theirs had won. The version rises with it, which ends every
		// session signed in with the old password on its next request.
		Optional<Integer> version = jdbc.sql("""
				UPDATE app_user SET password_hash = ?, password_version = password_version + 1
				WHERE username = ? AND role = 'TRADER' AND password_hash = ? RETURNING password_version""")
			.params(passwords.encode(change.newPassword()), username, hash)
			.query(Integer.class)
			.optional();
		if (version.isEmpty()) {
			return wrongPassword();
		}
		HttpSession current = request.getSession(false);
		if (current != null) {
			current.setAttribute(PasswordVersionFilter.SESSION_ATTRIBUTE, version.get());
		}
		// The other sessions end now too, rather than on their next request.
		String keep = current == null ? null : current.getId();
		Map<String, ? extends Session> signedIn = sessions.findByPrincipalName(username);
		signedIn.keySet().stream().filter((id) -> !id.equals(keep)).forEach(sessions::deleteById);
		if (current != null) {
			request.changeSessionId();
		}
		log.info("A trader changed their password; {} other sessions ended", Math.max(0, signedIn.size() - 1));
		return ResponseEntity.noContent().build();
	}

	private static ResponseEntity<ProblemDetail> wrongPassword() {
		return invalid(List.of(new FieldError("currentPassword", "WRONG_PASSWORD", "the current password is not right")));
	}

	private static ResponseEntity<ProblemDetail> invalid(List<FieldError> errors) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The password change is invalid");
		problem.setProperty("code", "VALIDATION_FAILED");
		problem.setProperty("errors", errors);
		return ResponseEntity.badRequest().body(problem);
	}

}
