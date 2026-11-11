package com.mia_princz.mse.execution.identity.adapter;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

/**
 * Which version of the first-login tour the signed-in user has seen, kept on the server so it follows them to any
 * browser. The web app shows the tour while the version it has is newer. Nothing here touches trading, so it does not
 * go through the writer.
 */
@RestController
@RequestMapping("/api/v1/me/tour")
class TourController {

	static final int MAX_VERSION = 1000;

	private final JdbcClient jdbc;

	TourController(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** 0 for a user who has never finished or skipped a tour. */
	@GetMapping
	Tour seen(Authentication authentication) {
		return new Tour(jdbc.sql("""
				SELECT p.tour_version_seen FROM user_preference p JOIN app_user u ON u.id = p.user_id
				WHERE u.username = :username""")
			.param("username", authentication.getName())
			.query(Integer.class)
			.optional()
			.orElse(0));
	}

	/**
	 * Records a version as seen. The stored version only rises, so replaying an older tour, or two tabs finishing in
	 * either order, never brings a newer one back. Answers with the version now stored. A session whose user no longer
	 * exists stores nothing and gets 401, as if it had no session.
	 */
	@PutMapping
	ResponseEntity<Tour> see(@RequestBody JsonNode body, Authentication authentication) {
		int version = versionIn(body);
		Optional<Integer> stored = jdbc.sql("""
				INSERT INTO user_preference (user_id, tour_version_seen)
				SELECT id, :version FROM app_user WHERE username = :username
				ON CONFLICT (user_id) DO UPDATE
				SET tour_version_seen = GREATEST(user_preference.tour_version_seen, EXCLUDED.tour_version_seen),
				    updated_at = now()
				RETURNING tour_version_seen""")
			.param("version", version)
			.param("username", authentication.getName())
			.query(Integer.class)
			.optional();
		return stored.map((seen) -> ResponseEntity.ok(new Tour(seen)))
			.orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
	}

	/**
	 * A whole number from 1 to 1000, written as a JSON number. Read by hand rather than bound, because binding would
	 * accept "7" or 1.9 and quietly turn them into 7 and 1.
	 */
	private static int versionIn(JsonNode body) {
		JsonNode version = body.isObject() && body.size() == 1 ? body.get("seenVersion") : null;
		if (version == null || !version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() < 1
				|| version.intValue() > MAX_VERSION) {
			throw new InvalidTourVersion();
		}
		return version.intValue();
	}

	/** The same problem as the trading API's invalid requests: VALIDATION_FAILED, naming the field. */
	@ExceptionHandler({ InvalidTourVersion.class, HttpMessageNotReadableException.class })
	ProblemDetail invalid() {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request is invalid");
		problem.setProperty("code", "VALIDATION_FAILED");
		problem.setProperty("errors", List.of(Map.of("field", "seenVersion", "code", "INVALID_VALUE", "message",
				"seenVersion must be a whole number from 1 to " + MAX_VERSION)));
		return problem;
	}

	record Tour(int seenVersion) {
	}

	static final class InvalidTourVersion extends RuntimeException {

		private static final long serialVersionUID = 1L;

		InvalidTourVersion() {
			super("Invalid tour version", null, false, false);
		}

	}

}
