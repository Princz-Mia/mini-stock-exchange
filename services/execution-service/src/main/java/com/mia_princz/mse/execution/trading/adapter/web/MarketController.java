package com.mia_princz.mse.execution.trading.adapter.web;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.core.Authentication;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.json.JsonMapper;

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

	/** Built as the application builds the converter that reads every other JSON body, from the same mapper. */
	private final JacksonJsonHttpMessageConverter json;

	MarketController(TradingEngine engine, TradingQueries queries,
			@Value("${mse.trading.command-timeout:5s}") Duration timeout,
			@Value("${mse.market.close-timeout:45s}") Duration closeTimeout, JsonMapper jsonMapper) {
		this.engine = engine;
		this.queries = queries;
		this.timeout = timeout;
		this.closeTimeout = closeTimeout;
		this.json = new JacksonJsonHttpMessageConverter(jsonMapper);
	}

	/**
	 * {@code state} is NOT_SET_UP, with the other fields null, until a market exists. {@code name} is also null for a
	 * simulation nobody named: one that the first setup command created. {@code closesAt} is null for a market with no
	 * duration.
	 */
	record MarketResponse(String state, String simulationId, String name, Instant openedAt, Instant closesAt,
			Instant closedAt, boolean closedOnSchedule, String commandSequence) {
	}

	/** {@code closesAt} is set only by an opening with a duration. */
	record TransitionResponse(String state, String commandSequence, Instant closesAt) {
	}

	record CloseResponse(String state, String commandSequence, int canceledOrders, int endedRuns) {
	}

	@GetMapping("/market")
	MarketResponse market() {
		return queries.market()
			.map((market) -> new MarketResponse(market.state(), market.simulationId().toString(), market.name(),
					market.openedAt(), market.closesAt(), market.closedAt(), market.closedOnSchedule(),
					Long.toString(market.commandSequence())))
			.orElse(new MarketResponse("NOT_SET_UP", null, null, null, null, null, false, null));
	}

	/**
	 * Opens the market, with an optional body that gives it a duration, after which the exchange closes it. With no
	 * body, or one without a duration, it stays open until an operator closes it, and the request is the one it always
	 * was: a key sent before durations existed means the same as before. An empty body, with any content type or none,
	 * is no body. With a duration, the duration is part of the request, so the same key with another duration is a
	 * conflict. The body comes as bytes, with the headers as the framework sees them, for {@link MarketOpenBody#read},
	 * which reads it before the key is looked at, where the framework would have.
	 */
	@PostMapping("/admin/market/open")
	TransitionResponse open(@RequestHeader(name = "Idempotency-Key", required = false) String keyHeader,
			HttpEntity<byte[]> sent, Authentication authentication) throws IOException, HttpMediaTypeNotSupportedException {
		MarketOpenBody request = MarketOpenBody.read(sent.getBody(), sent.getHeaders(), json);
		UUID key = CommandCalls.idempotencyKey(keyHeader);
		Duration duration = request == null ? null : request.validate();
		UUID operator = queries.userId(authentication.getName()).orElseThrow();
		byte[] hash = CommandCalls.sha256(duration == null ? OPEN_ROUTE : OPEN_ROUTE + "|" + duration.toMinutes());
		return control(MarketControl.Action.OPEN, key, new CommandKey(operator, key, OPEN_ROUTE, hash),
				new MarketControl(MarketControl.Action.OPEN, operator, duration));
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
		return control(action, key, commandKey, new MarketControl(action, operator));
	}

	private TransitionResponse control(MarketControl.Action action, UUID key, CommandKey commandKey,
			MarketControl command) {
		MarketTransition result = CommandCalls.await(key, timeout, () -> engine.control(commandKey, command));
		return switch (result.status()) {
			case APPLIED -> new TransitionResponse(result.state(), Long.toString(result.commandSequence()),
					result.closesAt());
			case NOT_ALLOWED -> throw notAllowed(action, result.state(), result.commandSequence());
			case NOT_SET_UP -> throw notSetUp();
			// The job as it was when the opening was refused, which a replay of the key repeats.
			case BOT_JOB_RUNNING -> throw new ApiException(HttpStatus.CONFLICT, "BOT_JOB_RUNNING",
					"Bots are still being created: " + BotJobViews.progress(result.botJob())
							+ ". The market can open once the job is done or stopped")
				.with("state", result.state())
				.with("commandSequence", Long.toString(result.commandSequence()))
				.with("botJob", BotJobViews.asItWas(result.botJob()));
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
