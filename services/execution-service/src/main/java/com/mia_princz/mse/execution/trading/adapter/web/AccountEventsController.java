package com.mia_princz.mse.execution.trading.adapter.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The signed-in trader's own change notifications. Only a session opens one, never a bot token: the stream is tied to
 * the session and closes when it ends.
 */
@RestController
class AccountEventsController {

	private final AccountStream stream;

	private final TradingQueries queries;

	AccountEventsController(AccountStream stream, TradingQueries queries) {
		this.stream = stream;
		this.queries = queries;
	}

	@GetMapping("/api/v1/account-events")
	SseEmitter events(Authentication authentication, HttpServletRequest request) {
		TradingQueries.Trader trader = Pages.trader(queries, authentication);
		HttpSession session = request.getSession(false);
		if (session == null) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "NO_SESSION", "Sign in to open your account stream");
		}
		try {
			return stream.subscribe(trader.accountId(), session.getId());
		}
		catch (AccountStream.TooManyStreamsException ex) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TOO_MANY_STREAMS", ex.getMessage());
		}
	}

	/** Problems keep their own content type, so a client that asked for an event stream still gets the status. */
	@ExceptionHandler(ApiException.class)
	ResponseEntity<ProblemDetail> problem(ApiException ex) {
		ResponseEntity.BodyBuilder response = ResponseEntity.status(ex.status())
			.contentType(MediaType.APPLICATION_PROBLEM_JSON);
		if (ex.status() == HttpStatus.SERVICE_UNAVAILABLE) {
			response.header("Retry-After", "5");
		}
		return response.body(ex.toProblem());
	}

}
