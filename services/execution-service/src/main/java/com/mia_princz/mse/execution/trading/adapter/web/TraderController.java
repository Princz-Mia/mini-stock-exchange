package com.mia_princz.mse.execution.trading.adapter.web;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mia_princz.mse.execution.trading.application.AddTrader;
import com.mia_princz.mse.execution.trading.application.CommandKey;
import com.mia_princz.mse.execution.trading.application.TradingEngine;
import com.mia_princz.mse.execution.trading.application.TraderResult;
import com.mia_princz.mse.execution.trading.domain.Money;

/**
 * The operator adds trader logins during SETUP, each optionally with its account and starting endowment, in one
 * audited writer command. The password is hashed here, before the command is queued, and goes no further than the
 * stored hash: not into the stored answer, the audit log, the request hash or this response.
 */
@RestController
@RequestMapping("/api/v1/admin")
class TraderController {

	static final String TRADER_ROUTE = "POST /api/v1/admin/traders";

	private final TradingEngine engine;

	private final TradingQueries queries;

	private final PasswordEncoder passwords;

	private final Duration timeout;

	TraderController(TradingEngine engine, TradingQueries queries, PasswordEncoder passwords,
			@Value("${mse.trading.command-timeout:5s}") Duration timeout) {
		this.engine = engine;
		this.queries = queries;
		this.passwords = passwords;
		this.timeout = timeout;
	}

	/** {@code accountId} and {@code cash} are null for a trader added without an account. */
	record TraderResponse(String username, String accountId, String cash, List<SetupController.HoldingResponse> holdings,
			String commandSequence) {
	}

	record TraderView(String username, boolean funded) {
	}

	record TraderPage(List<TraderView> traders, String nextCursor) {
	}

	/**
	 * Every trader login, wherever it came from, oldest first unless {@code order=newest}, in pages; with
	 * {@code funded=false}, those still without an account in this market, which setup can fund.
	 */
	@GetMapping("/traders")
	TraderPage traders(@RequestParam(required = false) Boolean funded, @RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "50") int limit, @RequestParam(required = false) String order) {
		Pages.checkLimit(limit);
		PageOrder direction = PageOrder.parse(order, PageOrder.OLDEST);
		TradingQueries.AccountPosition after = cursor == null ? null : Cursors.traders(cursor, direction, funded);
		List<TradingQueries.TraderLogin> page = queries.traders(funded, after, limit + 1, direction);
		List<TraderView> views = page.stream()
			.limit(limit)
			.map((trader) -> new TraderView(trader.username(), trader.funded()))
			.toList();
		if (page.size() <= limit) {
			return new TraderPage(views, null);
		}
		TradingQueries.TraderLogin last = page.get(limit - 1);
		return new TraderPage(views, Cursors.traders(new TradingQueries.AccountPosition(last.createdAt(), last.id()),
				direction, funded));
	}

	@PostMapping("/traders")
	ResponseEntity<TraderResponse> addTrader(@RequestHeader(name = "Idempotency-Key", required = false) String keyHeader,
			@RequestBody TraderBody body, Authentication authentication) {
		UUID key = CommandCalls.idempotencyKey(keyHeader);
		TraderBody.Valid trader = body.validate();
		UUID operator = queries.userId(authentication.getName()).orElseThrow();
		CommandKey commandKey = new CommandKey(operator, key, TRADER_ROUTE, trader.hash());
		AddTrader command = new AddTrader(UUID.randomUUID(), trader.funded() ? UUID.randomUUID() : null,
				trader.username(), passwords.encode(trader.password()), trader.cashCents(), trader.holdings(), operator);
		TraderResult result = CommandCalls.await(key, timeout, () -> engine.addTrader(commandKey, command));
		String sequence = Long.toString(result.commandSequence());
		return switch (result.status()) {
			case CREATED -> ResponseEntity.status(HttpStatus.CREATED)
				.body(new TraderResponse(result.username(),
						result.accountId() == null ? null : result.accountId().toString(),
						result.cashCents() == null ? null : new Money(result.cashCents()).format(),
						result.holdings()
							.stream()
							.map((holding) -> new SetupController.HoldingResponse(holding.instrumentId().toString(),
									holding.quantity()))
							.toList(),
						sequence));
			case NOT_IN_SETUP -> throw new ApiException(HttpStatus.CONFLICT, "MARKET_NOT_IN_SETUP",
					"Traders can only be added before the market opens")
				.with("commandSequence", sequence);
			case USERNAME_TAKEN -> throw new ApiException(HttpStatus.CONFLICT, "USERNAME_TAKEN",
					result.username() + " is taken")
				.with("commandSequence", sequence);
			case UNKNOWN_INSTRUMENT -> throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "UNKNOWN_INSTRUMENT",
					"A holding names an instrument this market does not have")
				.with("commandSequence", sequence);
		};
	}

}
