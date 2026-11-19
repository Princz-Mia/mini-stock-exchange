package com.mia_princz.mse.execution.trading.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mia_princz.mse.execution.trading.domain.BookOrder;
import com.mia_princz.mse.execution.trading.domain.DepthLevel;
import com.mia_princz.mse.execution.trading.domain.OrderRequest;

/** What the engine needs from the database. Every method runs inside the caller's transaction. */
public interface TradingStore {

	record Market(UUID simulationId, String state, long writerEpoch, int maxOpenOrdersPerAccount,
			int maxOpenOrdersTotal, int maxFillsPerCommand) {

		/** The same market once a transition has been applied in the current transaction. */
		public Market withState(String newState) {
			return new Market(simulationId, newState, writerEpoch, maxOpenOrdersPerAccount, maxOpenOrdersTotal,
					maxFillsPerCommand);
		}

	}

	/**
	 * An instrument as the writer keeps it: what its public snapshot carries, and the sequence of its newest committed
	 * trade. Read at startup, and taken from the row itself whenever an instrument command writes it.
	 */
	record Instrument(UUID id, String symbol, String displayName, long referencePriceCents, long tickCents,
			long lotSize, int ruleVersion, long lastTradeSequence) {

		public Instrument withLastTradeSequence(long sequence) {
			return new Instrument(id, symbol, displayName, referencePriceCents, tickCents, lotSize, ruleVersion,
					sequence);
		}

	}

	/** A row the writer keeps in memory was not as the writer believed; the writer must halt. */
	final class OutOfStepException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		public OutOfStepException(String message) {
			super(message);
		}

	}

	record Available(long cashCents, long shares) {
	}

	/** A new order row. Rejected orders have no accepted sequence. */
	record NewOrder(UUID id, UUID simulationId, OrderRequest request, UUID instrumentId,
			PlacementResult.OrderState state, String reason, long filled, long working, long canceled,
			Long acceptedSequence) {
	}

	/**
	 * The first committed result of a command, stored with the request it answered. The result is read only when the
	 * key's route and hash match, and is null otherwise: another route's answer is a conflict, whatever its shape.
	 */
	record StoredResult<T>(String route, byte[] requestHash, T result) {
	}

	/**
	 * A command's start: the locked market, the command's sequence, and the stored answer if its key was seen before.
	 * With a stored answer, {@code sequence} is the last one taken, not a new one.
	 */
	record Opening<T>(Market market, long sequence, Optional<StoredResult<T>> prior) {
	}

	/** An order row as a cancel reads it to answer for an order that is not resting in the writer's books. */
	record ExistingOrder(UUID id, UUID accountId, UUID instrumentId, String state, long working) {
	}

	/** Whether a market has been set up yet. Before that there is nothing to trade or rebuild. */
	boolean hasSimulation();

	/** Creates the market in SETUP with its issuance account, already claimed by this writer. Returns the epoch. */
	long createMarket(UUID simulationId, UUID issuanceAccountId);

	/** Locks the market row for the rest of the transaction; every trading command starts here. */
	Market lockMarket();

	/** Takes over the market: increments the writer epoch, so a previous writer's next transaction is refused. */
	long claimWriterEpoch(UUID simulationId);

	/** Open and partially filled orders of one instrument, in accepted-sequence order. */
	List<BookOrder> activeOrders(UUID instrumentId);

	/** Every disagreement between balances, the ledger and open orders. Empty means consistent. */
	List<String> reconciliationProblems(UUID simulationId);

	/** Moves the market to {@code state}; the first move to OPEN also records when the market opened. */
	void updateMarketState(UUID simulationId, String state);

	/** Records an operator action and its outcome. The correlation ID is the command's idempotency key. */
	void insertAudit(UUID simulationId, UUID actorUserId, String action, String outcome, UUID correlationId,
			long commandSequence);

	List<Instrument> instruments(UUID simulationId);

	boolean symbolTaken(UUID simulationId, String symbol);

	/** The instrument's symbol, if this market has it. */
	Optional<String> instrumentSymbol(UUID simulationId, UUID instrumentId);

	/**
	 * Sets the fields an operator edits, the display name null for none; the database refuses it once the market has
	 * left SETUP.
	 */
	Instrument updateInstrument(UUID simulationId, UUID instrumentId, long referencePriceCents, String displayName);

	/**
	 * Everything that refers to the instrument. The holders are named up to {@code holdersNamed}; {@code otherReferences}
	 * counts ledger entries, trades and bot runs, which come with an allocation or cannot exist in SETUP, but would
	 * otherwise make the delete fail on a foreign key.
	 */
	record InstrumentUsage(List<String> holders, long holderCount, long orderCount, long otherReferences) {

		public boolean inUse() {
			return holderCount > 0 || orderCount > 0 || otherReferences > 0;
		}

	}

	InstrumentUsage instrumentUsage(UUID simulationId, UUID instrumentId, int holdersNamed);

	/** Deletes the instrument; the database refuses it once the market has left SETUP, or while anything refers to it. */
	void deleteInstrument(UUID simulationId, UUID instrumentId);

	/** Whether any login has this username, ignoring case. */
	boolean usernameTaken(String username);

	/** A trader login, with its password already hashed. */
	void insertTraderUser(UUID userId, String username, String passwordHash);

	/** Whether this user already has an account in the market. */
	boolean hasAccount(UUID simulationId, UUID ownerUserId);

	void insertTraderAccount(UUID simulationId, UUID accountId, UUID ownerUserId, String displayName);

	int botCount(UUID simulationId);

	/** How many bots carry each strategy's tag; a strategy no bot carries is left out. */
	Map<BotStrategy, Integer> taggedBotCounts(UUID simulationId);

	/** The run whose desired state is RUNNING, if any; {@code endsAt} is null for a run without a time limit. */
	record RunningBotRun(UUID id, Instant endsAt) {
	}

	Optional<RunningBotRun> runningBotRun(UUID simulationId);

	/** A run's desired state, or empty if the simulation has no such run. */
	Optional<String> botRunState(UUID simulationId, UUID runId);

	record NewBotRun(UUID id, UUID simulationId, long seed, long commandsPerSecondHundredths, int maxOrderQuantity,
			int maxPosition, UUID startedBy, Instant startedAt, long startSequence, Instant endsAt) {
	}

	void insertBotRun(NewBotRun run);

	void insertBotRunInstrument(UUID runId, UUID simulationId, UUID instrumentId);

	/**
	 * Assigns the oldest {@code count} bots tagged with the strategy to the run, in one statement, and returns how many
	 * were assigned.
	 */
	int assignOldestBots(UUID runId, UUID simulationId, BotStrategy strategy, int count);

	void requestBotRunStop(UUID runId, Instant at);

	/** Every bot is created with its strategy; bots made untagged before it was required keep a null one. */
	void insertBotAccount(UUID simulationId, UUID accountId, String displayName, BotStrategy strategy);

	/** Stores a bot's token hash; the token itself is never stored. */
	void insertApiToken(UUID tokenId, UUID accountId, byte[] tokenHash);

	/** Whether the account is a bot in this market. */
	boolean isBotAccount(UUID simulationId, UUID accountId);

	/** Revokes the account's active tokens; how many there were. A revoked token stays revoked. */
	int revokeApiTokens(UUID accountId, Instant at);

	/**
	 * Funds a new account from the issuance account in one balanced GENESIS batch, creates its cash and position rows,
	 * and records each amount as an initial allocation linked to that batch.
	 */
	void insertGenesis(UUID simulationId, UUID accountId, String accountKind, long commandSequence, long cashCents,
			List<ProvisionAccount.Holding> holdings);

	/** The display name is null for none. Returns the row as written, its defaults included. */
	Instrument insertInstrument(UUID simulationId, UUID instrumentId, String symbol, String displayName,
			long referencePriceCents);

	Available available(UUID accountId, UUID instrumentId);

	/** Each account with open or partially filled orders, and how many, to check the writer's own count. */
	Map<UUID, Integer> restingOrdersByAccount(UUID simulationId);

	void insertOrder(NewOrder order);

	/** Reads the order without a lock: only the writer changes orders. */
	Optional<ExistingOrder> findOrder(UUID orderId);

	/**
	 * Cancels the account's order if it is still resting with exactly {@code quantity} working; false if no such row,
	 * which means the writer's book is out of step with the database.
	 */
	boolean cancelOrder(UUID orderId, UUID accountId, long quantity, String reason);

	/**
	 * Moves the instrument's last trade sequence from {@code from} to {@code to}, once per command that traded in it.
	 * Throws {@link OutOfStepException} if the row was not at {@code from}.
	 */
	void advanceTradeSequence(UUID instrumentId, long from, long to);

	/**
	 * Writes a command's collected rows, one statement per kind: maker fills, trades, journal batches with their
	 * postings, balances, account versions, then outbox events in the order they were added. Each statement checks it
	 * changed exactly the rows it was given. A missing cash or position row is inserted, as the first change to it.
	 */
	void flush(UUID simulationId, long commandSequence, WriteSet writes);

	/**
	 * Opens a trading command in one statement, in the order every command needs: locks the market row if
	 * {@code writerEpoch} still owns it, looks up the stored answer for {@code key}, and takes the next command sequence
	 * unless there is a stored answer (a replay takes none, so sequences stay gap-free). Empty when another writer has
	 * claimed the market since.
	 */
	<T> Optional<Opening<T>> open(UUID simulationId, long writerEpoch, CommandKey key, Class<T> type);

	/** {@code status} is the HTTP status the stored result is replayed with. */
	void insertResult(UUID simulationId, CommandKey key, int status, Object result, long commandSequence);

	/**
	 * The public state of one instrument once the current command commits: the instrument as it will be (its last
	 * trade sequence included), depth from the book plus the command's changes, and the tape newest first with the
	 * command's own trades. Nothing is read from the database.
	 */
	record MarketView(UUID simulationId, Instrument instrument, Market market, List<DepthLevel> bids,
			List<DepthLevel> asks, List<TapeTrade> recentTrades, long commandSequence, Instant generatedAt) {
	}

	/** An instrument's newest trades, newest first, to rebuild its tape at startup. */
	List<TapeTrade> recentTrades(UUID instrumentId, int limit);

	/** Writes the instrument's last {@code MarketSnapshot}, which says it was removed, to the outbox. */
	void insertRemovedSnapshot(MarketView view);

}
