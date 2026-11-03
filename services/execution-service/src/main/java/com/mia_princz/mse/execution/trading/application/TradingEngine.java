package com.mia_princz.mse.execution.trading.application;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.mia_princz.mse.execution.trading.application.CommandWriter.WriterHaltException;
import com.mia_princz.mse.execution.trading.application.PlacementResult.OrderState;
import com.mia_princz.mse.execution.trading.application.TradingStore.Market;
import com.mia_princz.mse.execution.trading.application.TradingStore.NewOrder;
import com.mia_princz.mse.execution.trading.application.TransactionRunner.CommitOutcomeUnknownException;
import com.mia_princz.mse.execution.trading.domain.BookOrder;
import com.mia_princz.mse.execution.trading.domain.DepthLevel;
import com.mia_princz.mse.execution.trading.domain.Fill;
import com.mia_princz.mse.execution.trading.domain.MatchPlan;
import com.mia_princz.mse.execution.trading.domain.OrderBook;
import com.mia_princz.mse.execution.trading.domain.OrderRequest;
import com.mia_princz.mse.execution.trading.domain.RejectionCode;
import com.mia_princz.mse.execution.trading.domain.Settlement;
import com.mia_princz.mse.execution.trading.domain.SettlementPlan;
import com.mia_princz.mse.execution.trading.domain.Side;

/**
 * Processes trading commands on the single writer. Each command is one database transaction covering the risk check,
 * matching, the order and trade rows, the ledger, the balances and the outbox. The in-memory book changes only after
 * that transaction has committed; if anything leaves the two possibly out of step, the writer halts.
 */
public final class TradingEngine {

	private final TradingStore store;

	private final TransactionRunner transactions;

	private final CommandWriter writer;

	private final Clock clock;

	private final Map<UUID, OrderBook> books = new HashMap<>();

	private final Map<UUID, String> symbols = new HashMap<>();

	private UUID simulationId;

	private long writerEpoch;

	public TradingEngine(TradingStore store, TransactionRunner transactions, CommandWriter writer, Clock clock) {
		this.store = store;
		this.transactions = transactions;
		this.writer = writer;
		this.clock = clock;
	}

	/**
	 * Loads the market and creates an empty book per instrument. Rebuilding resting orders after a restart, and
	 * claiming writer ownership, are not part of this step yet.
	 */
	public void start() {
		transactions.inTransaction(() -> {
			Market market = store.lockMarket();
			simulationId = market.simulationId();
			writerEpoch = market.writerEpoch();
			for (TradingStore.Instrument instrument : store.instruments(simulationId)) {
				books.put(instrument.id(), new OrderBook(market.maxFillsPerCommand()));
				symbols.put(instrument.id(), instrument.symbol());
			}
			return null;
		});
	}

	/** Places an order. A repeat of the same key and request returns the first committed result unchanged. */
	public CompletableFuture<PlacementResult> place(CommandKey key, PlaceOrder command) {
		return writer.submit(() -> process(key, command));
	}

	/** Cancels a resting order; allowed while the market is open or halted. */
	public CompletableFuture<CancelResult> cancel(CommandKey key, CancelOrder command) {
		return writer.submit(() -> processCancel(key, command));
	}

	/** A snapshot of one side of a book, read on the writer thread so it never sees a half-applied plan. */
	public CompletableFuture<List<DepthLevel>> depth(UUID instrumentId, Side side) {
		return writer.submit(() -> books.get(instrumentId).depth(side, Integer.MAX_VALUE));
	}

	private record Outcome(PlacementResult result, MatchPlan plan) {
	}

	private PlacementResult process(CommandKey key, PlaceOrder command) {
		OrderBook book = books.get(command.instrumentId());
		if (book == null) {
			throw new IllegalArgumentException("Unknown instrument " + command.instrumentId());
		}
		Outcome outcome;
		try {
			outcome = transactions.inTransaction(() -> placeInTransaction(key, command, book));
		}
		catch (CommitOutcomeUnknownException | StaleWriterException ex) {
			throw new WriterHaltException("Trading state may differ from the database", ex);
		}
		if (outcome.plan() != null) {
			try {
				book.apply(outcome.plan());
			}
			catch (RuntimeException ex) {
				throw new WriterHaltException("The book could not apply a committed plan", ex);
			}
		}
		return outcome.result();
	}

	private record CancelOutcome(CancelResult result, UUID instrumentId) {
	}

	private CancelResult processCancel(CommandKey key, CancelOrder command) {
		CancelOutcome outcome;
		try {
			outcome = transactions.inTransaction(() -> cancelInTransaction(key, command));
		}
		catch (CommitOutcomeUnknownException | StaleWriterException | BookOutOfStepException ex) {
			throw new WriterHaltException("Trading state may differ from the database", ex);
		}
		if (outcome.instrumentId() != null && outcome.result().status() == CancelResult.Status.CANCELED
				&& books.get(outcome.instrumentId()).cancel(command.orderId()).isEmpty()) {
			throw new WriterHaltException("A committed cancel found no order in the book",
					new BookOutOfStepException(command.orderId()));
		}
		return outcome.result();
	}

	private CancelOutcome cancelInTransaction(CommandKey key, CancelOrder command) {
		Market market = store.lockMarket();
		if (market.writerEpoch() != writerEpoch) {
			throw new StaleWriterException();
		}
		Optional<TradingStore.StoredResult<CancelResult>> prior = store.findResult(simulationId, key, CancelResult.class);
		if (prior.isPresent()) {
			return new CancelOutcome(replay(key, prior.get()), null);
		}
		long sequence = store.nextCommandSequence(simulationId);
		TradingStore.ExistingOrder order = store.lockOrder(command.orderId())
			.filter((existing) -> existing.accountId().equals(command.accountId()))
			.orElse(null);
		if (order == null) {
			return new CancelOutcome(remember(key, 404,
					new CancelResult(command.orderId(), CancelResult.Status.NOT_FOUND, 0, sequence)), null);
		}
		CancelResult.Status status = switch (order.state()) {
			case "CANCELED" -> CancelResult.Status.ALREADY_CANCELED;
			case "FILLED", "REJECTED" -> CancelResult.Status.NOT_CANCELABLE;
			default -> CancelResult.Status.CANCELED;
		};
		if (status != CancelResult.Status.CANCELED) {
			int httpStatus = status == CancelResult.Status.NOT_CANCELABLE ? 409 : 200;
			return new CancelOutcome(remember(key, httpStatus, new CancelResult(command.orderId(), status, 0, sequence)),
					order.instrumentId());
		}

		BookOrder resting = books.get(order.instrumentId())
			.find(command.orderId())
			.filter((found) -> found.remainingQuantity() == order.working())
			.orElseThrow(() -> new BookOutOfStepException(command.orderId()));
		store.cancelOrder(command.orderId(), order.working(), "CANCELED_BY_OWNER");
		SettlementPlan release = Settlement.cancel(order.instrumentId(), resting);
		for (SettlementPlan.Batch batch : release.batches()) {
			store.insertBatch(simulationId, batch.cause(), null, sequence, batch.postings());
		}
		release.balanceChanges().forEach(store::applyBalance);
		store.touchAccounts(List.of(command.accountId()));
		return new CancelOutcome(remember(key, 200,
				new CancelResult(command.orderId(), CancelResult.Status.CANCELED, order.working(), sequence)),
				order.instrumentId());
	}

	private Outcome placeInTransaction(CommandKey key, PlaceOrder command, OrderBook book) {
		Market market = store.lockMarket();
		if (market.writerEpoch() != writerEpoch) {
			throw new StaleWriterException();
		}
		Optional<TradingStore.StoredResult<PlacementResult>> prior = store.findResult(simulationId, key,
				PlacementResult.class);
		if (prior.isPresent()) {
			return new Outcome(replay(key, prior.get()), null);
		}
		long sequence = store.nextCommandSequence(simulationId);
		if (!"OPEN".equals(market.state())) {
			return reject(key, command, RejectionCode.MARKET_NOT_OPEN, sequence);
		}
		TradingStore.Available available = store.available(command.accountId(), command.instrumentId());
		long hold = Settlement.holdAmount(command.side(), command.bound(), command.quantity().shares());
		if (command.side() == Side.BUY && available.cashCents() < hold) {
			return reject(key, command, RejectionCode.INSUFFICIENT_CASH, sequence);
		}
		if (command.side() == Side.SELL && available.shares() < hold) {
			return reject(key, command, RejectionCode.INSUFFICIENT_SHARES, sequence);
		}

		OrderRequest request = new OrderRequest(command.orderId(), command.accountId(), command.side(), command.type(),
				command.bound(), command.quantity(), sequence);
		MatchPlan plan = book.plan(request);
		if (plan.outcome() == MatchPlan.Outcome.EXECUTION_LIMIT_EXCEEDED) {
			return reject(key, command, RejectionCode.EXECUTION_LIMIT_EXCEEDED, sequence);
		}
		if (plan.rests() && (store.openOrders(command.accountId()) >= market.maxOpenOrdersPerAccount()
				|| store.openOrdersTotal(simulationId) >= market.maxOpenOrdersTotal())) {
			return reject(key, command, RejectionCode.ORDER_LIMIT_EXCEEDED, sequence);
		}

		PlacementResult result = accepted(command, plan, sequence);
		store.insertOrder(new NewOrder(command.orderId(), simulationId, request, command.instrumentId(), result.state(),
				result.reason(), result.filled(), result.working(), result.canceled(), sequence));
		write(command, plan, sequence);
		return new Outcome(remember(key, 201, result), plan);
	}

	private void write(PlaceOrder command, MatchPlan plan, long sequence) {
		Instant executedAt = clock.instant();
		Map<Integer, TradingStore.Trade> trades = new HashMap<>();
		Set<UUID> touched = new LinkedHashSet<>();
		touched.add(command.accountId());
		for (int i = 0; i < plan.fills().size(); i++) {
			Fill fill = plan.fills().get(i);
			store.recordMakerFill(fill.makerOrderId(), fill.quantity());
			TradingStore.Trade trade = store.insertTrade(simulationId, command.instrumentId(), plan.taker(), fill,
					executedAt, sequence);
			trades.put(i, trade);
			touched.add(fill.makerAccountId());
		}
		SettlementPlan settlement = Settlement.of(command.instrumentId(), plan);
		for (SettlementPlan.Batch batch : settlement.batches()) {
			TradingStore.Trade trade = trades.get(batch.fillIndex());
			store.insertBatch(simulationId, batch.cause(), trade == null ? null : trade.id(), sequence, batch.postings());
		}
		settlement.balanceChanges().forEach(store::applyBalance);
		store.touchAccounts(touched);
		for (int i = 0; i < plan.fills().size(); i++) {
			store.insertTradeEvent(simulationId, command.instrumentId(), symbols.get(command.instrumentId()), trades.get(i),
					plan.fills().get(i), executedAt, sequence);
		}
	}

	private static PlacementResult accepted(PlaceOrder command, MatchPlan plan, long sequence) {
		long filled = plan.filledQuantity();
		long remaining = plan.remainingQuantity();
		OrderState state;
		String reason = null;
		long working = 0;
		long canceled = 0;
		if (plan.outcome() == MatchPlan.Outcome.FILLED) {
			state = OrderState.FILLED;
		}
		else if (plan.rests()) {
			state = filled == 0 ? OrderState.OPEN : OrderState.PARTIALLY_FILLED;
			working = remaining;
		}
		else {
			state = OrderState.CANCELED;
			reason = plan.outcome().name();
			canceled = remaining;
		}
		return new PlacementResult(command.orderId(), state, reason, null, filled, working, canceled, sequence);
	}

	private Outcome reject(CommandKey key, PlaceOrder command, RejectionCode code, long sequence) {
		OrderRequest request = new OrderRequest(command.orderId(), command.accountId(), command.side(), command.type(),
				command.bound(), command.quantity(), sequence);
		store.insertOrder(new NewOrder(command.orderId(), simulationId, request, command.instrumentId(),
				OrderState.REJECTED, code.name(), 0, 0, 0, null));
		return new Outcome(remember(key, 422, new PlacementResult(command.orderId(), OrderState.REJECTED, code.name(),
				code, 0, 0, 0, sequence)), null);
	}

	/** Stores the first committed result in the same transaction, so a retry with the same key gets it back. */
	private <T> T remember(CommandKey key, int httpStatus, T result) {
		long sequence = result instanceof PlacementResult placement ? placement.commandSequence()
				: ((CancelResult) result).commandSequence();
		store.insertResult(simulationId, key, httpStatus, result, sequence);
		return result;
	}

	private static <T> T replay(CommandKey key, TradingStore.StoredResult<T> stored) {
		if (!key.sameRequest(stored.route(), stored.requestHash())) {
			throw new IdempotencyConflictException(key);
		}
		return stored.result();
	}

	/** The database and the in-memory book disagree about an order; continuing would trade on a wrong book. */
	static final class BookOutOfStepException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		BookOutOfStepException(UUID orderId) {
			super("The book and the database disagree about order " + orderId);
		}

	}

	/** Another process has claimed the market since this engine started; this one must stop writing. */
	static final class StaleWriterException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		StaleWriterException() {
			super("The writer epoch changed; another writer owns the market");
		}

	}

}
