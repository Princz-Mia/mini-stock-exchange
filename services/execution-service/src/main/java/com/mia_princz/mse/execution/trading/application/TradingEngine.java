package com.mia_princz.mse.execution.trading.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mia_princz.mse.execution.trading.application.CommandWriter.WriterHaltException;
import com.mia_princz.mse.execution.trading.application.PlacementResult.OrderState;
import com.mia_princz.mse.execution.trading.application.TradingStore.Market;
import com.mia_princz.mse.execution.trading.application.TradingStore.NewOrder;
import com.mia_princz.mse.execution.trading.application.TransactionRunner.CommitOutcomeUnknownException;
import com.mia_princz.mse.execution.trading.domain.BookDelta;
import com.mia_princz.mse.execution.trading.domain.BookOverlay;
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
 * Processes commands on the single writer. Orders and cancels queued together run as one run, up to the writer's run
 * limit, in one database transaction: one lock, one epoch check and one lookup of every key, then each command in
 * admission order with its own sequence, risk check, matching, rows, ledger, balances and outbox events, and one
 * commit. Each command is planned against the books as the run's earlier commands leave them, held in an overlay;
 * the books, the tapes and every count the writer keeps change only after the commit, in sequence order, and then
 * each command gets its answer. A run that fails without halting rolls back and its commands run again one by one,
 * so each gets the answer it would get alone. Any other command is a transaction of its own, except closing the
 * market, which cancels every resting order and takes one transaction for each part of them. If anything leaves the
 * books and the database possibly out of step, the writer halts.
 */
public final class TradingEngine {

	/** Trades in a public market snapshot's tape. */
	static final int TAPE_TRADES = 50;

	/** Levels per side in a public market snapshot. */
	static final int SNAPSHOT_LEVELS = 20;

	/** The most resting orders one transaction of a market's close cancels, unless configured. */
	public static final int CLOSE_CHUNK_ORDERS = 5_000;

	/** The reason on an order the market's close cancelled. */
	static final String CANCELED_BY_CLOSE = "MARKET_CLOSED";

	/**
	 * What a later part of a close's sweep is recorded as: its audit action, and the route of its stored answer, which
	 * no request ever comes by.
	 */
	static final String SWEEP = "MARKET_CLOSE_SWEEP";

	/** How many times a later part of a close's sweep is tried before the writer gives the close up. */
	static final int SWEEP_ATTEMPTS = 3;

	/** The wait before a part that failed is tried again, once for each attempt made so far. */
	private static final long SWEEP_RETRY_PAUSE_MILLIS = 200;

	/** How the writer waits before it tries a failed part of a close again. */
	@FunctionalInterface
	public interface RetryPause {

		void pause(long millis) throws InterruptedException;

	}

	private static final Logger LOG = LoggerFactory.getLogger(TradingEngine.class);

	private static final long FALLBACK_LOG_INTERVAL_NANOS = 10_000_000_000L;

	/** When a fallback was last logged, on the writer's clock; zero before the first. */
	private long lastFallbackLogged;

	private int fallbacksUnlogged;

	private final TradingStore store;

	private final TransactionRunner transactions;

	private final CommandWriter writer;

	private final Clock clock;

	private final BotCredentials botCredentials;

	private final AccountChanges accountChanges;

	private final WriterTimings timings;

	private final LongSupplier nanoTime;

	private final int closeChunkOrders;

	private final RetryPause retryPause;

	private final Map<UUID, OrderBook> books = new HashMap<>();

	/** The current transaction's writes, its stored answers included, sent once just before its commit. */
	private WriteSet writes = new WriteSet();

	/** Each account's resting orders across all the books, kept by the books themselves. */
	private final RestingOrders resting;

	/** Every instrument as its public snapshot needs it, with its last trade sequence; changed only after commit. */
	private final Map<UUID, TradingStore.Instrument> catalogue = new HashMap<>();

	/** Each instrument's newest trades for its snapshots, kept like the books: changed only after a commit. */
	private final Map<UUID, Tape> tapes = new HashMap<>();

	/**
	 * Accounts known to trade in this market: all of them at startup, then each one a setup command creates, once
	 * that has committed; any other is looked up the first time it sends an order.
	 */
	private final Set<UUID> accounts = new HashSet<>();

	/**
	 * Runs runs of trading commands for the writer. One object for the engine's life: the writer joins queued commands
	 * into a run only when they go to the same processor.
	 */
	private final CommandWriter.RunProcessor<Trading> runs = this::processBatch;

	/** A trading command waiting in the writer's queue, with when it was admitted. */
	sealed interface Trading {

		CommandKey key();

		long admitted();

	}

	record QueuedPlace(CommandKey key, PlaceOrder command, long admitted) implements Trading {
	}

	record QueuedCancel(CommandKey key, CancelOrder command, long admitted) implements Trading {
	}

	/** Written by the writer only; volatile so that {@link #simulationId()} can answer on any thread. */
	private volatile UUID simulationId;

	/** Whether the market this engine holds has closed, as last committed; written by the writer only. */
	private volatile boolean closed;

	/**
	 * The close this engine could not finish, once there is one: set where the writer halts for it, or where startup
	 * gives it up, and never cleared, since only another start finishes it. Volatile, because a close sent meanwhile
	 * is answered from it on the caller's thread.
	 */
	private volatile CloseUnfinishedException unfinishedClose;

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
		this(store, transactions, writer, clock, botCredentials, accountChanges, timings, nanoTime, resting,
				CLOSE_CHUNK_ORDERS);
	}

	/** {@code closeChunkOrders} is the most resting orders one transaction of a market's close cancels. */
	public TradingEngine(TradingStore store, TransactionRunner transactions, CommandWriter writer, Clock clock,
			BotCredentials botCredentials, AccountChanges accountChanges, WriterTimings timings, LongSupplier nanoTime,
			RestingOrders resting, int closeChunkOrders) {
		this(store, transactions, writer, clock, botCredentials, accountChanges, timings, nanoTime, resting,
				closeChunkOrders, Thread::sleep);
	}

	/** {@code retryPause} is the wait before a failed part of a close is tried again, replaceable in tests. */
	public TradingEngine(TradingStore store, TransactionRunner transactions, CommandWriter writer, Clock clock,
			BotCredentials botCredentials, AccountChanges accountChanges, WriterTimings timings, LongSupplier nanoTime,
			RestingOrders resting, int closeChunkOrders, RetryPause retryPause) {
		if (closeChunkOrders < 1) {
			throw new IllegalArgumentException(
					"A close cancels at least one order per transaction: " + closeChunkOrders);
		}
		this.closeChunkOrders = closeChunkOrders;
		this.retryPause = retryPause;
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
	 * open orders. Any disagreement fails startup instead of trading on state that cannot be trusted. The market is
	 * the current simulation's, closed or not: a closed one is loaded the same way, so that every command is answered
	 * by it, as closed. A closed market that still has resting orders was closed by a process that stopped before it
	 * had cancelled them all; they are cancelled here, before any command is admitted. That process may also have
	 * stopped after its last order and before it removed the bot token files, so those are removed here too.
	 * <p>
	 * Whatever this engine held before is replaced, not added to: started again in place after the current simulation
	 * has changed, it keeps no book, instrument or tape of the one it held.
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
			Set<UUID> trading = store.tradingAccounts(simulation);
			simulationId = simulation;
			writerEpoch = epoch;
			closed = "CLOSED".equals(market.state());
			books.clear();
			books.putAll(rebuilt);
			catalogue.clear();
			catalogue.putAll(rebuiltCatalogue);
			tapes.clear();
			tapes.putAll(rebuiltTapes);
			accounts.clear();
			accounts.addAll(trading);
			return null;
		});
		if (closed) {
			if (restingOrders() > 0) {
				finishInterruptedClose();
			}
			// Also with nothing left to cancel: a close can halt once every order is cancelled, and its key, which
			// would tidy up, is then not sent again. One read and a delete for each file, most of them gone already.
			discardBotTokens();
		}
		started = true;
	}

	/**
	 * Finishes the order sweep of a close that a stopped process left unfinished, on the thread that starts the engine:
	 * the books are rebuilt and reconciled, and no command runs yet. The sweep's parts are recorded under the operator
	 * who closed the market, as they would have been. Anything that fails here fails startup, and a close sent to
	 * the process while it stops is told the close is unfinished.
	 */
	private void finishInterruptedClose() {
		TradingStore.AppliedClose close = transactions.inTransaction(() -> store.appliedClose(simulationId))
			.orElseThrow(() -> new IllegalStateException("Simulation " + simulationId + " is closed with "
					+ restingOrders() + " orders still resting, and no close of it is on record"));
		int resting = restingOrders();
		SweepTimes times = new SweepTimes();
		try {
			finishSweep(close.actorUserId(), times);
		}
		catch (RuntimeException | Error ex) {
			unfinishedClose = new CloseUnfinishedException(close.commandSequence(), restingOrders(), ex);
			throw new IllegalStateException("The market's close could not be finished", ex);
		}
		LOG.info("Finished the market's close at startup: {} resting orders cancelled in {} transactions in {} ms, "
				+ "the longest {} ms", resting, times.transactions, times.total / 1_000_000, times.longest / 1_000_000);
	}

	/** Starts with no market to rebuild: commands are admitted, and the first setup command creates the market. */
	public void startEmpty() {
		started = true;
	}

	/**
	 * The simulation this engine writes for, from memory and on any thread: the current one, since only the writer
	 * sets a market up. Null while there is none, and until startup has taken the market over.
	 */
	public UUID simulationId() {
		return simulationId;
	}

	/**
	 * The same, but null once the market has closed: for a check that must pass only while the simulation is not
	 * closed, made without reading its row.
	 */
	public UUID simulationIdUnlessClosed() {
		return closed ? null : simulationId;
	}

	/** Places an order. A repeat of the same key and request returns the first committed result unchanged. */
	public CompletableFuture<PlacementResult> place(CommandKey key, PlaceOrder command) {
		return submitTrading(new QueuedPlace(key, command, nanoTime.getAsLong()));
	}

	/** Cancels a resting order; allowed while the market is open or halted. */
	public CompletableFuture<CancelResult> cancel(CommandKey key, CancelOrder command) {
		return submitTrading(new QueuedCancel(key, command, nanoTime.getAsLong()));
	}

	/**
	 * Opens, halts or resumes the market. The new state takes effect at the command's sequence: every later command
	 * reads it from the locked market row. Books are untouched, so resting orders keep their priority.
	 */
	public CompletableFuture<MarketTransition> control(CommandKey key, MarketControl command) {
		if (command.action() == MarketControl.Action.CLOSE) {
			throw new IllegalArgumentException("Closing is its own command, with its own answer");
		}
		return submit(() -> processControl(key, command));
	}

	/**
	 * Closes the market, for good, from OPEN or HALTED. The close takes effect at the command's sequence: every later
	 * command reads it from the locked market row and is refused as for a closed market. In the command's own
	 * transaction every bot run that has not ended ends, every bot token is revoked, the first part of the resting
	 * orders is cancelled with their holds released, and every instrument's snapshot says CLOSED.
	 * <p>
	 * The rest of the resting orders are cancelled in the same writer submission, so no other command comes between,
	 * in further transactions of a bounded size, each with its own command sequence: all of them in one would hold the
	 * writer for one very large commit. The books change after each commit, as for every command. The answer is given
	 * once no order rests.
	 * <p>
	 * A close whose own transaction has committed is never left half done on a running writer. A later part that fails
	 * and rolls back is tried again a few times; if it still fails, or anything else stops the sweep, the writer halts
	 * with a {@link CloseUnfinishedException} as the cause, and startup cancels what still rests before it takes a
	 * command. So a writer that takes commands for a closed market has no order resting.
	 * <p>
	 * A close sent to an engine that has stopped for an unfinished close fails with that
	 * {@link CloseUnfinishedException} itself, whether it was queued behind the close that halted or is refused now:
	 * the caller of the first may have stopped waiting before the halt, and its key must still find out what happened.
	 */
	public CompletableFuture<MarketClosing> close(CommandKey key, MarketControl command) {
		if (command.action() != MarketControl.Action.CLOSE) {
			throw new IllegalArgumentException("Not a close: " + command.action());
		}
		CompletableFuture<MarketClosing> submitted;
		try {
			submitted = submit("close", () -> processClose(key, command));
		}
		catch (CommandWriter.WriterStoppedException ex) {
			// Read after the refusal: an unfinished close is kept before its writer stops admitting.
			CloseUnfinishedException unfinished = unfinishedClose;
			if (unfinished == null) {
				throw ex;
			}
			return CompletableFuture.failedFuture(unfinished);
		}
		CompletableFuture<MarketClosing> answer = new CompletableFuture<>();
		submitted.whenComplete((result, failure) -> {
			CloseUnfinishedException unfinished = unfinishedClose;
			if (failure == null) {
				answer.complete(result);
			}
			else if (failure instanceof CommandWriter.WriterStoppedException && unfinished != null) {
				answer.completeExceptionally(unfinished);
			}
			else {
				answer.completeExceptionally(failure);
			}
		});
		return answer;
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

	/** Starts a bot run while the market is open or halted; one run runs at a time, and none in a closed market. */
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

	/**
	 * One command of a run, done inside its transaction: its answer, and what it changes once the run commits. A plan
	 * or a cancelled order for its instrument's book (neither for a replay, a rejection or a cancel that found nothing
	 * resting), its trades for the tape, and the accounts it changed (none for a replay).
	 */
	private record Ran(Object result, UUID instrumentId, MatchPlan plan, UUID canceledOrderId, List<TapeTrade> trades,
			Set<UUID> changed, long sequence) {

		/** A stored answer given again, which changes nothing. */
		static Ran replayed(Object result) {
			return new Ran(result, null, null, null, List.of(), Set.of(), 0);
		}

		/** An answer that changes no book, for the given accounts. */
		static Ran answered(Object result, Set<UUID> changed, long sequence) {
			return new Ran(result, null, null, null, List.of(), changed, sequence);
		}

	}

	/** A command key without its request: two commands with the same one share a stored answer. */
	private record KeyId(UUID principalId, UUID idempotencyKey) {

		static KeyId of(CommandKey key) {
			return new KeyId(key.principalId(), key.idempotencyKey());
		}

	}

	/** The answer an earlier command of the run stored for its key, for a later command with the same key. */
	private record Answered(CommandKey key, Object result) {
	}

	/** An order the run has written or changed, as the run leaves it: its owner and its state. */
	private record RunOrder(UUID accountId, String state) {
	}

	/**
	 * What a run's commands have changed so far, held apart from the books, tapes and counts until the run commits, so
	 * each command sees the run's earlier ones. Its rows are written only at the run's end, so it also holds what a
	 * later command would otherwise read back: the orders the run changed, and the balances it read. Dropped with the
	 * run if it rolls back.
	 */
	private final class RunState {

		private final Map<UUID, BookOverlay> overlays = new HashMap<>();

		/** Orders the run has written or changed. */
		private final Map<UUID, RunOrder> orders = new HashMap<>();

		/** Every balance the run's orders check, read on the first check; null until then. */
		private Map<SettlementPlan.BalanceKey, Long> balances;

		private final List<SettlementPlan.BalanceKey> checked = new ArrayList<>();

		/** The balances of the run's orders that entered its transaction: their cash, and their shares. */
		RunState(List<Trading> run, List<Integer> entering) {
			for (int i : entering) {
				if (run.get(i) instanceof QueuedPlace place) {
					checked.add(new SettlementPlan.BalanceKey(place.command().accountId(), null));
					checked.add(new SettlementPlan.BalanceKey(place.command().accountId(), place.command().instrumentId()));
				}
			}
		}

		/**
		 * What is available of a balance (cash when {@code instrumentId} is null) once the run so far is applied: as read
		 * from the database, in one statement for the whole run on the first check, with the run's changes added.
		 */
		long available(UUID accountId, UUID instrumentId) {
			SettlementPlan.BalanceKey key = new SettlementPlan.BalanceKey(accountId, instrumentId);
			if (balances == null) {
				balances = new HashMap<>(store.available(checked));
			}
			if (!checked.contains(key)) {
				throw new IllegalStateException("The run did not read balance " + key);
			}
			return balances.getOrDefault(key, 0L) + writes.availableChange(key);
		}

		/** Each instrument's newest trade sequence once the run so far commits. */
		private final Map<UUID, Long> tradeSequences = new HashMap<>();

		/** Each instrument's trades in the run so far, oldest first. */
		private final Map<UUID, List<TapeTrade>> trades = new HashMap<>();

		BookOverlay overlay(UUID instrumentId) {
			return overlays.computeIfAbsent(instrumentId, (id) -> books.get(id).overlay());
		}

		/** The order as it rests once the run so far is applied. */
		Optional<BookOrder> find(UUID instrumentId, OrderBook book, UUID orderId) {
			BookOverlay overlay = overlays.get(instrumentId);
			return overlay == null ? book.find(orderId) : overlay.find(orderId);
		}

		/** The account's resting orders once the run so far is applied. */
		int resting(UUID accountId) {
			int count = resting.of(accountId);
			for (BookOverlay overlay : overlays.values()) {
				count += overlay.restingChange(accountId);
			}
			return count;
		}

		/** The market's resting orders once the run so far is applied. */
		int restingTotal() {
			int count = restingOrders();
			for (BookOverlay overlay : overlays.values()) {
				count += overlay.sizeChange();
			}
			return count;
		}

		long lastTradeSequence(UUID instrumentId) {
			Long last = tradeSequences.get(instrumentId);
			return last != null ? last : known(instrumentId).lastTradeSequence();
		}

		/** The instrument's tape once the run so far and then {@code newTrades}, oldest first, commit. */
		List<TapeTrade> tape(UUID instrumentId, List<TapeTrade> newTrades) {
			List<TapeTrade> earlier = trades.getOrDefault(instrumentId, List.of());
			List<TapeTrade> all = newTrades;
			if (!earlier.isEmpty()) {
				all = new ArrayList<>(earlier);
				all.addAll(newTrades);
			}
			Tape tape = tapes.get(instrumentId);
			return tape == null ? List.copyOf(all) : tape.with(all);
		}

		void traded(UUID instrumentId, List<TapeTrade> newTrades) {
			if (!newTrades.isEmpty()) {
				tradeSequences.put(instrumentId, newTrades.getLast().tradeSequence());
				trades.computeIfAbsent(instrumentId, (id) -> new ArrayList<>()).addAll(newTrades);
			}
		}

	}

	/** The run's transaction failed without halting and rolled back: nothing of it was written. */
	private static final class NotApplied extends RuntimeException {

		private static final long serialVersionUID = 1L;

		NotApplied(Throwable cause) {
			super(cause);
		}

	}

	/** A halt found inside a run's transaction, with the index of the command that caused it. */
	private static final class HaltInRun extends RuntimeException {

		private static final long serialVersionUID = 1L;

		private final int index;

		HaltInRun(int index, RuntimeException cause) {
			super(cause);
			this.index = index;
		}

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

	/**
	 * Runs a run of trading commands the writer took together, in one transaction, and gives each its outcome, in
	 * order; at the default run limit of one, every run is a single command, as it always was.
	 * <ul>
	 * <li>A run that fails without halting (a constraint, say, or a commit the database refused) rolled back whole, so
	 * its commands run again one by one and each gets the outcome it would get alone: a command that fails then fails
	 * alone, and one that halts the writer fails with the halt while the ones after it fail as never run. The fallback
	 * is logged and counted, since a run that keeps failing costs more than its commands alone.</li>
	 * <li>A run that halts the writer before or at its commit fails every command that entered its transaction: the
	 * one that caused the halt with it, the others as stopped.</li>
	 * <li>A run that halts the writer while being applied, after its commit, keeps the answers of the commands applied
	 * before; the rest fail, to be looked up by their keys.</li>
	 * <li>An order for an unknown instrument fails as it would alone, whatever happens to the rest.</li>
	 * <li>A writer closing past its grace period interrupts its thread, and commands not yet started then fail as never
	 * run.</li>
	 * </ul>
	 */
	List<CommandWriter.RunOutcome> processBatch(List<Trading> run) {
		long starting = nanoTime.getAsLong();
		for (Trading command : run) {
			timings.record(WriterTimings.Phase.QUEUE_WAIT, kind(command), starting - command.admitted());
		}
		if (Thread.currentThread().isInterrupted()) {
			List<CommandWriter.RunOutcome> stopped = new ArrayList<>(run.size());
			run.forEach((command) -> stopped.add(stopped("Writer closed before this command ran")));
			return stopped;
		}
		try {
			return runTogether(run);
		}
		catch (NotApplied ex) {
			if (run.size() == 1) {
				// As the writer treats a failing command on its own: it fails, and the others go on.
				return List.of(CommandWriter.RunOutcome.failed(ex.getCause()));
			}
			fellBack(run.size(), ex.getCause());
		}
		List<CommandWriter.RunOutcome> outcomes = new ArrayList<>(run.size());
		String stopped = null;
		for (Trading command : run) {
			if (stopped == null && Thread.currentThread().isInterrupted()) {
				stopped = "Writer closed before this command ran";
			}
			if (stopped != null) {
				outcomes.add(stopped(stopped));
				continue;
			}
			CommandWriter.RunOutcome outcome;
			try {
				outcome = runTogether(List.of(command)).getFirst();
			}
			catch (NotApplied ex) {
				outcome = CommandWriter.RunOutcome.failed(ex.getCause());
			}
			outcomes.add(outcome);
			if (outcome instanceof CommandWriter.RunOutcome.Halted) {
				stopped = "Writer halted before this command ran";
			}
		}
		return outcomes;
	}

	/**
	 * Records a run that fell back to one command at a time. Logged at most once every ten seconds, with how many fell
	 * back since: a run-path mistake would make every run fail, and one line per run would bury it.
	 */
	private void fellBack(int commands, Throwable cause) {
		timings.runFellBack(commands);
		long now = nanoTime.getAsLong();
		fallbacksUnlogged++;
		if (lastFallbackLogged == 0 || now - lastFallbackLogged >= FALLBACK_LOG_INTERVAL_NANOS) {
			LOG.warn("A run of {} commands failed and ran again one by one ({} runs fell back since the last report)",
					commands, fallbacksUnlogged, cause);
			lastFallbackLogged = now == 0 ? 1 : now;
			fallbacksUnlogged = 0;
		}
	}

	private static String kind(Trading command) {
		return command instanceof QueuedPlace ? "place" : "cancel";
	}

	private static CommandWriter.RunOutcome stopped(String message) {
		return CommandWriter.RunOutcome.failed(new CommandWriter.WriterStoppedException(message));
	}

	/**
	 * Runs commands in one transaction and gives each its outcome, in order; throws {@link NotApplied} if the
	 * transaction failed without halting, so that nothing of it was written. An order for an instrument the market does
	 * not have fails first, outside the transaction, as it would alone. Once the transaction has committed, each
	 * command is applied to the books, the tapes and the counts in sequence order, its accounts are told, and only then
	 * is it answered.
	 */
	private List<CommandWriter.RunOutcome> runTogether(List<Trading> run) {
		CommandWriter.RunOutcome[] outcomes = new CommandWriter.RunOutcome[run.size()];
		List<Integer> entering = new ArrayList<>(run.size());
		List<TradingStore.RunKey> keys = new ArrayList<>(run.size());
		for (int i = 0; i < run.size(); i++) {
			Trading command = run.get(i);
			if (command instanceof QueuedPlace place && !books.containsKey(place.command().instrumentId())) {
				outcomes[i] = CommandWriter.RunOutcome.failed(new UnknownInstrumentException(place.command().instrumentId()));
				continue;
			}
			if (command instanceof QueuedPlace place && !tradesHere(place.command().accountId())) {
				outcomes[i] = CommandWriter.RunOutcome.failed(new UnknownAccountException(place.command().accountId()));
				continue;
			}
			entering.add(i);
			keys.add(new TradingStore.RunKey(command.key(),
					command instanceof QueuedPlace ? PlacementResult.class : CancelResult.class));
		}
		if (entering.isEmpty()) {
			return List.of(outcomes);
		}
		Ran[] ran = new Ran[run.size()];
		// Timed as its command when only one entered the transaction, as a run otherwise.
		String kind = entering.size() == 1 ? kind(run.get(entering.getFirst())) : "run";
		long[] marks = new long[4];
		try {
			inTransaction(marks, () -> {
				runInTransaction(run, entering, keys, outcomes, ran);
				return null;
			});
		}
		catch (HaltInRun ex) {
			// Rolled back, so none of the run was written.
			return haltedBeforeCommit(run, entering, outcomes, ex.index, (RuntimeException) ex.getCause(),
					"Writer halted before this command's run committed");
		}
		catch (CommitOutcomeUnknownException ex) {
			return haltedBeforeCommit(run, entering, outcomes, entering.getFirst(), ex,
					"Writer halted while this command's run committed; look it up by its key");
		}
		catch (RuntimeException | Error ex) {
			throw new NotApplied(ex);
		}
		// Committed: from here on, anything thrown leaves the books behind the database.
		int at = 0;
		try {
			recordTransaction(kind, marks);
			long applying = 0;
			boolean applied = false;
			for (; at < entering.size(); at++) {
				int i = entering.get(at);
				if (ran[i] == null) {
					// A conflict, answered already: it changed nothing.
					continue;
				}
				long start = nanoTime.getAsLong();
				apply(ran[i]);
				applying += nanoTime.getAsLong() - start;
				applied |= ran[i].plan() != null || ran[i].canceledOrderId() != null;
				if (!ran[i].changed().isEmpty()) {
					accountChanges.changed(ran[i].changed(), ran[i].sequence());
				}
				outcomes[i] = CommandWriter.RunOutcome.done(ran[i].result());
			}
			if (applied) {
				timings.record(WriterTimings.Phase.APPLY, kind, applying);
			}
		}
		catch (RuntimeException | Error ex) {
			if (at == entering.size()) {
				// Every command is applied and answered; only its timing failed.
				return List.of(outcomes);
			}
			return haltedAfterCommit(run, entering, outcomes, ran, at, ex);
		}
		return List.of(outcomes);
	}

	/**
	 * A committed run that failed while being applied: the commands applied before keep their answers, the one being
	 * applied halts the writer, and those after it committed without reaching the books, so each fails as stopped and
	 * is looked up by its key after the restart. They are logged here; the caller logs the halt itself.
	 */
	private static List<CommandWriter.RunOutcome> haltedAfterCommit(List<Trading> run, List<Integer> entering,
			CommandWriter.RunOutcome[] outcomes, Ran[] ran, int at, Throwable failure) {
		WriterHaltException halt = failure instanceof WriterHaltException known ? known
				: new WriterHaltException("A committed command could not be applied", failure);
		outcomes[entering.get(at)] = CommandWriter.RunOutcome.halted(halt);
		List<CommandKey> unapplied = new ArrayList<>();
		for (int later : entering.subList(at + 1, entering.size())) {
			if (ran[later] != null) {
				outcomes[later] = stopped("Writer halted after this command committed; look it up by its key");
				unapplied.add(run.get(later).key());
			}
		}
		if (!unapplied.isEmpty()) {
			LOG.error("The writer halted applying a committed run; these commands committed but reach the books only "
					+ "after a restart: {}", unapplied);
		}
		return List.of(outcomes);
	}

	/**
	 * A run whose transaction halted the writer: the command that caused it fails with the halt, and every other
	 * command that entered the transaction fails as stopped, since none of them was applied. The caller logs the halt
	 * with the key of the command that caused it; the others are logged here, since a lost commit may have committed
	 * them.
	 */
	private static List<CommandWriter.RunOutcome> haltedBeforeCommit(List<Trading> run, List<Integer> entering,
			CommandWriter.RunOutcome[] outcomes, int cause, RuntimeException failure, String others) {
		WriterHaltException halt = new WriterHaltException("Trading state may differ from the database", failure);
		List<CommandKey> stoppedKeys = new ArrayList<>();
		for (int i : entering) {
			outcomes[i] = i == cause ? CommandWriter.RunOutcome.halted(halt) : stopped(others);
			if (i != cause) {
				stoppedKeys.add(run.get(i).key());
			}
		}
		if (!stoppedKeys.isEmpty()) {
			LOG.error("The writer halted with a run of {} commands ({}); besides the one logged with the cause, these are "
					+ "answered as stopped: {}", entering.size(), others, stoppedKeys);
		}
		return List.of(outcomes);
	}

	/**
	 * Opens the run with one statement, then runs each command that entered it in order. A key answered before, in an
	 * earlier transaction or earlier in this run, gets that answer again for the same request and is a conflict for
	 * another; neither takes a sequence. Every other command takes the next of the sequences the opening took.
	 */
	private void runInTransaction(List<Trading> run, List<Integer> entering, List<TradingStore.RunKey> keys,
			CommandWriter.RunOutcome[] outcomes, Ran[] ran) {
		TradingStore.RunOpening opening = store.openRun(simulationId, writerEpoch, keys)
			.orElseThrow(() -> new HaltInRun(entering.getFirst(), new StaleWriterException()));
		RunState state = new RunState(run, entering);
		Map<KeyId, Answered> answered = new HashMap<>();
		long next = opening.firstSequence();
		for (int j = 0; j < entering.size(); j++) {
			int i = entering.get(j);
			Trading command = run.get(i);
			CommandKey key = command.key();
			Optional<TradingStore.StoredResult<?>> prior = opening.priors().get(j);
			Answered earlier = prior.isPresent() ? null : answered.get(KeyId.of(key));
			if (prior.isPresent() || earlier != null) {
				boolean same = prior.isPresent() ? key.sameRequest(prior.get().route(), prior.get().requestHash())
						: key.sameRequest(earlier.key().route(), earlier.key().requestHash());
				if (same) {
					ran[i] = Ran.replayed(prior.isPresent() ? prior.get().result() : earlier.result());
				}
				else {
					outcomes[i] = CommandWriter.RunOutcome.failed(new IdempotencyConflictException(key));
				}
				continue;
			}
			long sequence = next++;
			try {
				ran[i] = switch (command) {
					case QueuedPlace place -> placeInRun(state, opening.market(), key, place.command(), sequence);
					case QueuedCancel cancel -> cancelInRun(state, opening.market(), key, cancel.command(), sequence);
				};
			}
			catch (TradingStore.OutOfStepException | BookOutOfStepException ex) {
				throw new HaltInRun(i, ex);
			}
			answered.put(KeyId.of(key), new Answered(key, ran[i].result()));
		}
		if (next != opening.lastSequence() + 1) {
			throw new IllegalStateException("The run used command sequences up to " + (next - 1)
					+ " but its opening took them up to " + opening.lastSequence());
		}
		// Every row the run collected, once. A row not as the writer believed (a counter, a maker, a cancelled order)
		// halts the writer, but which command's row it was is not known here. A run of one blames its command; a longer
		// run rolls back and runs again one by one, so the command whose row it is halts the writer, and the ones before
		// it commit, as they would one by one.
		try {
			store.flush(simulationId, writes);
			writes = new WriteSet();
		}
		catch (TradingStore.OutOfStepException ex) {
			if (entering.size() == 1) {
				throw new HaltInRun(entering.getFirst(), ex);
			}
			throw ex;
		}
	}

	/**
	 * Whether the account trades in this market. An account of another simulation, a closed one, has balances and may
	 * still hold a credential, but an order from it would be written into this market's books under the wrong owner, so
	 * it is refused before any transaction. Startup reads the market's accounts and every setup command adds the ones
	 * it creates, so an order costs nothing more; an account that came any other way is looked up, once, since accounts
	 * never leave a simulation.
	 */
	private boolean tradesHere(UUID accountId) {
		if (accounts.contains(accountId)) {
			return true;
		}
		boolean trades = store.tradesIn(simulationId, accountId);
		if (trades) {
			accounts.add(accountId);
		}
		return trades;
	}

	/** Applies a committed command to its book, tape and instrument; anything that fails leaves them out of step. */
	private void apply(Ran ran) {
		boolean removed = true;
		try {
			if (ran.plan() != null) {
				books.get(ran.instrumentId()).apply(ran.plan());
				tapes.get(ran.instrumentId()).add(ran.trades());
				if (!ran.trades().isEmpty()) {
					long last = ran.trades().getLast().tradeSequence();
					catalogue.computeIfPresent(ran.instrumentId(), (id, known) -> known.withLastTradeSequence(last));
				}
			}
			if (ran.canceledOrderId() != null) {
				removed = books.get(ran.instrumentId()).cancel(ran.canceledOrderId()).isPresent();
			}
		}
		catch (RuntimeException | Error ex) {
			// Committed but not applied: the book no longer matches the database, whatever was thrown.
			throw new WriterHaltException(ran.plan() != null ? "The book could not apply a committed plan"
					: "The book could not apply a committed cancel", ex);
		}
		if (!removed) {
			throw new WriterHaltException("A committed cancel found no order in the book",
					new BookOutOfStepException(ran.canceledOrderId()));
		}
	}

	/** Refused, not queued, until startup has finished, so the caller can retry the same key. */
	private <T> CompletableFuture<T> submitTrading(Trading command) {
		if (!started) {
			throw new CommandWriter.WriterStoppedException("Trading has not started");
		}
		return writer.submitInRun(runs, command);
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
		long[] marks = new long[4];
		T result = inTransaction(marks, work);
		recordTransaction(kind, marks);
		return result;
	}

	/**
	 * A transaction, with when it was asked for, opened, done with its statements and committed, in {@code marks}. The
	 * rows its commands collected are written once, at the end of the work.
	 */
	private <T> T inTransaction(long[] marks, Supplier<T> work) {
		marks[0] = nanoTime.getAsLong();
		T result = transactions.inTransaction(() -> {
			marks[1] = nanoTime.getAsLong();
			writes = new WriteSet();
			T value = work.get();
			if (!writes.isEmpty()) {
				store.flush(simulationId, writes);
				writes = new WriteSet();
			}
			marks[2] = nanoTime.getAsLong();
			return value;
		});
		marks[3] = nanoTime.getAsLong();
		return result;
	}

	private void recordTransaction(String kind, long[] marks) {
		timings.record(WriterTimings.Phase.BEGIN, kind, marks[1] - marks[0]);
		timings.record(WriterTimings.Phase.WORK, kind, marks[2] - marks[1]);
		timings.record(WriterTimings.Phase.COMMIT, kind, marks[3] - marks[2]);
	}

	/**
	 * Creates the market, in SETUP with its issuance account, the first time something is set up. This commits on
	 * its own: an empty market in SETUP is a valid state, and the command that needed it then runs normally. Only
	 * while no simulation exists at all: startup loads the current one even if it is closed, and a setup command is
	 * then refused by it, as for any market not in SETUP, instead of quietly starting another.
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
		AccountResult result;
		try {
			ensureMarket();
			result = timed("other", () -> provisionInTransaction(key, command));
		}
		catch (CommitOutcomeUnknownException | StaleWriterException | TradingStore.OutOfStepException ex) {
			throw new WriterHaltException("Trading state may differ from the database", ex);
		}
		if (result.accountId() != null) {
			knownToTrade(List.of(result.accountId()));
		}
		return result;
	}

	/**
	 * Adds accounts a setup command has just committed to the ones known to trade here, so that their first orders
	 * are not looked up on the writer thread. Only after the commit: an account of a transaction that rolled back
	 * does not exist.
	 */
	private void knownToTrade(Collection<UUID> created) {
		accounts.addAll(created);
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
		TraderResult result;
		try {
			ensureMarket();
			result = timed("other", () -> addTraderInTransaction(key, command));
		}
		catch (CommitOutcomeUnknownException | StaleWriterException | TradingStore.OutOfStepException ex) {
			throw new WriterHaltException("Trading state may differ from the database", ex);
		}
		if (result.accountId() != null) {
			knownToTrade(List.of(result.accountId()));
		}
		return result;
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

	/** The answer and, when this transaction made the bots, their accounts; none for a refusal or a stored answer. */
	private record ProvisionedBots(BotsResult result, List<UUID> accounts) {
	}

	private BotsResult processBots(CommandKey key, ProvisionBots command) {
		ProvisionedBots provisioned;
		try {
			ensureMarket();
			provisioned = timed("other", () -> botsInTransaction(key, command));
		}
		catch (CommitOutcomeUnknownException | StaleWriterException | TradingStore.OutOfStepException ex) {
			throw new WriterHaltException("Trading state may differ from the database", ex);
		}
		knownToTrade(provisioned.accounts());
		return provisioned.result();
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
	private ProvisionedBots botsInTransaction(CommandKey key, ProvisionBots command) {
		TradingStore.Opening<BotsResult> opening = open(key, BotsResult.class);
		Market market = opening.market();
		if (opening.prior().isPresent()) {
			return new ProvisionedBots(replay(key, opening.prior().get()), List.of());
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
			return new ProvisionedBots(remember(key, status,
					new BotsResult(refusal, 0, null, null, population, command.strategy().name(), sequence)), List.of());
		}
		List<UUID> created = new ArrayList<>(command.count());
		for (int number = population + 1; number <= population + command.count(); number++) {
			UUID account = UUID.randomUUID();
			created.add(account);
			store.insertBotAccount(simulationId, account, ProvisionBots.name(number), command.strategy());
			store.insertGenesis(simulationId, account, "BOT", sequence, command.cashCents(), command.holdings());
			store.insertApiToken(UUID.randomUUID(), account, botCredentials.issue(account));
		}
		store.insertAudit(simulationId, command.actorUserId(), "BOT_PROVISION", "APPLIED", key.idempotencyKey(),
				sequence);
		return new ProvisionedBots(remember(key, 201, new BotsResult(BotsResult.Status.CREATED, command.count(),
				ProvisionBots.name(population + 1), ProvisionBots.name(population + command.count()),
				population + command.count(), command.strategy().name(), sequence)), created);
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
		if ("CLOSED".equals(market.state())) {
			refusal = BotRunResult.Status.MARKET_CLOSED;
		}
		else if (!"OPEN".equals(market.state()) && !"HALTED".equals(market.state())) {
			refusal = BotRunResult.Status.NOT_OPEN;
		}
		else if (running.isPresent() && (running.get().endsAt() == null || running.get().endsAt().isAfter(now))
				|| store.botRunStopping(simulationId, now)) {
			// Also while a worker is still finishing a stopped run: its last commands belong to its window, not this one's.
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
		running.ifPresent((expired) -> store.requestBotRunStop(expired.id(), expired.endsAt(), null));
		// A run not yet ended (its worker hasn't confirmed, or it never had one) ends before this one starts.
		store.endUnfinishedBotRuns(simulationId, sequence - 1, now);
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
		if (status == BotRunResult.Status.STOPPED && !store.requestBotRunStop(command.runId(), clock.instant(), sequence)) {
			// Its worker confirmed it stopped, its time being up, since the state was read.
			status = BotRunResult.Status.NOT_RUNNING;
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
		if (!action.allowedFrom(market.state())) {
			store.insertAudit(simulationId, command.actorUserId(), action.auditName(), "NOT_ALLOWED",
					key.idempotencyKey(), sequence);
			return remember(key, 409,
					new MarketTransition(MarketTransition.Status.NOT_ALLOWED, market.state(), sequence));
		}
		store.updateMarketState(simulationId, action.to());
		// Every instrument's public state carries the venue state, so each one gets a snapshot at this sequence.
		Market changed = market.withState(action.to());
		for (TradingStore.Instrument instrument : listed()) {
			snapshot(changed, instrument, books.get(instrument.id()), BookDelta.none(), sequence, List.of());
		}
		store.insertAudit(simulationId, command.actorUserId(), action.auditName(), "APPLIED", key.idempotencyKey(),
				sequence);
		return remember(key, 200, new MarketTransition(MarketTransition.Status.APPLIED, action.to(), sequence));
	}

	/** The market's instruments in symbol order: the order their snapshots are written in. */
	private List<TradingStore.Instrument> listed() {
		List<TradingStore.Instrument> listed = new ArrayList<>(catalogue.values());
		listed.sort(Comparator.comparing(TradingStore.Instrument::symbol));
		return listed;
	}

	/**
	 * One committed part of a close's order sweep, to apply to the books: the orders it cancelled, by instrument, and
	 * the accounts they belonged to.
	 */
	private record Swept(Map<UUID, List<UUID>> canceled, Set<UUID> accounts, long sequence) {

		int orders() {
			int orders = 0;
			for (List<UUID> ofInstrument : canceled.values()) {
				orders += ofInstrument.size();
			}
			return orders;
		}

	}

	/** The close's answer and, when this transaction closed the market, the first part of its sweep; else null. */
	private record Closing(MarketClosing result, Swept swept) {
	}

	/** How long a close's transactions took, in nanoseconds on the writer's clock. */
	private static final class SweepTimes {

		private int transactions;

		private long total;

		private long longest;

		void add(long nanos) {
			transactions++;
			total += nanos;
			longest = Math.max(longest, nanos);
		}

	}

	private MarketClosing processClose(CommandKey key, MarketControl command) {
		if (simulationId == null) {
			return MarketClosing.notSetUp();
		}
		SweepTimes times = new SweepTimes();
		Closing closing;
		try {
			long start = nanoTime.getAsLong();
			closing = timed("close", () -> closeInTransaction(key, command));
			times.add(nanoTime.getAsLong() - start);
		}
		catch (CommitOutcomeUnknownException | StaleWriterException | TradingStore.OutOfStepException ex) {
			// Logged here and not only by whoever awaits the answer: a close can outlast its request's wait.
			LOG.error("The market's close halted the writer in its own transaction, so whether the market closed is "
					+ "known only to the database; resting orders in the books: {}. Trading is stopped until a restart",
					restingOrders(), ex);
			throw new WriterHaltException("Trading state may differ from the database", ex);
		}
		MarketClosing result = closing.result();
		if (closing.swept() != null) {
			// Committed: the market is closed for good, whatever becomes of the orders that still rest.
			closed = true;
			try {
				applySweep(closing.swept());
				finishSweep(command.actorUserId(), times);
			}
			catch (RuntimeException | Error ex) {
				// Not an answer of "failed, nothing applied", and not a writer that goes on with a market half closed.
				CloseUnfinishedException unfinished = new CloseUnfinishedException(result.commandSequence(),
						restingOrders(), ex);
				// Kept before the writer stops admitting, so a close sent from now on is told this and not less.
				unfinishedClose = unfinished;
				// On record here, whoever is still waiting for the answer: a close can outlast its request's wait.
				LOG.error("The market's close at sequence {} halted the writer before it finished; resting orders left: "
						+ "at most {}. Trading is stopped until a restart, which finishes the close",
						unfinished.commandSequence(), unfinished.restingOrders(), ex);
				throw new WriterHaltException("The market closed, but its resting orders were not all cancelled",
						unfinished);
			}
			LOG.info("The market closed at sequence {}: {} resting orders cancelled in {} transactions in {} ms, the "
					+ "longest {} ms; {} bot runs ended", result.commandSequence(), result.canceledOrders(),
					times.transactions, times.total / 1_000_000, times.longest / 1_000_000, result.endedRuns());
		}
		// Closed by this command, or by this key's first attempt in a process that stopped before it tidied up.
		if (result.status() == MarketClosing.Status.CLOSED) {
			discardBotTokens();
		}
		return result;
	}

	/**
	 * The close itself, at its sequence. Everything that makes the market closed is in this one transaction: the
	 * state, the runs' ends, the tokens' revocation and each instrument's last state. Only the cancelling of resting
	 * orders may go on after it, and the holds of those not yet cancelled are still in place, so the market reconciles
	 * between the parts.
	 */
	private Closing closeInTransaction(CommandKey key, MarketControl command) {
		TradingStore.Opening<MarketClosing> opening = open(key, MarketClosing.class);
		Market market = opening.market();
		if (opening.prior().isPresent()) {
			return new Closing(replay(key, opening.prior().get()), null);
		}
		long sequence = opening.sequence();
		MarketControl.Action action = command.action();
		if (!action.allowedFrom(market.state())) {
			store.insertAudit(simulationId, command.actorUserId(), action.auditName(), "NOT_ALLOWED",
					key.idempotencyKey(), sequence);
			return new Closing(remember(key, 409,
					new MarketClosing(MarketClosing.Status.NOT_ALLOWED, market.state(), sequence, 0, 0)), null);
		}
		// One reading of the service's clock for everything the close stamps.
		Instant now = clock.instant();
		store.closeMarket(simulationId, now);
		int endedRuns = store.endBotRunsAtClose(simulationId, sequence, now);
		store.revokeBotTokens(simulationId, now);
		int resting = restingOrders();
		// Every instrument's public state carries the venue state, so each gets a snapshot at this sequence: its last.
		Swept swept = sweep(market.withState(action.to()), sequence, true);
		store.insertAudit(simulationId, command.actorUserId(), action.auditName(), "APPLIED", key.idempotencyKey(),
				sequence);
		return new Closing(remember(key, 200,
				new MarketClosing(MarketClosing.Status.CLOSED, action.to(), sequence, resting, endedRuns)), swept);
	}

	/**
	 * Cancels whatever still rests in the closed market, one part per transaction, until no order rests. Each part is a
	 * command of its own, with the next sequence; the books change after each commit. A writer being closed stops
	 * between two parts, and startup finishes the rest.
	 */
	private void finishSweep(UUID actorUserId, SweepTimes times) {
		while (restingOrders() > 0) {
			if (Thread.currentThread().isInterrupted()) {
				throw new CommandWriter.WriterStoppedException(
						"Writer closed before the market's close had cancelled every order");
			}
			applySweep(sweepPart(actorUserId, times));
		}
	}

	/**
	 * One later part of the sweep, committed. A part that fails and rolls back left the database and the books as they
	 * were, so it is tried again, a few times with a short wait: a connection that dropped is replaced by then. A part
	 * that may have committed, or a market that is no longer this writer's, is not tried again.
	 */
	private Swept sweepPart(UUID actorUserId, SweepTimes times) {
		for (int attempt = 1;; attempt++) {
			long start = nanoTime.getAsLong();
			try {
				Swept swept = timed("close", () -> sweepInTransaction(actorUserId));
				times.add(nanoTime.getAsLong() - start);
				return swept;
			}
			catch (CommitOutcomeUnknownException | StaleWriterException | TradingStore.OutOfStepException ex) {
				// Not known to have rolled back, or not to be written by this writer at all.
				throw ex;
			}
			catch (RuntimeException ex) {
				if (attempt == SWEEP_ATTEMPTS) {
					LOG.error("A part of the market's close failed and rolled back; giving the close up (attempt {} "
							+ "of {})", attempt, SWEEP_ATTEMPTS, ex);
					throw ex;
				}
				LOG.warn("A part of the market's close failed and rolled back; trying it again (attempt {} of {})",
						attempt, SWEEP_ATTEMPTS, ex);
				try {
					retryPause.pause(SWEEP_RETRY_PAUSE_MILLIS * attempt);
				}
				catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw new CommandWriter.WriterStoppedException(
							"Writer closed before the market's close had cancelled every order");
				}
			}
		}
	}

	/**
	 * A later part of a close's sweep. It leaves what every command sequence leaves: a stored answer, under a key of
	 * its own and the operator who closed, and an audit row; and what a cancel leaves: each order's update, the
	 * release of its hold, and a snapshot of each instrument whose book changes, still saying CLOSED.
	 */
	private Swept sweepInTransaction(UUID actorUserId) {
		CommandKey key = new CommandKey(actorUserId, UUID.randomUUID(), SWEEP, new byte[32]);
		TradingStore.Opening<MarketSweep> opening = open(key, MarketSweep.class);
		Market market = opening.market();
		if (!"CLOSED".equals(market.state())) {
			throw new IllegalStateException("Only a closed market is swept; this one is " + market.state());
		}
		long sequence = opening.sequence();
		int resting = restingOrders();
		Swept swept = sweep(market, sequence, false);
		store.insertAudit(simulationId, actorUserId, SWEEP, "APPLIED", key.idempotencyKey(), sequence);
		remember(key, 200, new MarketSweep(swept.orders(), resting - swept.orders(), sequence));
		return swept;
	}

	/**
	 * Collects one part of a close's sweep into the current transaction: resting orders up to the part's limit,
	 * instrument by instrument in symbol order, each cancelled and its hold released exactly as its owner's cancel
	 * would, and a snapshot of each instrument whose book the part changes, or of every instrument when the market's
	 * state changes with it. The books themselves change only once the transaction has committed.
	 */
	private Swept sweep(Market market, long sequence, boolean everyInstrument) {
		Map<UUID, List<UUID>> canceled = new LinkedHashMap<>();
		Set<UUID> owners = new LinkedHashSet<>();
		int left = closeChunkOrders;
		for (TradingStore.Instrument instrument : listed()) {
			OrderBook book = books.get(instrument.id());
			List<BookOrder> orders = new ArrayList<>(book.orders(Side.BUY, left));
			orders.addAll(book.orders(Side.SELL, left - orders.size()));
			left -= orders.size();
			List<UUID> ids = new ArrayList<>(orders.size());
			for (BookOrder order : orders) {
				cancelWithRelease(instrument.id(), order, CANCELED_BY_CLOSE, sequence);
				owners.add(order.accountId());
				ids.add(order.orderId());
			}
			if (!orders.isEmpty()) {
				canceled.put(instrument.id(), ids);
			}
			if (everyInstrument || !orders.isEmpty()) {
				snapshot(market, instrument, book, BookDelta.cancel(orders), sequence, List.of());
			}
		}
		// One command for each account, however many of its orders the part cancels.
		writes.touch(owners);
		return new Swept(canceled, owners, sequence);
	}

	/**
	 * Applies a committed part of the sweep to the books and tells its accounts. Anything that fails here leaves the
	 * books behind the database, so whoever runs the sweep stops for it: the writer halts, or startup fails.
	 */
	private void applySweep(Swept swept) {
		long start = nanoTime.getAsLong();
		swept.canceled().forEach((instrumentId, orders) -> {
			OrderBook book = books.get(instrumentId);
			for (UUID orderId : orders) {
				if (book.cancel(orderId).isEmpty()) {
					throw new BookOutOfStepException(orderId);
				}
			}
		});
		if (!swept.canceled().isEmpty()) {
			timings.record(WriterTimings.Phase.APPLY, "close", nanoTime.getAsLong() - start);
		}
		if (!swept.accounts().isEmpty()) {
			accountChanges.changed(swept.accounts(), swept.sequence());
		}
	}

	/**
	 * Removes the closed market's bot token files. Every token is revoked already, so this only tidies the secret
	 * store, and nothing it runs into may fail the close.
	 */
	private void discardBotTokens() {
		try {
			botCredentials.discard(transactions.inTransaction(() -> store.botAccounts(simulationId)));
		}
		catch (RuntimeException ex) {
			LOG.warn("The closed market's bot token files could not be removed; their tokens are revoked", ex);
		}
	}

	/**
	 * A cancel inside its run's transaction. The books, with the run's earlier commands over them, hold exactly the
	 * resting orders as the transaction has them, so one resting there is resting in the database: it is cancelled
	 * with one guarded update, without reading the row first.
	 */
	private Ran cancelInRun(RunState run, Market market, CommandKey key, CancelOrder command, long sequence) {
		for (Map.Entry<UUID, OrderBook> entry : books.entrySet()) {
			Optional<BookOrder> found = run.find(entry.getKey(), entry.getValue(), command.orderId());
			if (found.isPresent() && found.get().accountId().equals(command.accountId())) {
				return cancelResting(run, market, key, command, entry.getKey(), found.get(), sequence);
			}
		}
		// Not resting, or someone else's. An order the run has already written or changed answers from the run, since its
		// rows are written only at the end; any other is read, without a lock, only to answer.
		RunOrder known = run.orders.get(command.orderId());
		String state = known != null ? (known.accountId().equals(command.accountId()) ? known.state() : null)
				: store.findOrder(simulationId, command.orderId())
					.filter((existing) -> existing.accountId().equals(command.accountId()))
					.map(TradingStore.ExistingOrder::state)
					.orElse(null);
		if (state == null) {
			return Ran.answered(remember(key, 404,
					new CancelResult(command.orderId(), CancelResult.Status.NOT_FOUND, 0, sequence)), Set.of(), sequence);
		}
		CancelResult.Status status = switch (state) {
			case "CANCELED" -> CancelResult.Status.ALREADY_CANCELED;
			case "FILLED", "REJECTED" -> CancelResult.Status.NOT_CANCELABLE;
			// Resting in the database but not in the book: the book is out of step, so the writer halts.
			default -> throw new BookOutOfStepException(command.orderId());
		};
		int httpStatus = status == CancelResult.Status.NOT_CANCELABLE ? 409 : 200;
		return Ran.answered(remember(key, httpStatus, new CancelResult(command.orderId(), status, 0, sequence)),
				Set.of(), sequence);
	}

	/**
	 * Cancels an order the book holds for the caller, with an update guarded by what the book says; when the run's
	 * writes are sent, a row that is not resting as the book says halts the writer.
	 */
	private Ran cancelResting(RunState run, Market market, CommandKey key, CancelOrder command, UUID instrumentId,
			BookOrder resting, long sequence) {
		cancelWithRelease(instrumentId, resting, "CANCELED_BY_OWNER", sequence);
		run.orders.put(command.orderId(), new RunOrder(command.accountId(), "CANCELED"));
		writes.touch(List.of(command.accountId()));
		BookOverlay overlay = run.overlay(instrumentId);
		snapshot(run, market, instrumentId, overlay, BookDelta.cancel(resting), sequence, List.of());
		CancelResult result = remember(key, 200, new CancelResult(command.orderId(), CancelResult.Status.CANCELED,
				resting.remainingQuantity(), sequence));
		overlay.cancel(command.orderId());
		return new Ran(result, instrumentId, null, command.orderId(), List.of(), Set.of(command.accountId()), sequence);
	}

	/**
	 * Collects what cancelling an order the book holds writes, whoever cancels it: the order's guarded update, and the
	 * release of its whole remaining hold as a balanced batch with its balance changes.
	 */
	private void cancelWithRelease(UUID instrumentId, BookOrder resting, String reason, long sequence) {
		writes.cancel(resting.orderId(), resting.accountId(), resting.remainingQuantity(), reason);
		SettlementPlan release = Settlement.cancel(instrumentId, resting);
		for (SettlementPlan.Batch batch : release.batches()) {
			writes.batch(batch.cause(), null, sequence, batch.postings());
		}
		release.balanceChanges().forEach(writes::balance);
	}

	/**
	 * An order inside its run's transaction. Its balances are what the run read from the database, with the run's
	 * earlier changes to them added; the order is matched against its book with the run's earlier commands over it,
	 * and its plan joins them once its rows are collected.
	 */
	private Ran placeInRun(RunState run, Market market, CommandKey key, PlaceOrder command, long sequence) {
		if (!"OPEN".equals(market.state())) {
			// A closed market never opens again, so its refusal has its own code: nothing is gained by trying later.
			return reject(run, key, command, "CLOSED".equals(market.state()) ? RejectionCode.MARKET_CLOSED
					: RejectionCode.MARKET_NOT_OPEN, sequence);
		}
		long hold = Settlement.holdAmount(command.side(), command.bound(), command.quantity().shares());
		if (command.side() == Side.BUY && run.available(command.accountId(), null) < hold) {
			return reject(run, key, command, RejectionCode.INSUFFICIENT_CASH, sequence);
		}
		if (command.side() == Side.SELL && run.available(command.accountId(), command.instrumentId()) < hold) {
			return reject(run, key, command, RejectionCode.INSUFFICIENT_SHARES, sequence);
		}

		OrderRequest request = new OrderRequest(command.orderId(), command.accountId(), command.side(), command.type(),
				command.bound(), command.quantity(), sequence);
		BookOverlay overlay = run.overlay(command.instrumentId());
		MatchPlan plan = overlay.plan(request);
		if (plan.outcome() == MatchPlan.Outcome.EXECUTION_LIMIT_EXCEEDED) {
			return reject(run, key, command, RejectionCode.EXECUTION_LIMIT_EXCEEDED, sequence);
		}
		if (plan.rests() && (run.resting(command.accountId()) >= market.maxOpenOrdersPerAccount()
				|| run.restingTotal() >= market.maxOpenOrdersTotal())) {
			return reject(run, key, command, RejectionCode.ORDER_LIMIT_EXCEEDED, sequence);
		}

		PlacementResult result = accepted(command, plan, sequence);
		writes.order(new NewOrder(command.orderId(), simulationId, request, command.instrumentId(), result.state(),
				result.reason(), result.filled(), result.working(), result.canceled(), sequence));
		run.orders.put(command.orderId(), new RunOrder(command.accountId(), result.state().name()));
		List<TapeTrade> trades = write(run, command, plan, sequence);
		if (!plan.fills().isEmpty() || plan.rests()) {
			snapshot(run, market, command.instrumentId(), overlay, BookDelta.of(plan), sequence, trades);
		}
		remember(key, 201, result);
		overlay.record(plan);
		run.traded(command.instrumentId(), trades);
		// The order's own account and every resting order's account it traded against.
		Set<UUID> changed = new HashSet<>();
		changed.add(command.accountId());
		plan.fills().forEach((fill) -> changed.add(fill.makerAccountId()));
		return new Ran(result, command.instrumentId(), plan, null, trades, changed, sequence);
	}

	/**
	 * The instrument's public state once this command commits, written in the same transaction: depth from the book
	 * with the run's earlier commands and this one's changes over it, and the tape from memory with the run's trades
	 * and this command's first. Re-reading the tape from the database for every command was the largest part of the
	 * writer's time. The books and tapes themselves change only after the commit.
	 */
	private void snapshot(RunState run, Market market, UUID instrumentId, BookOverlay overlay, BookDelta delta,
			long sequence, List<TapeTrade> newTrades) {
		long last = newTrades.isEmpty() ? run.lastTradeSequence(instrumentId) : newTrades.getLast().tradeSequence();
		writes.snapshot(new TradingStore.MarketView(simulationId, known(instrumentId).withLastTradeSequence(last),
				market, overlay.depthAfter(delta, Side.BUY, SNAPSHOT_LEVELS),
				overlay.depthAfter(delta, Side.SELL, SNAPSHOT_LEVELS), run.tape(instrumentId, newTrades), sequence,
				clock.instant()));
	}

	/**
	 * The public state of an instrument a command other than an order or cancel changed. An instrument added by this
	 * command has no tape yet.
	 */
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
	private List<TapeTrade> write(RunState run, PlaceOrder command, MatchPlan plan, long sequence) {
		// PostgreSQL keeps microseconds, so the tape in memory holds exactly what the database does.
		Instant executedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
		Map<Integer, WriteSet.NewTrade> trades = new HashMap<>();
		List<TapeTrade> tape = new ArrayList<>(plan.fills().size());
		Set<UUID> touched = new LinkedHashSet<>();
		touched.add(command.accountId());
		// Trades are numbered from the writer's own counter, which moves only after the commit, with the run's earlier
		// trades after it. The row is moved once per run, first among its writes, so a counter that disagrees halts the
		// writer before any trade is written under a number already taken.
		long before = run.lastTradeSequence(command.instrumentId());
		if (!plan.fills().isEmpty()) {
			writes.tradeSequence(command.instrumentId(), before, before + plan.fills().size());
		}
		long tradeSequence = before;
		boolean takerBuys = command.side() == Side.BUY;
		for (int i = 0; i < plan.fills().size(); i++) {
			Fill fill = plan.fills().get(i);
			writes.fillMaker(fill.makerOrderId(), fill.quantity());
			run.orders.put(fill.makerOrderId(),
					new RunOrder(fill.makerAccountId(), fill.makerRemainingAfter() == 0 ? "FILLED" : "PARTIALLY_FILLED"));
			WriteSet.NewTrade trade = new WriteSet.NewTrade(UUID.randomUUID(), command.instrumentId(), ++tradeSequence,
					fill.makerOrderId(), plan.taker().orderId(),
					takerBuys ? plan.taker().accountId() : fill.makerAccountId(),
					takerBuys ? fill.makerAccountId() : plan.taker().accountId(), fill.price().cents(), fill.quantity(),
					executedAt, sequence);
			writes.trade(trade);
			trades.put(i, trade);
			tape.add(new TapeTrade(trade.id(), trade.tradeSequence(), fill.price().cents(), fill.quantity(),
					command.side(), executedAt));
			touched.add(fill.makerAccountId());
		}
		SettlementPlan settlement = Settlement.of(command.instrumentId(), plan);
		for (SettlementPlan.Batch batch : settlement.batches()) {
			WriteSet.NewTrade trade = trades.get(batch.fillIndex());
			writes.batch(batch.cause(), trade == null ? null : trade.id(), sequence, batch.postings());
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

	/** A rejection holds nothing, but the order's own account is told of it, as of any order it placed. */
	private Ran reject(RunState run, CommandKey key, PlaceOrder command, RejectionCode code, long sequence) {
		OrderRequest request = new OrderRequest(command.orderId(), command.accountId(), command.side(), command.type(),
				command.bound(), command.quantity(), sequence);
		writes.order(new NewOrder(command.orderId(), simulationId, request, command.instrumentId(),
				OrderState.REJECTED, code.name(), 0, 0, 0, null));
		run.orders.put(command.orderId(), new RunOrder(command.accountId(), OrderState.REJECTED.name()));
		return Ran.answered(remember(key, 422, new PlacementResult(command.orderId(), OrderState.REJECTED, code.name(),
				code, 0, 0, 0, sequence)), Set.of(command.accountId()), sequence);
	}

	/**
	 * Stores the first committed result in the same transaction, so a retry with the same key gets it back. It is
	 * written with the transaction's other rows, just before the commit.
	 */
	private <T> T remember(CommandKey key, int httpStatus, T result) {
		long sequence = switch (result) {
			case PlacementResult placement -> placement.commandSequence();
			case CancelResult cancel -> cancel.commandSequence();
			case MarketTransition transition -> transition.commandSequence();
			case MarketClosing closing -> closing.commandSequence();
			case MarketSweep sweep -> sweep.commandSequence();
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
		writes.result(key, httpStatus, result, sequence);
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

	/**
	 * The market closed, and this writer could not finish cancelling the orders that rested. The close itself
	 * committed, so nothing of it is undone and nothing is to be sent again: the writer halts with this as the cause,
	 * and the next start cancels what still rests before it takes a command. A close sent to the engine meanwhile
	 * fails with this itself.
	 */
	public static final class CloseUnfinishedException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		private final long commandSequence;

		private final int restingOrders;

		public CloseUnfinishedException(long commandSequence, int restingOrders, Throwable cause) {
			super("The market closed at sequence " + commandSequence + " and its close did not finish; resting orders "
					+ "left: at most " + restingOrders + ". The next start finishes it", cause);
			this.commandSequence = commandSequence;
			this.restingOrders = restingOrders;
		}

		/** The close's own sequence. */
		public long commandSequence() {
			return commandSequence;
		}

		/**
		 * The orders still in the books when the writer stopped. An upper bound on what rests in the database: a part
		 * that committed without the books hearing of it, or without their applying all of it, cancelled up to one
		 * part more.
		 */
		public int restingOrders() {
			return restingOrders;
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

	/** An order from an account that doesn't trade in this market. Raised before any transaction, as above. */
	public static final class UnknownAccountException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		public UnknownAccountException(UUID accountId) {
			super("Account " + accountId + " does not trade in this market");
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
