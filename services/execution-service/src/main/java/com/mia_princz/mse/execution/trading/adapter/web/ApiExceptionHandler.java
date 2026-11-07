package com.mia_princz.mse.execution.trading.adapter.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Problem responses for the trading API's controllers, with a stable code. */
@RestControllerAdvice(basePackageClasses = ApiExceptionHandler.class)
class ApiExceptionHandler {

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ProblemDetail> problem(ApiException ex) {
		ResponseEntity.BodyBuilder response = ResponseEntity.status(ex.status());
		if (ex.status() == HttpStatus.SERVICE_UNAVAILABLE || ex.status() == HttpStatus.TOO_MANY_REQUESTS) {
			response.header("Retry-After", Integer.toString(ex.retryAfterSeconds()));
		}
		return response.body(ex.toProblem());
	}

	/** A malformed id or number, such as {@code limit=many}: answered with the same field error as any other. */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ResponseEntity<ProblemDetail> mismatch(MethodArgumentTypeMismatchException ex) {
		return problem(ApiException.invalid(ex.getName(), "INVALID_VALUE", ex.getName() + " has an invalid value"));
	}

}
