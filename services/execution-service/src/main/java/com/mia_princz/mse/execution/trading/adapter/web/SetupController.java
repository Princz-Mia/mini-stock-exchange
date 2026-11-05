package com.mia_princz.mse.execution.trading.adapter.web;

import java.time.Duration;
import java.util.List;
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

import com.mia_princz.mse.execution.trading.application.AccountResult;
import com.mia_princz.mse.execution.trading.application.AddInstrument;
import com.mia_princz.mse.execution.trading.application.CommandKey;
import com.mia_princz.mse.execution.trading.application.InstrumentResult;
import com.mia_princz.mse.execution.trading.application.ProvisionAccount;
import com.mia_princz.mse.execution.trading.application.TradingEngine;
import com.mia_princz.mse.execution.trading.domain.Money;
import com.mia_princz.mse.execution.trading.domain.Price;

/**
 * The operator's SETUP commands. They run on the writer like trading commands, with an idempotency key, and are
 * audited; once the market has opened they are refused.
 */
@RestController
@RequestMapping("/api/v1/admin")
class SetupController {

	static final String INSTRUMENT_ROUTE = "POST /api/v1/admin/instruments";

	static final String ACCOUNT_ROUTE = "POST /api/v1/admin/accounts";

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

	record HoldingResponse(String instrumentId, long quantity) {
	}

	record AccountResponse(String accountId, String username, String cash, List<HoldingResponse> holdings,
			String commandSequence) {
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

	/** Only an existing trader can be given an account; an unknown or non-trader user is refused before the writer. */
	@PostMapping("/accounts")
	ResponseEntity<AccountResponse> provisionAccount(
			@RequestHeader(name = "Idempotency-Key", required = false) String keyHeader, @RequestBody AccountBody body,
			Authentication authentication) {
		UUID key = CommandCalls.idempotencyKey(keyHeader);
		AccountBody.Valid account = body.validate();
		UUID operator = queries.userId(authentication.getName()).orElseThrow();
		UUID owner = queries.traderUserId(account.username())
			.orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "UNKNOWN_TRADER",
					"No trader with that username"));
		CommandKey commandKey = new CommandKey(operator, key, ACCOUNT_ROUTE, account.hash());
		ProvisionAccount command = new ProvisionAccount(UUID.randomUUID(), owner, account.username(),
				account.cashCents(), account.holdings(), operator);
		AccountResult result = CommandCalls.await(key, timeout, () -> engine.provisionAccount(commandKey, command));
		String sequence = Long.toString(result.commandSequence());
		return switch (result.status()) {
			case CREATED -> ResponseEntity.status(HttpStatus.CREATED)
				.body(new AccountResponse(result.accountId().toString(), result.username(),
						new Money(result.cashCents()).format(),
						result.holdings()
							.stream()
							.map((holding) -> new HoldingResponse(holding.instrumentId().toString(), holding.quantity()))
							.toList(),
						sequence));
			case NOT_IN_SETUP -> throw new ApiException(HttpStatus.CONFLICT, "MARKET_NOT_IN_SETUP",
					"Accounts can only be provisioned before the market opens")
				.with("commandSequence", sequence);
			case ACCOUNT_EXISTS -> throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_EXISTS",
					result.username() + " already has an account")
				.with("commandSequence", sequence);
			case UNKNOWN_INSTRUMENT -> throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "UNKNOWN_INSTRUMENT",
					"A holding names an instrument this market does not have")
				.with("commandSequence", sequence);
		};
	}

}
