package com.mia_princz.mse.execution.trading.adapter.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** A request the API answers with a problem response: a stable code, a detail, and optional field errors. */
final class ApiException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	record FieldError(String field, String code, String message) {
	}

	private final HttpStatus status;

	private final String code;

	private final List<FieldError> errors;

	private final Map<String, Object> properties = new LinkedHashMap<>();

	private int retryAfterSeconds = 1;

	ApiException(HttpStatus status, String code, String detail) {
		this(status, code, detail, List.of());
	}

	ApiException(HttpStatus status, String code, String detail, List<FieldError> errors) {
		super(detail);
		this.status = status;
		this.code = code;
		this.errors = List.copyOf(errors);
	}

	ApiException with(String name, Object value) {
		properties.put(name, value);
		return this;
	}

	/** How long a client should wait before retrying; sent as {@code Retry-After} with a 503. */
	ApiException retryAfter(int seconds) {
		this.retryAfterSeconds = seconds;
		return this;
	}

	HttpStatus status() {
		return status;
	}

	int retryAfterSeconds() {
		return retryAfterSeconds;
	}

	ProblemDetail toProblem() {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, getMessage());
		problem.setProperty("code", code);
		if (!errors.isEmpty()) {
			problem.setProperty("errors", errors);
		}
		properties.forEach(problem::setProperty);
		return problem;
	}

}
