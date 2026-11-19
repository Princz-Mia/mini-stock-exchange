package com.mia_princz.mse.execution.trading.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import com.mia_princz.mse.execution.trading.application.CommandWriter.WriterHaltException;
import com.mia_princz.mse.execution.trading.application.PlacementResult.OrderState;
import com.mia_princz.mse.execution.trading.application.TradingStore.Market;
import com.mia_princz.mse.execution.trading.application.TradingStore.NewOrder;
import com.mia_princz.mse.execution.trading.application.TransactionRunner.CommitOutcomeUnknownException;
import com.mia_princz.mse.execution.trading.domain.BookDelta;
import com.mia_princz.mse.execution.trading.domain.BookOrder;
import com.mia_princz.mse.execution.trading.domain.DepthLevel;
import com.mia_princz.mse.execution.trading.domain.DisplayName;
import com.mia_princz.mse.execution.trading.domain.Fill;
import com.mia_princz.mse.execution.trading.domain.MatchPlan;
import com.mia_princz.mse.execution.trading.domain.OrderBook;
import com.mia_princz.mse.execution.trading.domain.RestingOrders;
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

	/** Trades in a public market snapshot's tape. */
	static final int TAPE_TRADES = 50;

	/** Levels per side in a public market snapshot. */
	static final int SNAPSHOT_LEVELS = 20;

	private final TradingStore store;

	private final TransactionRunner transactions;

	private final CommandWriter writer;

	private final Clock clock;

	private final BotCredentials botCredentials;

	private final AccountChanges accountChanges;

	private final WriterTimings timings;

	private final LongSupplier nanoTime;

	private final Map<UUID, OrderBook> books = new HashMap<>();

	/** The current command's writes, flushed just before its answer is stored; a new set for each transaction. */
	private WriteSet writes = new WriteSet();

	/** Each account's resting orders across all the books, kept by the books themselves. */
	private final RestingOrders resting;

	/** Every instrument as its public snapshot needs it, with its last trade sequence; changed only after commit. */
	private final Map<UUID, TradingStore.Instrument> catalogue = new HashMap<>();

	/** Each instrument's newest trades for its snapshots, kept like the books: changed only after a commit. */
	private final Map<UUID, Tape> tapes = new HashMap<>();

	private UUID simulationId;

	private long writerEpoch;

	/**
	 * Set once startup has finished: the books rebuilt and reconciled, or no market to rebuild. The web server takes
	 * requests before that, and a command run on an engine that hasn't started would see no epoch, no books and no
	 * market, so until then none is admitted.
	 */
	private volatile boolean started;

	public TradingEngine(TradingStore store, TransactionRunner transactions, CommandWriter writer, Clock clock,
			BotCredentials botCredentials) {
		this(store, transactions, writer, clock, botCredentials, AccountChanges.NONE);
	}

	public TradingEngine(TradingStore store, TransactionRunner transactions, CommandWriter writer, Clock clock,
			BotCredentials botCredentials, AccountChanges accountChanges) {
		this(store, transactions, writer, clock, botCredentials, accountChanges, WriterTimings.NONE, System::nanoTime);
	}

	/** {@code nanoTime} is the clock the timings are taken with, replaceable in tests. */
	public TradingEngine(TradingStore store, TransactionRunner transactions, CommandWriter writer, Clock clock,
			BotCredentials botCredentials, AccountChanges accountChanges, WriterTimings timings,
			LongSupplier nanoTime) {
		this(store, transactions, writer, clock, botCredentials, accountChanges, timings, nanoTime,
				new RestingOrders());
	}

	/** {@code resting} is the books' shared count of each account's resting orders, replaceable in tests. */
	public TradingEngine(TradingStore store, TransactionRunner transactions, CommandWriter writer, Clock clock,
			BotCredentials botCredentials, AccountChanges accountChanges, WriterTimings timings, LongSupplier nanoTime,
			RestingOrders resting) {
		this.resting = resting;
		this.store = store;
		this.transactions = transactions;
		this.writer = writer;
		this.clock = clock;
		this.botCredentials = botCredentials;
		this.accountChanges = accountChanges;
		this.timings = timings;
		this.nanoTime = nanoTime;
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
			Map<UUID, TradingStore.Instrument> rebuiltCatalogue = new HashMap<>();
			Map<UUID, Tape> rebuiltTapes = new HashMap<>();
			List<String> problems = new ArrayList<>();
			// A restart in place rebuilds every book, so the count starts again from nothing.
			resting.clear();
			for (TradingStore.Instrument instrument : store.instruments(simulation)) {
				OrderBook book = new OrderBook(market.maxFillsPerCommand(), resting);
				store.activeOrders(instrument.id()).forEach(book::restore);
				if (book.isCrossed()) {
					problems.add("Book for " + instrument.symbol() + " is crossed after rebuilding");
				}
				rebuilt.put(instrument.id(), book);
				rebuiltCatalogue.put(instrument.id(), instrument);
				rebuiltTapes.put(instrument.id(), new Tape(TAPE_TRADES, store.recentTrades(instrument.id(), TAPE_TRADES)));
			}
			problems.addAll(store.reconciliationProblems(simulation));
			Map<UUID, Integer> counted = store.restingOrdersByAccount(simulation);
			if (!counted.equals(resting.counts())) {
				problems.add("Resting orders per account differ between the books and the database");
			}
			if (!problems.isEmpty()) {
				throw new ReconciliationException(problems);
			}
			simulationId = simulation;
			writerEpoch = epoch;
			books.putAll(rebuilt);
			catalogue.putAll(rebuiltCatalogue);
			tapes.putAll(rebuiltTapes);
			return null;
		});
		started = true;
	}

	/** Starts with no market to rebuild: commands are admitted, and the first setup command creates the market. */
	public void startEmpty() {
		started = true;
	}

	/** Places an order. A repeat of the same key and request returns the first committed result unchanged. */
	public CompletableFuture<PlacementResult> place(CommandKey key, PlaceOrder command) {
		return submit("place", () -> process(key, command));
	}

	/** Cancels a resting order; allowed while the market is open or halted. */
	public CompletableFuture<CancelResult> cancel(CommandKey key, CancelOrder command) {
		return submit("cancel", () -> processCancel(key, command));
	}

	/**
	 * Opens, halts or resumes the market. The new state takes effect at the command's sequence: every later command
	 * reads it from the locked market row. Books are untouched, so resting orders keep their priority.
	 */
	public CompletableFuture<MarketTransition> control(CommandKey key, MarketControl command) {
		return submit(() -> processControl(key, command));
	}

	/**
	 * Adds an instrument while the market is in SETUP. The first one creates the market. The new instrument's empty
	 * book joins the engine only after the command has committed.
	 */
	public CompletableFuture<InstrumentResult> addInstrument(CommandKey key, AddInstrument command) {
		return submit(() -> processAddInstrument(key, command));
	}

	/**
	 * Sets an instrument's reference price and display name while the market is in SETUP, and publishes its snapshot
	 * with them. Books hold neither, so nothing in memory changes.
	 */
	public CompletableFuture<InstrumentEdit> editInstrument(CommandKey key, EditInstrument command) {
		return submit(() -> processEditInstrument(key, command));
	}

	/**
	 * Deletes an instrument nothing uses while the market is in SETUP, and publishes its last snapshot, which says it
	 * was removed. Its book, symbol and tape leave the engine only after the command has committed.
	 */
	public CompletableFuture<InstrumentDeletion> deleteInstrument(CommandKey key, DeleteInstrument command) {
		return submit(() -> processDeleteInstrument(key, command));
	}

	/** Gives a trader their account and starting endowment while the market is in SETUP. */
	public CompletableFuture<AccountResult> provisionAccount(CommandKey key, ProvisionAccount command) {
		return submit(() -> processProvision(key, command));
	}

	/** Adds a trader login, and optionally its account and endowment, while the market is in SETUP. */
	public CompletableFuture<TraderResult> addTrader(CommandKey key, AddTrader command) {
		return submit(() -> processAddTrader(key, command));
	}

	/** Creates funded bot accounts, each with its own API token, while the market is in SETUP. */
	public CompletableFuture<BotsResult> provisionBots(CommandKey key, ProvisionBots command) {
		return submit(() -> processBots(key, command));
	}

	/** Revokes a bot's tokens, in any market state; the bot's next request is refused. */
	public CompletableFuture<TokenRevocation> revokeBotToken(CommandKey key, RevokeBotToken command) {
		return submit(() -> processRevoke(key, command));
	}

	/** Starts a bot run while the market is open or halted; one run runs at a time. */
	public CompletableFuture<BotRunResult> startBotRun(CommandKey key, StartBotRun command) {
		return submit(() -> processStartRun(key, command));
	}

	/** Asks a running bot run to stop. */
	public CompletableFuture<BotRunResult> stopBotRun(CommandKey key, StopBotRun command) {
		return submit(() -> processStopRun(key, command));
	}

	/** A snapshot of one side of a book, read on the writer thread so it never sees a half-applied plan. */
	public CompletableFuture<List<DepthLevel>> depth(UUID instrumentId, Side side) {
		return submit(() -> books.get(instrumentId).depth(side, Integer.MAX_VALUE));
	}

	/** {@code trades} are the command's new trades, oldest first, for the tape once it commits. */
	/** {@code fresh} is false for a repeated key's stored answer, which changed nothing. */
	private record Outcome(PlacementResult result, MatchPlan plan, List<TapeTrade> trades, boolean fresh) {

		Outcome(PlacementResult result, MatchPlan plan) {
			this(result, plan, List.of(), true);
		}

		Outcome(PlacementResult result, MatchPlan plan, List<TapeTrade> trades) {
			this(result, plan, trades, true);
		}

	}

	private PlacementResult process(CommandKey key, PlaceOrder command) {
		OrderBook book = books.get(command.instrumentId());
		if (book == null) {
			throw new UnknownInstrumentException(command.instrumentId());
		}
		Outcome outcome;
		try {
			outcome = timed("place", () -> placeInTransaction(key, command, book));
		}
		catch (CommitOutcomeUnknownException | StaleWriterException | TradingStore.OutOfStepException ex) {
			throw new WriterHaltException("Trading state may differ from the database", ex);
		}
		if (outcome.plan() != null) {
			long applying = nanoTime.getAsLong();
			try {
				book.apply(outcome.plan());
				tapes.get(command.instrumentId()).add(outcome.trades());
				if (!outcome.trades().isEmpty()) {
					long last = outcome.trades().getLast().tradeSequence();
					catalogue.computeIfPresent(command.instrumentId(), (id, known) -> known.withLastTradeSequence(last));
				}
			}
			catch (RuntimeException | Error ex) {
				// Committed but not applied: the book no longer matches the database, whatever was thrown.
				throw new WriterHaltException("The book could not apply a committed plan", ex);
			}
			timings.record(WriterTimings.Phase.APPLY, "place", nanoTime.getAsLong() - applying);
		}
		if (outcome.fresh()) {
			// The order's own account, a rejection included, and every resting order's account it traded against.
			Set<UUID> changed = new HashSet<>();
			changed.add(command.accountId());
			if (outcome.plan() != null) {
				outcome.plan().fills().forEach((fill) -> changed.add(fill.makerAccountId()));
			}
			accountChanges.changed(changed, outcome.result().commandSequence());
		}
		return outcome.result();
	}

	private record CancelOutcome(CancelResult result, UUID instrumentId) {
	}

	private CancelResult processCancel(CommandKey key, CancelOrder command) {
		CancelOutcome outcome;
		try {
			outcome = timed("cancel", () -> cancelInTransaction(key, command));
		}
		catch (CommitOutcomeUnknownException | StaleWriterException | BookOutOfStepException
				| TradingStore.OutOfStepException ex) {
			throw new WriterHaltException("Trading state may differ from the database", ex);
		}
		long applying = nanoTime.getAsLong();
		boolean removed = true;
		if (outcome.instrumentId() != null && outcome.result().status() == CancelResult.Status.CANCELED) {
			try {
				removed = books.get(outcome.instrumentId()).cancel(command.orderId()).isPresent();
			}
			catch (RuntimeException | Error ex) {
				throw new WriterHaltException("The book could not apply a committed cancel", ex);
			}
		}
		if (!removed) {
			throw new WriterHaltException("A committed cancel found no order in the book",
					new BookOutOfStepException(command.orderId()));
		}
		timings.record(WriterTimings.Phase.APPLY, "cancel", nanoTime.getAsLong() - applying);
		if (outcome.instrumentId() != null && outcome.result().status() == CancelResult.Status.CANCELED) {
			accountChanges.changed(Set.of(command.accountId()), outcome.result().commandSequence());
		}
		return outcome.result();
	}

	/** {@code instrument} is the row as written when {@code fresh}, otherwise null. */
	private record AddedInstrument(InstrumentResult result, boolean fresh, int maxFillsPerCommand,
			TradingStore.Instrument instrument) {
	}

	private InstrumentResult processAddInstrument(CommandKey key, AddInstrument command) {
		AddedInstrument added;
		try {
			ensureMarket();
			added = timed("other", () -> addInstrumentInTransaction(key, command));
		}
		catch (CommitOutcomeUnknownException | StaleWriterException | TradingStore.OutOfStepException ex) {
			throw new WriterHaltException("Trading state may differ from the database", ex);
		}
		if (added.fresh()) {
			// Never replace a book that exists: it may hold resting orders.
			books.putIfAbsent(command.instrumentId(), new OrderBook(added.maxFillsPerCommand(), resting));
			catalogue.putIfAbsent(command.instrumentId(), added.instrument());
			tapes.putIfAbsent(command.instrumentId(), new Tape(TAPE_TRADES, List.of()));
		}
		return added.result();
	}

	/** Refused, not queued, until startup has finished, so the caller can retry the same key. */
	private <T> CompletableFuture<T> submit(Callable<T> command) {
		return submit("other", command);
	}

	private <T> CompletableFuture<T> submit(String kind, Callable<T> command) {
		if (!started) {
			throw new CommandWriter.WriterStoppedException("Trading has not started");
		}
		long admitted = nanoTime.getAsLong();
		return writer.submit(() -> {
			timings.record(WriterTimings.Phase.QUEUE_WAIT, kind, nanoTime.getAsLong() - admitted);
			return command.call();
		});
	}

	/**
	 * A command's transaction, timed: opening it (a pooled connection included), its statements, and its commit, all
	 * recorded once it has committed. A transaction that fails records none of them.
	 */
	private <T> T timed(String kind, Supplier<T> work) {
		long opening = nanoTime.getAsLong();
		long[] marks = new long[2];
		T result = transactions.inTransaction(() -> {
			marks[0] = nanoTime.getAsLong();
			writes = new WriteSet();
			T value = work.get();
			if (!writes.isEmpty()) {
				// Rows collected after the answer was stored, or by a path that stored none, would be lost silently.
				throw new IllegalStateException("A command's writes were not flushed before its commit");
			}
			marks[1] = nanoTime.getAsLong();
			return value;
		});
		long committed = nanoTime.getAsLong();
		timings.record(WriterTimings.Phase.BEGIN, kind, marks[0] - opening);
		timings.record(WriterTimings.Phase.WORK, kind, marks[1] - marks[0]);
		timings.record(WriterTimings.Phase.COMMIT, kind, committed - marks[1]);
		return result;
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
		TradingStore.Opening<InstrumentResult> opening = open(key, InstrumentResult.class);
		Market market = opening.market();
		if (opening.prior().isPresent()) {
			return new AddedInstrument(replay(key, opening.prior().get()), false, market.maxFillsPerCommand(), null);
		}
		long sequence = opening.sequence();
		InstrumentResult.Status refusal = !"SETUP".equals(market.state()) ? InstrumentResult.Status.NOT_IN_SETUP
				: store.symbolTaken(simulationId, command.symbol()) ? InstrumentResult.Status.SYMBOL_TAKEN : null;
		if (refusal != null) {
			store.insertAudit(simulationId, command.actorUserId(), "INSTRUMENT_ADD", "NOT_ALLOWED", key.idempotencyKey(),
					sequence);
			return new AddedInstrument(remember(key, 409, new InstrumentResult(refusal, null, command.symbol(),
					name(command.displayName()), command.referencePrice().cents(), sequence)), false,
					market.maxFillsPerCommand(), null);
		}
		TradingStore.Instrument inserted = store.insertInstrument(simulationId, command.instrumentId(),
				command.symbol(), name(command.displayName()), command.referencePrice().cents());
		// The catalogue changed, so the new instrument's public state is published at this sequence.
		snapshot(market, inserted, new OrderBook(market.maxFillsPerCommand()), BookDelta.none(), sequence, List.of());
		store.insertAudit(simulationId, command.actorUserId(), "INSTRUMENT_ADD", "APPLIED", key.idempotencyKey(),
				sequence);
		return new AddedInstrument(remember(key, 201, new InstrumentResult(InstrumentResult.Status.CREATED,
				command.instrumentId(), command.symbol(), name(command.displayName()), command.referencePrice().cents(),
				sequence)), true,
				market.maxFillsPerCommand(), inserted);
	}

	/** {@code instrument} is the row as written when the edit applied, otherwise null. */
	private record EditedInstrument(InstrumentEdit result, TradingStore.Instrument instrument) {
	}

	/** Without a market there is no instrument to edit, and nothing to commit the answer to. */
	private InstrumentEdit processEditInstrument(CommandKey key, EditInstrument command) {
		if (simulationId == null) {
			return new InstrumentEdit(InstrumentEdit.Status.UNKNOWN_INSTRUMENT, command.instrumentId(), null,
					name(command.displayName()), command.referencePrice().cents(), 0);
		}
		EditedInstrument edited;
		try {
			edited = timed("other", () -> editInstrumentInTransaction(key, command));
		}
		catch (CommitOutcomeUnknownException | StaleWriterException | TradingStore.OutOfStepException ex) {
			throw new WriterHaltException("Trading state may differ from the database", ex);
		}
		if (edited.instrument() != null) {
			catalogue.put(command.instrumentId(), edited.instrument());
		}
		return edited.result();
	}

	/** An unknown instrument is answered as such in any state; a known one is edited only in SETUP. */
	private EditedInstrument editInstrumentInTransaction(CommandKey key, EditInstrument command) {
		TradingStore.Opening<InstrumentEdit> opening = open(key, InstrumentEdit.class);
		Market market = opening.market();
		if (opening.prior().isPresent()) {
			return new EditedInstrument(replay(key, opening.prior().get()), null);
		}
		long sequence = opening.sequence();
		long cents = command.referencePrice().cents();
		String displayName = name(command.displayName());
		Optional<String> symbol = store.instrumentSymbol(simulationId, command.instrumentId());
		InstrumentEdit.Status refusal = symbol.isEmpty() ? InstrumentEdit.Status.UNKNOWN_INSTRUMENT
				: !"SETUP".equals(market.state()) ? InstrumentEdit.Status.NOT_IN_SETUP : null;
		if (refusal != null) {
			store.insertAudit(simulationId, command.actorUserId(), "INSTRUMENT_EDIT", "NOT_ALLOWED",
					key.idempotencyKey(), sequence);
			return new EditedInstrument(remember(key, refusal == InstrumentEdit.Status.UNKNOWN_INSTRUMENT ? 404 : 409,
					new InstrumentEdit(refusal, command.instrumentId(), symbol.orElse(null), displayName, cents,
							sequence)), null);
		}
		TradingStore.Instrument updated = store.updateInstrument(simulationId, command.instrumentId(), cents,
				displayName);
		OrderBook book = books.get(command.instrumentId());
		if (book == null) {
			throw new IllegalStateException("No book for instrument " + command.instrumentId());
		}
		// The catalogue changed, so the instrument's public state is published again at this sequence.
		snapshot(market, updated, book, BookDelta.none(), sequence, List.of());
		store.insertAudit(simulationId, command.actorUserId(), "INSTRUMENT_EDIT", "APPLIED", key.idempotencyKey(),
				sequence);
		return new EditedInstrument(remember(key, 200, new InstrumentEdit(InstrumentEdit.Status.UPDATED,
				command.instrumentId(), symbol.get(), displayName, cents, sequence)), updated);
	}

	private static String name(DisplayName displayName) {
		return displayName == null ? null : displayName.value();
	}

	/** Without a market there is no instrument to delete, and nothing to commit the answer to. */
	private InstrumentDeletion processDeleteInstrument(CommandKey key, DeleteInstrument command) {
		if (simulationId == null) {
			return new InstrumentDeletion(InstrumentDeletion.Status.UNKNOWN_INSTRUMENT, command.instrumentId(), null,
					null, 0);
		}
		InstrumentDeletion deletion;
		try {
			deletion = timed("other", () -> deleteInstrumentInTransaction(key, command));
		}
		catch (CommitOutcomeUnknownException | StaleWriterException | TradingStore.OutOfStepException ex) {
			throw new WriterHaltException("Trading state may differ from the database", ex);
		}
		if (deletion.status() == InstrumentDeletion.Status.DELETED) {
			// On this thread, so the next order for it finds no book and is refused before any transaction. Removing is
			// idempotent, so a replayed answer changes nothing.
			books.remove(command.instrumentId());
			catalogue.remove(command.instrumentId());
			tapes.remove(command.instrumentId());
		}
		return deletion;
	}

	/**
	 * An unknown instrument is answered as such in any state; a known one is deleted only in SETUP and only while
	 * nothing refers to it.
	 */
	private InstrumentDeletion deleteInstrumentInTransaction(CommandKey key, DeleteInstrument command) {
		TradingStore.Opening<InstrumentDeletion> opening = open(key, InstrumentDeletion.class);
		Market market = opening.market();
		if (opening.prior().isPresent()) {
			return replay(key, opening.prior().get());
		}
		long sequence = opening.sequence();
		Optional<String> symbol = store.instrumentSymbol(simulationId, command.instrumentId());
		InstrumentDeletion.Status refusal = symbol.isEmpty() ? InstrumentDeletion.Status.UNKNOWN_INSTRUMENT
				: !"SETUP".equals(market.state()) ? InstrumentDeletion.Status.NOT_IN_SETUP : null;
		InstrumentDeletion.Usage usage = null;
		if (refusal == null) {
			TradingStore.InstrumentUsage found = store.instrumentUsage(simulationId, command.instrumentId(),
					InstrumentDeletion.HOLDERS_NAMED);
			if (found.inUse()) {
				refusal = InstrumentDeletion.Status.IN_USE;
				usage = new InstrumentDeletion.Usage(found.holders(), found.holderCount(), found.orderCount());
			}
		}
		if (refusal != null) {
			store.insertAudit(simulationId, command.actorUserId(), "INSTRUMENT_DELETE", "NOT_ALLOWED",
					key.idempotencyKey(), sequence);
			return remember(key, refusal == InstrumentDeletion.Status.UNKNOWN_INSTRUMENT ? 404 : 409,
					new InstrumentDeletion(refusal, command.instrumentId(), symbol.orElse(null), usage, sequence));
		}
		// Nothing has traded or rests in SETUP, so the last public state is an empty market.
		store.insertRemovedSnapshot(new TradingStore.MarketView(simulationId, known(command.instrumentId()), market,
				List.of(), List.of(), List.of(), sequence, clock.instant()));
		store.deleteInstrument(simulationId, command.instrumentId());
		store.insertAudit(simulationId, command.actorUserId(), "INSTRUMENT_DELETE", "APPLIED", key.idempotencyKey(),
				sequence);
		return remember(key, 200, new InstrumentDeletion(InstrumentDeletion.Status.DELETED, command.instrumentId(),
				symbol.get(), null, sequence));
	}

	private AccountResult processProvision(CommandKey key, ProvisionAccount command) {
		try {
			ensureMarket();
			return timed("other", () -> provisionInTransaction(key, command));
		}
		catch (CommitOutcomeUnknownException | StaleWriterException | TradingStore.OutOfStepException ex) {
			throw new WriterHaltException("Trading state may differ from the database", ex);
		}
	}

	private AccountResult provisionInTransaction(CommandKey key, ProvisionAccount command) {
		TradingStore.Opening<AccountResult> opening = open(key, AccountResult.class);
		Market market = opening.market();
		if (opening.prior().isPresent()) {
			return replay(key, opening.prior().get());
		}
		long sequence = opening.sequence();
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

	private TraderResult processAddTrader(CommandKey key, AddTrader command) {
		try {
			ensureMarket();
			return timed("other", () -> addTraderInTransaction(key, command));
		}
		catch (CommitOutcomeUnknownException | StaleWriterException | TradingStore.OutOfStepException ex) {
			throw new WriterHaltException("Trading state may differ from the database", ex);
		}
	}

	/**
	 * The login and, when asked, its account and genesis batch, in one transaction: either the trader can sign in with
	 * what they were given, or nothing was added. A username is taken whatever its case.
	 */
	private TraderResult addTraderInTransaction(CommandKey key, AddTrader command) {
		TradingStore.Opening<TraderResult> opening = open(key, TraderResult.class);
		Market market = opening.market();
		if (opening.prior().isPresent()) {
			return replay(key, opening.prior().get());
		}
		long sequence = opening.sequence();
		Long cash = command.funded() ? command.cashCents() : null;
		TraderResult.Status refusal = null;
		if (!"SETUP".equals(market.state())) {
			refusal = TraderResult.Status.NOT_IN_SETUP;
		}
		else if (store.usernameTaken(command.username())) {
			refusal = TraderResult.Status.USERNAME_TAKEN;
		}
		else if (!command.holdings().stream().allMatch((holding) -> books.containsKey(holding.instrumentId()))) {
			refusal = TraderResult.Status.UNKNOWN_INSTRUMENT;
		}
		if (refusal != null) {
			store.insertAudit(simulationId, command.actorUserId(), "TRADER_ADD", "NOT_ALLOWED", key.idempotencyKey(),
					sequence);
			int status = refusal == TraderResult.Status.UNKNOWN_INSTRUMENT ? 422 : 409;
			return remember(key, status,
					new TraderResult(refusal, command.username(), null, cash, command.holdings(), sequence));
		}
		store.insertTraderUser(command.userId(), command.username(), command.passwordHash());
		if (command.funded()) {
			store.insertTraderAccount(simulationId, command.accountId(), command.userId(), command.username());
			store.insertGenesis(simulationId, command.accountId(), "TRADER", sequence, command.cashCents(),
					command.holdings());
		}
		store.insertAudit(simulationId, command.actorUserId(), "TRADER_ADD", "APPLIED", key.idempotencyKey(), sequence);
		return remember(key, 201, new TraderResult(TraderResult.Status.CREATED, command.username(),
				command.accountId(), cash, command.holdings(), sequence));
	}

	private BotsResult processBots(CommandKey key, ProvisionBots command) {
		try {
			ensureMarket();
			return timed("other", () -> botsInTransaction(key, command));
		}
		catch (CommitOutcomeUnknownException | StaleWriterException | TradingStore.OutOfStepException ex) {
			throw new WriterHaltException("Trading state may differ from the database", ex);
		}
	}

	/**
	 * The orders resting in the market, counted from the books. The books hold exactly the committed open orders
	 * (startup reconciles them, and they change only after a commit), so this matches a count in the database, which
	 * cost more with every order resting and ran for each one that rests.
	 */
	private int restingOrders() {
		int total = 0;
		for (OrderBook book : books.values()) {
			total += book.size();
		}
		return total;
	}

	/**
	 * Each bot gets its own genesis batch and token. Tokens are handed over before commit; if the transaction then
	 * fails, the stray tokens belong to accounts that never existed, so they authenticate nothing.
	 */
	private BotsResult botsInTransaction(CommandKey key, ProvisionBots command) {
		TradingStore.Opening<BotsResult> opening = open(key, BotsResult.class);
		Market market = opening.market();
		if (opening.prior().isPresent()) {
			return replay(key, opening.prior().get());
		}
		long sequence = opening.sequence();
		int population = store.botCount(simulationId);
		BotsResult.Status refusal = null;
		if (!"SETUP".equals(market.state())) {
			refusal = BotsResult.Status.NOT_IN_SETUP;
		}
		else if (!command.holdings().stream().allMatch((holding) -> books.containsKey(holding.instrumentId()))) {
			refusal = BotsResult.Status.UNKNOWN_INSTRUMENT;
		}
		if (refusal != null) {
			store.insertAudit(simulationId, command.actorUserId(), "BOT_PROVISION", "NOT_ALLOWED", key.idempotencyKey(),
					sequence);
			int status = refusal == BotsResult.Status.UNKNOWN_INSTRUMENT ? 422 : 409;
			return remember(key, status, new BotsResult(refusal, 0, null, null, population, command.strategy().name(), sequence));
		}
		for (int number = population + 1; number <= population + command.count(); number++) {
			UUID account = UUID.randomUUID();
			store.insertBotAccount(simulationId, account, ProvisionBots.name(number), command.strategy());
			store.insertGenesis(simulationId, account, "BOT", sequence, command.cashCents(), command.holdings());
			store.insertApiToken(UUID.randomUUID(), account, botCredentials.issue(account));
		}
		store.insertAudit(simulationId, command.actorUserId(), "BOT_PROVISION", "APPLIED", key.idempotencyKey(),
				sequence);
		return remember(key, 201, new BotsResult(BotsResult.Status.CREATED, command.count(),
				ProvisionBots.name(population + 1), ProvisionBots.name(population + command.count()),
				population + command.count(), command.strategy().name(), sequence));
	}

	private TokenRevocation processRevoke(CommandKey key, RevokeBotToken command) {
		if (simulationId == null) {
			return new TokenRevocation(TokenRevocation.Status.NOT_A_BOT, command.accountId(), 0, 0);
		}
		try {
			return timed("other", () -> revokeInTransaction(key, command));
		}
		catch (CommitOutcomeUnknownException | StaleWriterException | TradingStore.OutOfStepException ex) {
			throw new WriterHaltException("Trading state may differ from the database", ex);
		}
	}

	/**
	 * Revoking is identity, not trading: the bot's orders and balances are untouched, and a run it belongs to keeps
	 * running without it. Revoking again revokes nothing and says so.
	 */
	private TokenRevocation revokeInTransaction(CommandKey key, RevokeBotToken command) {
		TradingStore.Opening<TokenRevocation> opening = open(key, TokenRevocation.class);
		Market market = opening.market();
		if (opening.prior().isPresent()) {
			return replay(key, opening.prior().get());
		}
		long sequence = opening.sequence();
		if (!store.isBotAccount(simulationId, command.accountId())) {
			store.insertAudit(simulationId, command.actorUserId(), "BOT_TOKEN_REVOKE", "NOT_ALLOWED",
					key.idempotencyKey(), sequence);
			return remember(key, 404,
					new TokenRevocation(TokenRevocation.Status.NOT_A_BOT, command.accountId(), 0, sequence));
		}
		int revoked = store.revokeApiTokens(command.accountId(), clock.instant());
		store.insertAudit(simulationId, command.actorUserId(), "BOT_TOKEN_REVOKE", revoked > 0 ? "APPLIED" : "NOT_ALLOWED",
				key.idempotencyKey(), sequence);
		TokenRevocation.Status status = revoked > 0 ? TokenRevocation.Status.REVOKED
				: TokenRevocation.Status.ALREADY_REVOKED;
		return remember(key, 200, new TokenRevocation(status, command.accountId(), revoked, sequence));
	}

	private BotRunResult processStartRun(CommandKey key, StartBotRun command) {
		if (simulationId == null) {
			return new BotRunResult(BotRunResult.Status.NO_MARKET, null, 0, 0);
		}
		try {
			return timed("other", () -> startRunInTransaction(key, command));
		}
		catch (CommitOutcomeUnknownException | StaleWriterException | TradingStore.OutOfStepException ex) {
			throw new WriterHaltException("Trading state may differ from the database", ex);
		}
	}

	/**
	 * A run whose time is up still reads as running until something replaces it; starting the next one records it as
	 * stopped at its end. Each strategy is run by the oldest bots tagged with it, liquidity providers first; untagged
	 * bots are never assigned. A strategy with too few tagged bots refuses the run, naming the first one short.
	 */
	private BotRunResult startRunInTransaction(CommandKey key, StartBotRun command) {
		TradingStore.Opening<BotRunResult> opening = open(key, BotRunResult.class);
		Market market = opening.market();
		if (opening.prior().isPresent()) {
			return replay(key, opening.prior().get());
		}
		long sequence = opening.sequence();
		Instant now = clock.instant();
		BotRunResult.Status refusal = null;
		Optional<TradingStore.RunningBotRun> running = store.runningBotRun(simulationId);
		Map<BotStrategy, Integer> tagged = store.taggedBotCounts(simulationId);
		BotRunResult.Shortfall shortfall = Arrays.stream(BotStrategy.values())
			.filter((strategy) -> command.count(strategy) > tagged.getOrDefault(strategy, 0))
			.findFirst()
			.map((strategy) -> new BotRunResult.Shortfall(strategy.name(), command.count(strategy),
					tagged.getOrDefault(strategy, 0)))
			.orElse(null);
		List<UUID> instruments = command.instrumentIds().isEmpty()
				? store.instruments(simulationId).stream().map(TradingStore.Instrument::id).toList()
				: command.instrumentIds();
		if (!"OPEN".equals(market.state()) && !"HALTED".equals(market.state())) {
			refusal = BotRunResult.Status.NOT_OPEN;
		}
		else if (running.isPresent() && (running.get().endsAt() == null || running.get().endsAt().isAfter(now))) {
			refusal = BotRunResult.Status.RUN_ACTIVE;
		}
		else if (shortfall != null) {
			refusal = BotRunResult.Status.NOT_ENOUGH_BOTS;
		}
		else if (instruments.isEmpty() || !instruments.stream().allMatch(books::containsKey)) {
			refusal = BotRunResult.Status.UNKNOWN_INSTRUMENT;
		}
		if (refusal != null) {
			store.insertAudit(simulationId, command.actorUserId(), "BOT_RUN_START", "NOT_ALLOWED", key.idempotencyKey(),
					sequence);
			BotRunResult refused = new BotRunResult(refusal, null, 0, sequence,
					refusal == BotRunResult.Status.NOT_ENOUGH_BOTS ? shortfall : null);
			return remember(key, refused.httpStatus(), refused);
		}
		running.ifPresent((expired) -> store.requestBotRunStop(expired.id(), expired.endsAt()));
		store.insertBotRun(new TradingStore.NewBotRun(command.runId(), simulationId, command.seed(),
				command.commandsPerSecondHundredths(), command.maxOrderQuantity(), command.maxPosition(),
				command.actorUserId(), now, sequence, command.duration() == null ? null : now.plus(command.duration())));
		for (UUID instrument : instruments) {
			store.insertBotRunInstrument(command.runId(), simulationId, instrument);
		}
		for (BotStrategy strategy : BotStrategy.values()) {
			int wanted = command.count(strategy);
			if (wanted > 0 && store.assignOldestBots(command.runId(), simulationId, strategy, wanted) != wanted) {
				throw new IllegalStateException("assigned fewer " + strategy + " bots than counted");
			}
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
			return timed("other", () -> stopRunInTransaction(key, command));
		}
		catch (CommitOutcomeUnknownException | StaleWriterException | TradingStore.OutOfStepException ex) {
			throw new WriterHaltException("Trading state may differ from the database", ex);
		}
	}

	private BotRunResult stopRunInTransaction(CommandKey key, StopBotRun command) {
		TradingStore.Opening<BotRunResult> opening = open(key, BotRunResult.class);
		Market market = opening.market();
		if (opening.prior().isPresent()) {
			return replay(key, opening.prior().get());
		}
		long sequence = opening.sequence();
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
			return timed("other", () -> controlInTransaction(key, command));
		}
		catch (CommitOutcomeUnknownException | StaleWriterException | TradingStore.OutOfStepException ex) {
			throw new WriterHaltException("Trading state may differ from the database", ex);
		}
	}

	private MarketTransition controlInTransaction(CommandKey key, MarketControl command) {
		TradingStore.Opening<MarketTransition> opening = open(key, MarketTransition.class);
		Market market = opening.market();
		if (opening.prior().isPresent()) {
			return replay(key, opening.prior().get());
		}
		long sequence = opening.sequence();
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
		List<TradingStore.Instrument> listed = new ArrayList<>(catalogue.values());
		listed.sort(java.util.Comparator.comparing(TradingStore.Instrument::symbol));
		for (TradingStore.Instrument instrument : listed) {
			snapshot(changed, instrument, books.get(instrument.id()), BookDelta.none(), sequence, List.of());
		}
		store.insertAudit(simulationId, command.actorUserId(), action.auditName(), "APPLIED", key.idempotencyKey(),
				sequence);
		return remember(key, 200, new MarketTransition(MarketTransition.Status.APPLIED, action.to(), sequence));
	}

	private CancelOutcome cancelInTransaction(CommandKey key, CancelOrder command) {
		TradingStore.Opening<CancelResult> opening = open(key, CancelResult.class);
		Market market = opening.market();
		if (opening.prior().isPresent()) {
			return new CancelOutcome(replay(key, opening.prior().get()), null);
		}
		long sequence = opening.sequence();
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
			writes.batch(batch.cause(), null, batch.postings());
		}
		release.balanceChanges().forEach(writes::balance);
		writes.touch(List.of(command.accountId()));
		snapshot(market, order.instrumentId(), books.get(order.instrumentId()), BookDelta.cancel(resting), sequence);
		return new CancelOutcome(remember(key, 200,
				new CancelResult(command.orderId(), CancelResult.Status.CANCELED, order.working(), sequence)),
				order.instrumentId());
	}

	private Outcome placeInTransaction(CommandKey key, PlaceOrder command, OrderBook book) {
		TradingStore.Opening<PlacementResult> opening = open(key, PlacementResult.class);
		Market market = opening.market();
		if (opening.prior().isPresent()) {
			return new Outcome(replay(key, opening.prior().get()), null, List.of(), false);
		}
		long sequence = opening.sequence();
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
		if (plan.rests() && (resting.of(command.accountId()) >= market.maxOpenOrdersPerAccount()
				|| restingOrders() >= market.maxOpenOrdersTotal())) {
			return reject(key, command, RejectionCode.ORDER_LIMIT_EXCEEDED, sequence);
		}

		PlacementResult result = accepted(command, plan, sequence);
		store.insertOrder(new NewOrder(command.orderId(), simulationId, request, command.instrumentId(), result.state(),
				result.reason(), result.filled(), result.working(), result.canceled(), sequence));
		List<TapeTrade> trades = write(command, plan, sequence);
		if (!plan.fills().isEmpty() || plan.rests()) {
			snapshot(market, command.instrumentId(), book, BookDelta.of(plan), sequence, trades);
		}
		return new Outcome(remember(key, 201, result), plan, trades);
	}

	/**
	 * The instrument's public state once this command commits, written in the same transaction. The depth comes from
	 * the book and the command's own changes; the book itself changes only after the commit.
	 */
	private void snapshot(Market market, UUID instrumentId, OrderBook book, BookDelta delta, long sequence) {
		snapshot(market, instrumentId, book, delta, sequence, List.of());
	}

	/**
	 * The tape comes from memory, with the command's own trades first: re-reading it from the database for every
	 * command was the largest part of the writer's time. An instrument added by this command has no tape yet.
	 */
	private void snapshot(Market market, UUID instrumentId, OrderBook book, BookDelta delta, long sequence,
			List<TapeTrade> newTrades) {
		snapshot(market, known(instrumentId), book, delta, sequence, newTrades);
	}

	/** {@code instrument} as it is before this command; its last trade sequence moves to the command's newest. */
	private void snapshot(Market market, TradingStore.Instrument instrument, OrderBook book, BookDelta delta,
			long sequence, List<TapeTrade> newTrades) {
		Tape tape = tapes.get(instrument.id());
		TradingStore.Instrument after = newTrades.isEmpty() ? instrument
				: instrument.withLastTradeSequence(newTrades.getLast().tradeSequence());
		writes.snapshot(new TradingStore.MarketView(simulationId, after, market,
				book.depthAfter(delta, Side.BUY, SNAPSHOT_LEVELS), book.depthAfter(delta, Side.SELL, SNAPSHOT_LEVELS),
				tape == null ? List.copyOf(newTrades) : tape.with(newTrades), sequence, clock.instant()));
	}

	/** The instrument as the writer knows it; one that the database has but the writer does not is out of step. */
	private TradingStore.Instrument known(UUID instrumentId) {
		TradingStore.Instrument instrument = catalogue.get(instrumentId);
		if (instrument == null) {
			throw new TradingStore.OutOfStepException("The writer does not know instrument " + instrumentId);
		}
		return instrument;
	}

	/** Writes the order's trades and settlement; returns its trades for the tape, oldest first. */
	private List<TapeTrade> write(PlaceOrder command, MatchPlan plan, long sequence) {
		// PostgreSQL keeps microseconds, so the tape in memory holds exactly what the database does.
		Instant executedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
		Map<Integer, WriteSet.NewTrade> trades = new HashMap<>();
		List<TapeTrade> tape = new ArrayList<>(plan.fills().size());
		Set<UUID> touched = new LinkedHashSet<>();
		touched.add(command.accountId());
		// Trades are numbered from the writer's own counter, which moves only after the commit. The row is moved once,
		// first, so a counter that disagrees halts the writer before any trade is written under a number already taken.
		long before = known(command.instrumentId()).lastTradeSequence();
		if (!plan.fills().isEmpty()) {
			store.advanceTradeSequence(command.instrumentId(), before, before + plan.fills().size());
		}
		long tradeSequence = before;
		boolean takerBuys = command.side() == Side.BUY;
		for (int i = 0; i < plan.fills().size(); i++) {
			Fill fill = plan.fills().get(i);
			writes.fillMaker(fill.makerOrderId(), fill.quantity());
			WriteSet.NewTrade trade = new WriteSet.NewTrade(UUID.randomUUID(), command.instrumentId(), ++tradeSequence,
					fill.makerOrderId(), plan.taker().orderId(),
					takerBuys ? plan.taker().accountId() : fill.makerAccountId(),
					takerBuys ? fill.makerAccountId() : plan.taker().accountId(), fill.price().cents(), fill.quantity(),
					executedAt);
			writes.trade(trade);
			trades.put(i, trade);
			tape.add(new TapeTrade(trade.id(), trade.tradeSequence(), fill.price().cents(), fill.quantity(),
					command.side(), executedAt));
			touched.add(fill.makerAccountId());
		}
		SettlementPlan settlement = Settlement.of(command.instrumentId(), plan);
		for (SettlementPlan.Batch batch : settlement.batches()) {
			WriteSet.NewTrade trade = trades.get(batch.fillIndex());
			writes.batch(batch.cause(), trade == null ? null : trade.id(), batch.postings());
		}
		settlement.balanceChanges().forEach(writes::balance);
		writes.touch(touched);
		String symbol = known(command.instrumentId()).symbol();
		for (int i = 0; i < plan.fills().size(); i++) {
			writes.tradeEvent(trades.get(i), symbol);
		}
		return tape;
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
			case InstrumentEdit edit -> edit.commandSequence();
			case InstrumentDeletion deletion -> deletion.commandSequence();
			case AccountResult account -> account.commandSequence();
			case TraderResult trader -> trader.commandSequence();
			case BotsResult bots -> bots.commandSequence();
			case BotRunResult run -> run.commandSequence();
			case TokenRevocation revocation -> revocation.commandSequence();
			default -> throw new IllegalArgumentException("Not a command result: " + result);
		};
		store.flush(simulationId, sequence, writes);
		writes = new WriteSet();
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
	/** An order for an instrument the market doesn't have. Raised before any transaction, so nothing was written. */
	public static final class UnknownInstrumentException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		public UnknownInstrumentException(UUID instrumentId) {
			super("Unknown instrument " + instrumentId);
		}

	}

	/**
	 * Opens a command in one statement: locks the market row while this writer still owns it, looks up the key's stored
	 * answer, and takes the next command sequence unless there is one. A market that another writer has taken over
	 * fences this one.
	 */
	private <T> TradingStore.Opening<T> open(CommandKey key, Class<T> type) {
		return store.open(simulationId, writerEpoch, key, type).orElseThrow(StaleWriterException::new);
	}

	static final class StaleWriterException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		StaleWriterException() {
			super("The writer epoch changed; another writer owns the market");
		}

	}

}
