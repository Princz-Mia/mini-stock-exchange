package com.mia_princz.mse.marketdata.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** A request answered with a problem response: a stable code, a detail, and optional field errors. */
final class ApiException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	record FieldError(String field, String code, String message) {
	}

	private final HttpStatus status;

	private final String code;

	private final List<FieldError> errors;

	ApiException(HttpStatus status, String code, String detail) {
		this(status, code, detail, List.of());
	}

	ApiException(HttpStatus status, String code, String detail, List<FieldError> errors) {
		super(detail);
		this.status = status;
		this.code = code;
		this.errors = List.copyOf(errors);
	}

	HttpStatus status() {
		return status;
	}

	ProblemDetail toProblem() {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, getMessage());
		problem.setProperty("code", code);
		if (!errors.isEmpty()) {
			problem.setProperty("errors", errors);
		}
		return problem;
	}

	static ApiException invalid(String field, String code, String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The request is invalid",
				List.of(new FieldError(field, code, message)));
	}

}
