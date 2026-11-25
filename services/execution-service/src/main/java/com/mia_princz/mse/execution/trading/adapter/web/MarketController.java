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
import com.mia_princz.mse.execution.trading.application.MarketClosing;
import com.mia_princz.mse.execution.trading.application.MarketControl;
import com.mia_princz.mse.execution.trading.application.MarketTransition;
import com.mia_princz.mse.execution.trading.application.TradingEngine;

/**
 * The authoritative trading phase, readable by anyone, and the operator's open, halt, resume and close commands.
 * Each command is sequenced on the writer and audited, and needs an idempotency key like any trading command.
 */
@RestController
@RequestMapping("/api/v1")
class MarketController {

	static final String OPEN_ROUTE = "POST /api/v1/admin/market/open";

	static final String HALT_ROUTE = "POST /api/v1/admin/market/halt";

	static final String RESUME_ROUTE = "POST /api/v1/admin/market/resume";

	static final String CLOSE_ROUTE = "POST /api/v1/admin/market/close";

	private final TradingEngine engine;

	private final TradingQueries queries;

	private final Duration timeout;

	private final Duration closeTimeout;

	MarketController(TradingEngine engine, TradingQueries queries,
			@Value("${mse.trading.command-timeout:5s}") Duration timeout,
			@Value("${mse.market.close-timeout:45s}") Duration closeTimeout) {
		this.engine = engine;
		this.queries = queries;
		this.timeout = timeout;
		this.closeTimeout = closeTimeout;
	}

	/**
	 * {@code state} is NOT_SET_UP, with the other fields null, until a market exists. {@code name} is also null for a
	 * simulation nobody named: one that the first setup command created.
	 */
	record MarketResponse(String state, String simulationId, String name, Instant openedAt, Instant closedAt,
			String commandSequence) {
	}

	record TransitionResponse(String state, String commandSequence) {
	}

	record CloseResponse(String state, String commandSequence, int canceledOrders, int endedRuns) {
	}

	@GetMapping("/market")
	MarketResponse market() {
		return queries.market()
			.map((market) -> new MarketResponse(market.state(), market.simulationId().toString(), market.name(),
					market.openedAt(), market.closedAt(), Long.toString(market.commandSequence())))
			.orElse(new MarketResponse("NOT_SET_UP", null, null, null, null, null));
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

	/**
	 * Closes the market for good. A close with many orders resting takes several transactions, so it waits longer for
	 * its answer than other commands do; past that, the close goes on and the same key returns its answer. No lookup
	 * by key serves a close, so the timeout says to send the key again. A close that committed and could not cancel
	 * every order is answered by {@link CommandCalls} as unfinished, not as failed, to the request that sent it and
	 * to any close sent before the restart.
	 */
	@PostMapping("/admin/market/close")
	CloseResponse close(@RequestHeader(name = "Idempotency-Key", required = false) String keyHeader,
			Authentication authentication) {
		UUID key = CommandCalls.idempotencyKey(keyHeader);
		UUID operator = queries.userId(authentication.getName()).orElseThrow();
		CommandKey commandKey = new CommandKey(operator, key, CLOSE_ROUTE, CommandCalls.sha256(CLOSE_ROUTE));
		MarketClosing result = CommandCalls.await(key, closeTimeout,
				"The close may still be going on; send the same key again for its answer",
				() -> engine.close(commandKey, new MarketControl(MarketControl.Action.CLOSE, operator)));
		return switch (result.status()) {
			case CLOSED -> new CloseResponse(result.state(), Long.toString(result.commandSequence()),
					result.canceledOrders(), result.endedRuns());
			case NOT_ALLOWED -> throw notAllowed(MarketControl.Action.CLOSE, result.state(), result.commandSequence());
			case NOT_SET_UP -> throw notSetUp();
		};
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
			case NOT_ALLOWED -> throw notAllowed(action, result.state(), result.commandSequence());
			case NOT_SET_UP -> throw notSetUp();
		};
	}

	private static ApiException notAllowed(MarketControl.Action action, String state, long commandSequence) {
		return new ApiException(HttpStatus.CONFLICT, "MARKET_TRANSITION_NOT_ALLOWED",
				"The market is " + state + "; it cannot " + action.name().toLowerCase() + " from there")
			.with("state", state)
			.with("commandSequence", Long.toString(commandSequence));
	}

	private static ApiException notSetUp() {
		return new ApiException(HttpStatus.CONFLICT, "MARKET_NOT_SET_UP", "No market has been set up yet");
	}

}
