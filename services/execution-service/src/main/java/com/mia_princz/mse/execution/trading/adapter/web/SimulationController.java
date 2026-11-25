package com.mia_princz.mse.execution.trading.adapter.web;

import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mia_princz.mse.execution.trading.application.CommandKey;
import com.mia_princz.mse.execution.trading.application.SimulationStart;
import com.mia_princz.mse.execution.trading.application.StartSimulation;
import com.mia_princz.mse.execution.trading.application.TradingEngine;

/**
 * The operator starts a new simulation once the one before it has closed. It is sequenced on the writer and audited,
 * with an idempotency key, like the market's transitions; the new simulation then goes through setup like the first.
 */
@RestController
@RequestMapping("/api/v1/admin")
class SimulationController {

	static final String START_ROUTE = "POST /api/v1/admin/simulations";

	private final TradingEngine engine;

	private final TradingQueries queries;

	private final Duration timeout;

	SimulationController(TradingEngine engine, TradingQueries queries,
			@Value("${mse.trading.command-timeout:5s}") Duration timeout) {
		this.engine = engine;
		this.queries = queries;
		this.timeout = timeout;
	}

	record SimulationResponse(String simulationId, String name, String state, String commandSequence) {
	}

	/**
	 * No lookup by key serves a start, so a wait that runs out says to send the key again: it returns the first
	 * answer, in whichever simulation that was stored.
	 */
	@PostMapping("/simulations")
	ResponseEntity<SimulationResponse> start(@RequestHeader(name = "Idempotency-Key", required = false) String keyHeader,
			@RequestBody SimulationBody body, Authentication authentication) {
		UUID key = CommandCalls.idempotencyKey(keyHeader);
		SimulationBody.Valid simulation = body.validate();
		UUID operator = queries.userId(authentication.getName()).orElseThrow();
		CommandKey commandKey = new CommandKey(operator, key, START_ROUTE, simulation.hash());
		StartSimulation command = new StartSimulation(UUID.randomUUID(), simulation.name(), operator);
		SimulationStart result = CommandCalls.await(key, timeout,
				"The start may still commit; send the same key again for its answer",
				() -> engine.startSimulation(commandKey, command));
		String sequence = Long.toString(result.commandSequence());
		return switch (result.status()) {
			case STARTED -> ResponseEntity.status(HttpStatus.CREATED)
				.body(new SimulationResponse(result.simulationId().toString(), result.name(), result.state(), sequence));
			case NOT_CLOSED -> throw new ApiException(HttpStatus.CONFLICT, "SIMULATION_NOT_CLOSED",
					"SETUP".equals(result.state())
							? "The current simulation is in SETUP and has not opened; set that one up, since a new one "
									+ "starts only after the current one has closed"
							: "The current simulation is " + result.state()
									+ "; close the market before starting a new one")
				.with("state", result.state())
				.with("commandSequence", sequence);
			case NAME_TAKEN -> throw new ApiException(HttpStatus.CONFLICT, "SIMULATION_NAME_TAKEN",
					"A simulation named " + result.name() + " already exists; names differ, whatever their case")
				.with("commandSequence", sequence);
		};
	}

}
