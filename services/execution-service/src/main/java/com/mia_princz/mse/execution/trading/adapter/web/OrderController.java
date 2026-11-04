package com.mia_princz.mse.execution.trading.adapter.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.json.JsonMapper;

import com.mia_princz.mse.execution.outbox.OutboxAdmission;
import com.mia_princz.mse.execution.trading.application.CancelOrder;
import com.mia_princz.mse.execution.trading.application.CancelResult;
import com.mia_princz.mse.execution.trading.application.CommandKey;
import com.mia_princz.mse.execution.trading.application.CommandWriter.WriterBusyException;
import com.mia_princz.mse.execution.trading.application.CommandWriter.WriterHaltException;
import com.mia_princz.mse.execution.trading.application.CommandWriter.WriterStoppedException;
import com.mia_princz.mse.execution.trading.application.IdempotencyConflictException;
import com.mia_princz.mse.execution.trading.application.PlaceOrder;
import com.mia_princz.mse.execution.trading.application.PlacementResult;
import com.mia_princz.mse.execution.trading.application.TradingEngine;

/**
 * Order commands and reads for the logged-in trader. The account always comes from the session, never from the
 * request. A first answer and every replay of the same key map to the same status and body.
 */
@RestController
@RequestMapping("/api/v1")
class OrderController {

	static final String PLACE_ROUTE = "POST /api/v1/orders";

	static final String CANCEL_ROUTE = "POST /api/v1/orders/{id}/cancel";

	/** Reads stored results with the same plain mapper that wrote them. */
	private static final JsonMapper STORED = JsonMapper.builder().build();

	private final TradingEngine engine;

	private final TradingQueries queries;

	private final OutboxAdmission admission;

	private final Duration timeout;

	OrderController(TradingEngine engine, TradingQueries queries, OutboxAdmission admission,
			@Value("${mse.trading.command-timeout:5s}") Duration timeout) {
		this.engine = engine;
		this.queries = queries;
		this.admission = admission;
		this.timeout = timeout;
	}

	record OrderResponse(String orderId, String state, String reason, long filledQuantity, long workingQuantity,
			long canceledQuantity, String commandSequence) {
	}

	record CancelResponse(String orderId, String status, long canceledQuantity, String commandSequence) {
	}

	@PostMapping("/orders")
	ResponseEntity<?> place(@RequestHeader(name = "Idempotency-Key", required = false) String keyHeader,
			@RequestBody OrderBody body, Authentication authentication) {
		TradingQueries.Trader trader = trader(authentication);
		UUID key = idempotencyKey(keyHeader);
		OrderBody.Valid order = body.validate();
		if (!admission.admitsNewOrders()) {
			// Not queued, like a full writer queue, so retrying with the same key later is safe.
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EXCHANGE_BUSY",
					"New orders are paused until market data publication catches up; cancels still work")
				.with("reason", "PUBLICATION_BACKLOG")
				.retryAfter(5);
		}
		CommandKey commandKey = new CommandKey(trader.userId(), key, PLACE_ROUTE, order.hash());
		PlaceOrder command = new PlaceOrder(UUID.randomUUID(), trader.accountId(), order.instrumentId(), order.side(),
				order.type(), order.bound(), order.quantity());
		return placement(await(key, () -> engine.place(commandKey, command)));
	}

	@PostMapping("/orders/{orderId}/cancel")
	ResponseEntity<?> cancel(@RequestHeader(name = "Idempotency-Key", required = false) String keyHeader,
			@PathVariable UUID orderId, Authentication authentication) {
		TradingQueries.Trader trader = trader(authentication);
		UUID key = idempotencyKey(keyHeader);
		CommandKey commandKey = new CommandKey(trader.userId(), key, CANCEL_ROUTE, sha256(orderId.toString()));
		return cancellation(await(key, () -> engine.cancel(commandKey, new CancelOrder(orderId, trader.accountId()))));
	}

	record OrderPage(List<TradingQueries.OrderView> orders, String nextCursor) {
	}

	/** Active orders all at once (at most the per-account limit), or the whole history in keyset pages. */
	@GetMapping("/orders")
	OrderPage orders(@RequestParam(defaultValue = "all") String status, @RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "50") int limit, Authentication authentication) {
		UUID account = trader(authentication).accountId();
		if ("active".equals(status)) {
			return new OrderPage(queries.activeOrders(account), null);
		}
		if (!"all".equals(status)) {
			throw ApiException.invalid("status", "INVALID_VALUE", "status must be all or active");
		}
		Pages.checkLimit(limit);
		TradingQueries.OrderPosition after = cursor == null ? null : Cursors.orders(cursor);
		List<TradingQueries.OrderView> page = queries.orders(account, after, limit + 1);
		if (page.size() <= limit) {
			return new OrderPage(page, null);
		}
		TradingQueries.OrderView last = page.get(limit - 1);
		return new OrderPage(page.subList(0, limit),
				Cursors.orders(new TradingQueries.OrderPosition(last.createdAt(), UUID.fromString(last.orderId()))));
	}

	@GetMapping("/orders/{orderId}")
	TradingQueries.OrderView order(@PathVariable UUID orderId, Authentication authentication) {
		return queries.order(orderId, trader(authentication).accountId())
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "No such order"));
	}

	/** The first committed result for one of the caller's keys. 404 means unknown, so retrying that key is safe. */
	@GetMapping("/commands/{key}")
	ResponseEntity<?> command(@PathVariable UUID key, Authentication authentication) {
		TradingQueries.StoredCommand stored = queries.command(trader(authentication).userId(), key)
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "COMMAND_NOT_FOUND",
					"No committed result for this key; retrying it is safe"));
		return PLACE_ROUTE.equals(stored.route()) ? placement(STORED.readValue(stored.body(), PlacementResult.class))
				: cancellation(STORED.readValue(stored.body(), CancelResult.class));
	}

	private static ResponseEntity<?> placement(PlacementResult result) {
		if (result.rejected()) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, result.rejection().name(), "The order was rejected")
				.with("orderId", result.orderId().toString())
				.with("commandSequence", Long.toString(result.commandSequence()));
		}
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(new OrderResponse(result.orderId().toString(), result.state().name(), result.reason(), result.filled(),
					result.working(), result.canceled(), Long.toString(result.commandSequence())));
	}

	private static ResponseEntity<?> cancellation(CancelResult result) {
		return switch (result.status()) {
			case CANCELED, ALREADY_CANCELED -> ResponseEntity.ok(new CancelResponse(result.orderId().toString(),
					result.status().name(), result.canceledQuantity(), Long.toString(result.commandSequence())));
			case NOT_CANCELABLE -> throw new ApiException(HttpStatus.CONFLICT, "ORDER_NOT_CANCELABLE",
					"The order is filled or was rejected");
			case NOT_FOUND -> throw new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "No such order");
		};
	}

	private interface Submission<T> {

		CompletableFuture<T> submit();

	}

	/** Waits for the committed result. A timeout does not cancel the command; its outcome is recoverable by key. */
	private <T> T await(UUID key, Submission<T> submission) {
		CompletableFuture<T> future;
		try {
			future = submission.submit();
		}
		catch (WriterBusyException ex) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EXCHANGE_BUSY",
					"The exchange is busy and did not accept the command; retry with the same key");
		}
		catch (WriterStoppedException ex) {
			throw unavailable();
		}
		try {
			return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException ex) {
			throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "COMMAND_OUTCOME_UNKNOWN",
					"The command may still complete; look it up by its key").with("commandKey", key.toString());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw unavailable();
		}
		catch (ExecutionException ex) {
			Throwable cause = ex.getCause();
			if (cause instanceof IdempotencyConflictException) {
				throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
						"This idempotency key was already used for a different request");
			}
			if (cause instanceof IllegalArgumentException) {
				throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "UNKNOWN_INSTRUMENT", "No such instrument");
			}
			if (cause instanceof WriterHaltException || cause instanceof WriterStoppedException) {
				throw unavailable();
			}
			throw new IllegalStateException("Command failed", cause);
		}
	}

	private static ApiException unavailable() {
		return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TRADING_UNAVAILABLE", "Trading is unavailable");
	}

	private TradingQueries.Trader trader(Authentication authentication) {
		return Pages.trader(queries, authentication);
	}

	private static UUID idempotencyKey(String header) {
		try {
			return UUID.fromString(header);
		}
		catch (IllegalArgumentException | NullPointerException ex) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
					"An Idempotency-Key header with a UUID is required");
		}
	}

	private static byte[] sha256(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
