package com.mia_princz.mse.execution.trading.adapter.web;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mia_princz.mse.execution.trading.application.CommandKey;
import com.mia_princz.mse.execution.trading.application.MarketControl;
import com.mia_princz.mse.execution.trading.application.MarketTransition;
import com.mia_princz.mse.execution.trading.application.TradingEngine;

/**
 * The authoritative trading phase, readable by anyone, and the operator's open, halt and resume commands. Each
 * command is sequenced on the writer and audited, and needs an idempotency key like any trading command.
 */
@RestController
@RequestMapping("/api/v1")
class MarketController {

	static final String OPEN_ROUTE = "POST /api/v1/admin/market/open";

	static final String HALT_ROUTE = "POST /api/v1/admin/market/halt";

	static final String RESUME_ROUTE = "POST /api/v1/admin/market/resume";

	private final TradingEngine engine;

	private final TradingQueries queries;

	private final Duration timeout;

	MarketController(TradingEngine engine, TradingQueries queries,
			@Value("${mse.trading.command-timeout:5s}") Duration timeout) {
		this.engine = engine;
		this.queries = queries;
		this.timeout = timeout;
	}

	/** {@code state} is NOT_SET_UP, with the other fields null, until a market exists. */
	record MarketResponse(String state, String simulationId, Instant openedAt, String commandSequence) {
	}

	record TransitionResponse(String state, String commandSequence) {
	}

	@GetMapping("/market")
	MarketResponse market() {
		return queries.market()
			.map((market) -> new MarketResponse(market.state(), market.simulationId().toString(), market.openedAt(),
					Long.toString(market.commandSequence())))
			.orElse(new MarketResponse("NOT_SET_UP", null, null, null));
	}

	@PostMapping("/admin/market/open")
	TransitionResponse open(@RequestHeader(name = "Idempotency-Key", required = false) String key,
			Authentication authentication) {
		return control(MarketControl.Action.OPEN, OPEN_ROUTE, key, authentication);
	}

	@PostMapping("/admin/market/halt")
	TransitionResponse halt(@RequestHeader(name = "Idempotency-Key", required = false) String key,
			Authentication authentication) {
		return control(MarketControl.Action.HALT, HALT_ROUTE, key, authentication);
	}

	@PostMapping("/admin/market/resume")
	TransitionResponse resume(@RequestHeader(name = "Idempotency-Key", required = false) String key,
			Authentication authentication) {
		return control(MarketControl.Action.RESUME, RESUME_ROUTE, key, authentication);
	}

	private TransitionResponse control(MarketControl.Action action, String route, String keyHeader,
			Authentication authentication) {
		UUID key = CommandCalls.idempotencyKey(keyHeader);
		UUID operator = queries.userId(authentication.getName()).orElseThrow();
		// No body: the route alone is the request, so reusing a key for another action is a conflict.
		CommandKey commandKey = new CommandKey(operator, key, route, CommandCalls.sha256(route));
		MarketTransition result = CommandCalls.await(key, timeout,
				() -> engine.control(commandKey, new MarketControl(action, operator)));
		return switch (result.status()) {
			case APPLIED -> new TransitionResponse(result.state(), Long.toString(result.commandSequence()));
			case NOT_ALLOWED -> throw new ApiException(HttpStatus.CONFLICT, "MARKET_TRANSITION_NOT_ALLOWED",
					"The market is " + result.state() + "; it cannot " + action.name().toLowerCase() + " from there")
				.with("state", result.state())
				.with("commandSequence", Long.toString(result.commandSequence()));
			case NOT_SET_UP -> throw new ApiException(HttpStatus.CONFLICT, "MARKET_NOT_SET_UP",
					"No market has been set up yet");
		};
	}

}
