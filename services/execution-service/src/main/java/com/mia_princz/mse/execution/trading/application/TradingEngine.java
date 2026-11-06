package com.mia_princz.mse.execution.trading.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
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
import com.mia_princz.mse.execution.trading.domain.BookDelta;
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

	/** Levels per side in a public market snapshot. */
	static final int SNAPSHOT_LEVELS = 20;

	private final TradingStore store;

	private final TransactionRunner transactions;

	private final CommandWriter writer;

	private final Clock clock;

	private final BotCredentials botCredentials;

	private final Map<UUID, OrderBook> books = new HashMap<>();

	private final Map<UUID, String> symbols = new HashMap<>();

	private UUID simulationId;

	private long writerEpoch;

	public TradingEngine(TradingStore store, TransactionRunner transactions, CommandWriter writer, Clock clock,
			BotCredentials botCredentials) {
		this.store = store;
		this.transactions = transactions;
		this.writer = writer;
		this.clock = clock;
		this.botCredentials = botCredentials;
	}

	/**
	 * Takes over the market and rebuilds every book from the database: claims a new writer epoch, which fences any
	 * previous writer, restores resting orders in their original priority, then reconciles balances, the ledger and
	 * open orders. Any disagreement fails startup instead of trading on state that cannot be trusted.
	 */
	public void start() {
		transactions.inTransaction(() -> {
			Market market = store.lockMarket();
			UUID simulation = market.simulationId();
			long epoch = store.claimWriterEpoch(simulation);
			Map<UUID, OrderBook> rebuilt = new HashMap<>();
			Map<UUID, String> names = new HashMap<>();
			List<String> problems = new ArrayList<>();
			for (TradingStore.Instrument instrument : store.instruments(simulation)) {
				OrderBook book = new OrderBook(market.maxFillsPerCommand());
				store.activeOrders(instrument.id()).forEach(book::restore);
				if (book.isCrossed()) {
					problems.add("Book for " + instrument.symbol() + " is crossed after rebuilding");
				}
				rebuilt.put(instrument.id(), book);
				names.put(instrument.id(), instrument.symbol());
			}
			problems.addAll(store.reconciliationProblems(simulation));
			if (!problems.isEmpty()) {
				throw new ReconciliationException(problems);
			}
			simulationId = simulation;
			writerEpoch = epoch;
			books.putAll(rebuilt);
			symbols.putAll(names);
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

	/**
	 * Opens, halts or resumes the market. The new state takes effect at the command's sequence: every later command
	 * reads it from the locked market row. Books are untouched, so resting orders keep their priority.
	 */
	public CompletableFuture<MarketTransition> control(CommandKey key, MarketControl command) {
		return writer.submit(() -> processControl(key, command));
	}

	/**
	 * Adds an instrument while the market is in SETUP. The first one creates the market. The new instrument's empty
	 * book joins the engine only after the command has committed.
	 */
	public CompletableFuture<InstrumentResult> addInstrument(CommandKey key, AddInstrument command) {
		return writer.submit(() -> processAddInstrument(key, command));
	}

	/** Gives a trader their account and starting endowment while the market is in SETUP. */
	public CompletableFuture<AccountResult> provisionAccount(CommandKey key, ProvisionAccount command) {
		return writer.submit(() -> processProvision(key, command));
	}

	/** Creates funded bot accounts, each with its own API token, while the market is in SETUP. */
	public CompletableFuture<BotsResult> provisionBots(CommandKey key, ProvisionBots command) {
		return writer.submit(() -> processBots(key, command));
	}

	/** Starts a bot run while the market is open or halted; one run runs at a time. */
	public CompletableFuture<BotRunResult> startBotRun(CommandKey key, StartBotRun command) {
		return writer.submit(() -> processStartRun(key, command));
	}

	/** Asks a running bot run to stop. */
	public CompletableFuture<BotRunResult> stopBotRun(CommandKey key, StopBotRun command) {
		return writer.submit(() -> processStopRun(key, command));
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

	private record AddedInstrument(InstrumentResult result, boolean fresh, int maxFillsPerCommand) {
	}

	private InstrumentResult processAddInstrument(CommandKey key, AddInstrument command) {
		AddedInstrument added;
		try {
			ensureMarket();
			added = transactions.inTransaction(() -> addInstrumentInTransaction(key, command));
		}
		catch (CommitOutcomeUnknownException | StaleWriterException ex) {
			throw new WriterHaltException("Trading state may differ from the database", ex);
		}
		if (added.fresh()) {
			// Never replace a book that exists: it may hold resting orders.
			books.putIfAbsent(command.instrumentId(), new OrderBook(added.maxFillsPerCommand()));
			symbols.putIfAbsent(command.instrumentId(), command.symbol());
		}
		return added.result();
	}

	/**
	 * Creates the market, in SETUP with its issuance account, the first time something is set up. This commits on
	 * its own: an empty market in SETUP is a valid state, and the command that needed it then runs normally.
	 */
	private void ensureMarket() {
		if (simulationId != null) {
			return;
		}
		UUID simulation = UUID.randomUUID();
		long epoch = transactions.inTransaction(() -> store.createMarket(simulation, UUID.randomUUID()));
		simulationId = simulation;
		writerEpoch = epoch;
	}

	private AddedInstrument addInstrumentInTransaction(CommandKey key, AddInstrument command) {
		Market market = store.lockMarket();
		if (market.writerEpoch() != writerEpoch) {
			throw new StaleWriterException();
		}
		Optional<TradingStore.StoredResult<InstrumentResult>> prior = store.findResult(simulationId, key,
				InstrumentResult.class);
		if (prior.isPresent()) {
			return new AddedInstrument(replay(key, prior.get()), false, market.maxFillsPerCommand());
		}
		long sequence = store.nextCommandSequence(simulationId);
		InstrumentResult.Status refusal = !"SETUP".equals(market.state()) ? InstrumentResult.Status.NOT_IN_SETUP
				: store.symbolTaken(simulationId, command.symbol()) ? InstrumentResult.Status.SYMBOL_TAKEN : null;
		if (refusal != null) {
			store.insertAudit(simulationId, command.actorUserId(), "INSTRUMENT_ADD", "NOT_ALLOWED", key.idempotencyKey(),
					sequence);
			return new AddedInstrument(remember(key, 409, new InstrumentResult(refusal, null, command.symbol(),
					command.referencePrice().cents(), sequence)), false, market.maxFillsPerCommand());
		}
		store.insertInstrument(simulationId, command.instrumentId(), command.symbol(), command.referencePrice().cents());
		// The catalogue changed, so the new instrument's public state is published at this sequence.
		snapshot(market, command.instrumentId(), new OrderBook(market.maxFillsPerCommand()), BookDelta.none(),
				sequence);
		store.insertAudit(simulationId, command.actorUserId(), "INSTRUMENT_ADD", "APPLIED", key.idempotencyKey(),
				sequence);
		return new AddedInstrument(remember(key, 201, new InstrumentResult(InstrumentResult.Status.CREATED,
				command.instrumentId(), command.symbol(), command.referencePrice().cents(), sequence)), true,
				market.maxFillsPerCommand());
	}

	private AccountResult processProvision(CommandKey key, ProvisionAccount command) {
		try {
			ensureMarket();
			return transactions.inTransaction(() -> provisionInTransaction(key, command));
		}
		catch (CommitOutcomeUnknownException | StaleWriterException ex) {
			throw new WriterHaltException("Trading state may differ from the database", ex);
		}
	}

	private AccountResult provisionInTransaction(CommandKey key, ProvisionAccount command) {
		Market market = store.lockMarket();
		if (market.writerEpoch() != writerEpoch) {
			throw new StaleWriterException();
		}
		Optional<TradingStore.StoredResult<AccountResult>> prior = store.findResult(simulationId, key,
				AccountResult.class);
		if (prior.isPresent()) {
			return replay(key, prior.get());
		}
		long sequence = store.nextCommandSequence(simulationId);
		AccountResult.Status refusal = null;
		if (!"SETUP".equals(market.state())) {
			refusal = AccountResult.Status.NOT_IN_SETUP;
		}
		else if (store.hasAccount(simulationId, command.ownerUserId())) {
			refusal = AccountResult.Status.ACCOUNT_EXISTS;
		}
		else if (!command.holdings().stream().allMatch((holding) -> books.containsKey(holding.instrumentId()))) {
			refusal = AccountResult.Status.UNKNOWN_INSTRUMENT;
		}
		if (refusal != null) {
			store.insertAudit(simulationId, command.actorUserId(), "ACCOUNT_PROVISION", "NOT_ALLOWED",
					key.idempotencyKey(), sequence);
			int status = refusal == AccountResult.Status.UNKNOWN_INSTRUMENT ? 422 : 409;
			return remember(key, status, new AccountResult(refusal, null, command.username(), command.cashCents(),
					command.holdings(), sequence));
		}
		store.insertTraderAccount(simulationId, command.accountId(), command.ownerUserId(), command.username());
		store.insertGenesis(simulationId, command.accountId(), "TRADER", sequence, command.cashCents(),
				command.holdings());
		store.insertAudit(simulationId, command.actorUserId(), "ACCOUNT_PROVISION", "APPLIED", key.idempotencyKey(),
				sequence);
		return remember(key, 201, new AccountResult(AccountResult.Status.CREATED, command.accountId(),
				command.username(), command.cashCents(), command.holdings(), sequence));
	}

	private BotsResult processBots(CommandKey key, ProvisionBots command) {
		try {
			ensureMarket();
			return transactions.inTransaction(() -> botsInTransaction(key, command));
		}
		catch (CommitOutcomeUnknownException | StaleWriterException ex) {
			throw new WriterHaltException("Trading state may differ from the database", ex);
		}
	}

	/**
	 * Each bot gets its own genesis batch and token. Tokens are handed over before commit; if the transaction then
	 * fails, the stray tokens belong to accounts that never existed, so they authenticate nothing.
	 */
	private BotsResult botsInTransaction(CommandKey key, ProvisionBots command) {
		Market market = store.lockMarket();
		if (market.writerEpoch() != writerEpoch) {
			throw new StaleWriterException();
		}
		Optional<TradingStore.StoredResult<BotsResult>> prior = store.findResult(simulationId, key, BotsResult.class);
		if (prior.isPresent()) {
			return replay(key, prior.get());
		}
		long sequence = store.nextCommandSequence(simulationId);
		int population = store.botCount(simulationId);
		BotsResult.Status refusal = null;
		if (!"SETUP".equals(market.state())) {
			refusal = BotsResult.Status.NOT_IN_SETUP;
		}
		else if (population + command.count() > ProvisionBots.MAX_POPULATION) {
			refusal = BotsResult.Status.POPULATION_LIMIT;
		}
		else if (!command.holdings().stream().allMatch((holding) -> books.containsKey(holding.instrumentId()))) {
			refusal = BotsResult.Status.UNKNOWN_INSTRUMENT;
		}
		if (refusal != null) {
			store.insertAudit(simulationId, command.actorUserId(), "BOT_PROVISION", "NOT_ALLOWED", key.idempotencyKey(),
					sequence);
			int status = refusal == BotsResult.Status.UNKNOWN_INSTRUMENT ? 422 : 409;
			return remember(key, status, new BotsResult(refusal, 0, null, null, population, sequence));
		}
		for (int number = population + 1; number <= population + command.count(); number++) {
			UUID account = UUID.randomUUID();
			store.insertBotAccount(simulationId, account, ProvisionBots.name(number));
			store.insertGenesis(simulationId, account, "BOT", sequence, command.cashCents(), command.holdings());
			store.insertApiToken(UUID.randomUUID(), account, botCredentials.issue(account));
		}
		store.insertAudit(simulationId, command.actorUserId(), "BOT_PROVISION", "APPLIED", key.idempotencyKey(),
				sequence);
		return remember(key, 201, new BotsResult(BotsResult.Status.CREATED, command.count(),
				ProvisionBots.name(population + 1), ProvisionBots.name(population + command.count()),
				population + command.count(), sequence));
	}

	private BotRunResult processStartRun(CommandKey key, StartBotRun command) {
		if (simulationId == null) {
			return new BotRunResult(BotRunResult.Status.NO_MARKET, null, 0, 0);
		}
		try {
			return transactions.inTransaction(() -> startRunInTransaction(key, command));
		}
		catch (CommitOutcomeUnknownException | StaleWriterException ex) {
			throw new WriterHaltException("Trading state may differ from the database", ex);
		}
	}

	/**
	 * A run whose time is up still reads as running until something replaces it; starting the next one records it as
	 * stopped at its end. Bots are assigned in creation order, liquidity providers first.
	 */
	private BotRunResult startRunInTransaction(CommandKey key, StartBotRun command) {
		Market market = store.lockMarket();
		if (market.writerEpoch() != writerEpoch) {
			throw new StaleWriterException();
		}
		Optional<TradingStore.StoredResult<BotRunResult>> prior = store.findResult(simulationId, key,
				BotRunResult.class);
		if (prior.isPresent()) {
			return replay(key, prior.get());
		}
		long sequence = store.nextCommandSequence(simulationId);
		Instant now = clock.instant();
		BotRunResult.Status refusal = null;
		Optional<TradingStore.RunningBotRun> running = store.runningBotRun(simulationId);
		List<UUID> bots = store.botAccountIds(simulationId);
		List<UUID> instruments = command.instrumentIds().isEmpty()
				? store.instruments(simulationId).stream().map(TradingStore.Instrument::id).toList()
				: command.instrumentIds();
		if (!"OPEN".equals(market.state()) && !"HALTED".equals(market.state())) {
			refusal = BotRunResult.Status.NOT_OPEN;
		}
		else if (running.isPresent() && (running.get().endsAt() == null || running.get().endsAt().isAfter(now))) {
			refusal = BotRunResult.Status.RUN_ACTIVE;
		}
		else if (command.bots() > bots.size()) {
			refusal = BotRunResult.Status.NOT_ENOUGH_BOTS;
		}
		else if (instruments.isEmpty() || !instruments.stream().allMatch(books::containsKey)) {
			refusal = BotRunResult.Status.UNKNOWN_INSTRUMENT;
		}
		if (refusal != null) {
			store.insertAudit(simulationId, command.actorUserId(), "BOT_RUN_START", "NOT_ALLOWED", key.idempotencyKey(),
					sequence);
			BotRunResult refused = new BotRunResult(refusal, null, 0, sequence);
			return remember(key, refused.httpStatus(), refused);
		}
		running.ifPresent((expired) -> store.requestBotRunStop(expired.id(), expired.endsAt()));
		store.insertBotRun(new TradingStore.NewBotRun(command.runId(), simulationId, command.seed(),
				command.commandsPerSecondHundredths(), command.maxOrderQuantity(), command.maxPosition(),
				command.actorUserId(), now, sequence, command.duration() == null ? null : now.plus(command.duration())));
		for (UUID instrument : instruments) {
			store.insertBotRunInstrument(command.runId(), simulationId, instrument);
		}
		int next = 0;
		for (int i = 0; i < command.marketMakers(); i++) {
			store.insertBotAssignment(command.runId(), bots.get(next++), "MARKET_MAKER");
		}
		for (int i = 0; i < command.noise(); i++) {
			store.insertBotAssignment(command.runId(), bots.get(next++), "NOISE");
		}
		for (int i = 0; i < command.momentum(); i++) {
			store.insertBotAssignment(command.runId(), bots.get(next++), "MOMENTUM");
		}
		store.insertAudit(simulationId, command.actorUserId(), "BOT_RUN_START", "APPLIED", key.idempotencyKey(),
				sequence);
		BotRunResult started = new BotRunResult(BotRunResult.Status.STARTED, command.runId(), command.bots(), sequence);
		return remember(key, started.httpStatus(), started);
	}

	private BotRunResult processStopRun(CommandKey key, StopBotRun command) {
		if (simulationId == null) {
			return new BotRunResult(BotRunResult.Status.NO_MARKET, null, 0, 0);
		}
		try {
			return transactions.inTransaction(() -> stopRunInTransaction(key, command));
		}
		catch (CommitOutcomeUnknownException | StaleWriterException ex) {
			throw new WriterHaltException("Trading state may differ from the database", ex);
		}
	}

	private BotRunResult stopRunInTransaction(CommandKey key, StopBotRun command) {
		Market market = store.lockMarket();
		if (market.writerEpoch() != writerEpoch) {
			throw new StaleWriterException();
		}
		Optional<TradingStore.StoredResult<BotRunResult>> prior = store.findResult(simulationId, key,
				BotRunResult.class);
		if (prior.isPresent()) {
			return replay(key, prior.get());
		}
		long sequence = store.nextCommandSequence(simulationId);
		Optional<String> state = store.botRunState(simulationId, command.runId());
		BotRunResult.Status status = state.isEmpty() ? BotRunResult.Status.NOT_FOUND
				: "RUNNING".equals(state.get()) ? BotRunResult.Status.STOPPED : BotRunResult.Status.NOT_RUNNING;
		if (status == BotRunResult.Status.STOPPED) {
			store.requestBotRunStop(command.runId(), clock.instant());
		}
		store.insertAudit(simulationId, command.actorUserId(), "BOT_RUN_STOP",
				status == BotRunResult.Status.STOPPED ? "APPLIED" : "NOT_ALLOWED", key.idempotencyKey(), sequence);
		BotRunResult result = new BotRunResult(status, command.runId(), 0, sequence);
		return remember(key, result.httpStatus(), result);
	}

	private MarketTransition processControl(CommandKey key, MarketControl command) {
		if (simulationId == null) {
			return MarketTransition.notSetUp();
		}
		try {
			return transactions.inTransaction(() -> controlInTransaction(key, command));
		}
		catch (CommitOutcomeUnknownException | StaleWriterException ex) {
			throw new WriterHaltException("Trading state may differ from the database", ex);
		}
	}

	private MarketTransition controlInTransaction(CommandKey key, MarketControl command) {
		Market market = store.lockMarket();
		if (market.writerEpoch() != writerEpoch) {
			throw new StaleWriterException();
		}
		Optional<TradingStore.StoredResult<MarketTransition>> prior = store.findResult(simulationId, key,
				MarketTransition.class);
		if (prior.isPresent()) {
			return replay(key, prior.get());
		}
		long sequence = store.nextCommandSequence(simulationId);
		MarketControl.Action action = command.action();
		if (!action.from().equals(market.state())) {
			store.insertAudit(simulationId, command.actorUserId(), action.auditName(), "NOT_ALLOWED",
					key.idempotencyKey(), sequence);
			return remember(key, 409,
					new MarketTransition(MarketTransition.Status.NOT_ALLOWED, market.state(), sequence));
		}
		store.updateMarketState(simulationId, action.to());
		// Every instrument's public state carries the venue state, so each one gets a snapshot at this sequence.
		Market changed = market.withState(action.to());
		for (TradingStore.Instrument instrument : store.instruments(simulationId)) {
			snapshot(changed, instrument.id(), books.get(instrument.id()), BookDelta.none(), sequence);
		}
		store.insertAudit(simulationId, command.actorUserId(), action.auditName(), "APPLIED", key.idempotencyKey(),
				sequence);
		return remember(key, 200, new MarketTransition(MarketTransition.Status.APPLIED, action.to(), sequence));
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
		snapshot(market, order.instrumentId(), books.get(order.instrumentId()), BookDelta.cancel(resting), sequence);
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
		if (!plan.fills().isEmpty() || plan.rests()) {
			snapshot(market, command.instrumentId(), book, BookDelta.of(plan), sequence);
		}
		return new Outcome(remember(key, 201, result), plan);
	}

	/**
	 * The instrument's public state once this command commits, written in the same transaction. The depth comes from
	 * the book and the command's own changes; the book itself changes only after the commit.
	 */
	private void snapshot(Market market, UUID instrumentId, OrderBook book, BookDelta delta, long sequence) {
		store.insertMarketSnapshot(new TradingStore.MarketView(simulationId, instrumentId, market,
				book.depthAfter(delta, Side.BUY, SNAPSHOT_LEVELS), book.depthAfter(delta, Side.SELL, SNAPSHOT_LEVELS),
				sequence, clock.instant()));
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
		long sequence = switch (result) {
			case PlacementResult placement -> placement.commandSequence();
			case CancelResult cancel -> cancel.commandSequence();
			case MarketTransition transition -> transition.commandSequence();
			case InstrumentResult instrument -> instrument.commandSequence();
			case AccountResult account -> account.commandSequence();
			case BotsResult bots -> bots.commandSequence();
			case BotRunResult run -> run.commandSequence();
			default -> throw new IllegalArgumentException("Not a command result: " + result);
		};
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
