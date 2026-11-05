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

import com.mia_princz.mse.execution.trading.application.AddInstrument;
import com.mia_princz.mse.execution.trading.application.CommandKey;
import com.mia_princz.mse.execution.trading.application.InstrumentResult;
import com.mia_princz.mse.execution.trading.application.TradingEngine;
import com.mia_princz.mse.execution.trading.domain.Price;

/**
 * The operator's SETUP commands. They run on the writer like trading commands, with an idempotency key, and are
 * audited; once the market has opened they are refused.
 */
@RestController
@RequestMapping("/api/v1/admin")
class SetupController {

	static final String INSTRUMENT_ROUTE = "POST /api/v1/admin/instruments";

	private final TradingEngine engine;

	private final TradingQueries queries;

	private final Duration timeout;

	SetupController(TradingEngine engine, TradingQueries queries,
			@Value("${mse.trading.command-timeout:5s}") Duration timeout) {
		this.engine = engine;
		this.queries = queries;
		this.timeout = timeout;
	}

	record InstrumentResponse(String instrumentId, String symbol, String referencePrice, String commandSequence) {
	}

	@PostMapping("/instruments")
	ResponseEntity<InstrumentResponse> addInstrument(
			@RequestHeader(name = "Idempotency-Key", required = false) String keyHeader,
			@RequestBody InstrumentBody body, Authentication authentication) {
		UUID key = CommandCalls.idempotencyKey(keyHeader);
		InstrumentBody.Valid instrument = body.validate();
		UUID operator = queries.userId(authentication.getName()).orElseThrow();
		CommandKey commandKey = new CommandKey(operator, key, INSTRUMENT_ROUTE, instrument.hash());
		AddInstrument command = new AddInstrument(UUID.randomUUID(), instrument.symbol(), instrument.referencePrice(),
				operator);
		InstrumentResult result = CommandCalls.await(key, timeout, () -> engine.addInstrument(commandKey, command));
		return switch (result.status()) {
			case CREATED -> ResponseEntity.status(HttpStatus.CREATED)
				.body(new InstrumentResponse(result.instrumentId().toString(), result.symbol(),
						new Price(result.referencePriceCents()).format(), Long.toString(result.commandSequence())));
			case NOT_IN_SETUP -> throw new ApiException(HttpStatus.CONFLICT, "MARKET_NOT_IN_SETUP",
					"Instruments can only be added before the market opens")
				.with("commandSequence", Long.toString(result.commandSequence()));
			case SYMBOL_TAKEN -> throw new ApiException(HttpStatus.CONFLICT, "SYMBOL_TAKEN",
					"An instrument with symbol " + result.symbol() + " already exists")
				.with("commandSequence", Long.toString(result.commandSequence()));
		};
	}

}
