package com.mia_princz.mse.execution.trading.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

	/** Whether any market has been set up yet, closed or not. Before that there is nothing to load or rebuild. */
	boolean hasSimulation();

	/** Creates the market in SETUP with its issuance account, already claimed by this writer. Returns the epoch. */
	long createMarket(UUID simulationId, UUID issuanceAccountId);

	/**
	 * Starts a simulation in SETUP with its name and its issuance account, owned by a writer at {@code writerEpoch},
	 * with its first command sequence, 1, already taken by the command that starts it.
	 */
	void startMarket(UUID simulationId, UUID issuanceAccountId, String name, long writerEpoch);

	/**
	 * Locks the current simulation's row for the rest of the transaction: the one not closed or, once every one is
	 * closed, the newest. Taking over the market starts here.
	 */
	Market lockMarket();

	/**
	 * The current simulation's ID as this statement sees it. Asked once {@link #lockMarket} has returned: a locking
	 * read that waited for another transaction returns the row it chose before it waited, and never a row that
	 * transaction added.
	 */
	UUID currentSimulation();

	/**
	 * Locks the simulation's row for the rest of the transaction if {@code writerEpoch} still owns it, without taking a
	 * command sequence. Empty when another writer has claimed the market since.
	 */
	Optional<Market> lockOwnedMarket(UUID simulationId, long writerEpoch);

	/**
	 * Gives a closed simulation up for good: its writer epoch becomes 0, which no writer ever holds, so that no
	 * process that still believes it owns the simulation can open a command in it again.
	 */
	void retireMarket(UUID simulationId, long writerEpoch);

	/** Whether a simulation has this name, ignoring case. */
	boolean simulationNameTaken(String name);

	/**
	 * Every stored answer under the key's principal and idempotency key, in any simulation, oldest simulation first.
	 * Each is read as {@code type} only when its route and hash match the key's, and is null otherwise.
	 */
	<T> List<StoredResult<T>> storedInAnySimulation(CommandKey key, Class<T> type);

	/** The same, leaving out the answers stored in {@code simulationId}: the ones a command opened there cannot find. */
	<T> List<StoredResult<T>> storedInOtherSimulations(UUID simulationId, CommandKey key, Class<T> type);

	/** Takes over the market: increments the writer epoch, so a previous writer's next transaction is refused. */
	long claimWriterEpoch(UUID simulationId);

	/** Open and partially filled orders of one instrument, in accepted-sequence order. */
	List<BookOrder> activeOrders(UUID instrumentId);

	/**
	 * Every disagreement between balances, the ledger and open orders. Empty means consistent. Reads the whole ledger,
	 * which it does in one pass only inside a transaction.
	 */
	List<String> reconciliationProblems(UUID simulationId);

	/** Moves the market to {@code state}; the first move to OPEN also records when the market opened. */
	void updateMarketState(UUID simulationId, String state);

	/**
	 * Closes the market at {@code closedAt}, for good. When it opened stays as it is, so the database refuses this for
	 * a market that never opened.
	 */
	void closeMarket(UUID simulationId, Instant closedAt);

	/**
	 * Sets when the exchange closes the market itself, in the transaction that opens it. The database refuses this for
	 * a market that has not opened.
	 */
	void scheduleClose(UUID simulationId, Instant closesAt);

	/** When the exchange closes the market itself; empty for a market opened without a duration, or not opened. */
	Optional<Instant> closesAt(UUID simulationId);

	/** The operator whose opening of the market applied, as its audit row has it. Empty if it never opened. */
	Optional<UUID> openedBy(UUID simulationId);

	/**
	 * The close that closed a market, as its audit row has it: who it is recorded under, and its sequence. That is the
	 * operator who sent it or, for a close the exchange made at the market's close time, the one who opened it.
	 */
	record AppliedClose(UUID actorUserId, long commandSequence) {
	}

	/** The market's applied close, sent or scheduled. Empty if it was never closed by a command. */
	Optional<AppliedClose> appliedClose(UUID simulationId);

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

	/** Records an instrument the run trades, with the instrument's newest trade sequence as the run starts. */
	void insertBotRunInstrument(UUID runId, UUID simulationId, UUID instrumentId);

	/**
	 * Assigns the oldest {@code count} bots tagged with the strategy to the run, in one statement, and returns how many
	 * were assigned.
	 */
	int assignOldestBots(UUID runId, UUID simulationId, BotStrategy strategy, int count);

	/**
	 * Marks a running run stopped at {@code at}; {@code stopSequence} is the operator's stop command, if it was one.
	 * False if the run was no longer running: its worker confirmed it stopped in the meantime, its time being up.
	 */
	boolean requestBotRunStop(UUID runId, Instant at, Long stopSequence);

	/**
	 * Whether a worker still holds a live lease on a run that should no longer run: it is still sending the run's last
	 * commands and closing cancels, so the next run waits for its confirmation.
	 */
	boolean botRunStopping(UUID simulationId, Instant now);

	/**
	 * Ends every run of the simulation not yet ended at {@code endSequence}, with each of its instruments' newest trade
	 * sequence; a run its worker already confirmed keeps the end it recorded, and one from before windows were kept
	 * keeps none.
	 */
	void endUnfinishedBotRuns(UUID simulationId, long endSequence, Instant endedAt);

	/**
	 * Ends the simulation's runs as its market closes at {@code sequence}, and returns how many it ended. A run not
	 * yet ended ends there, by the close: stopped if it was still running, its window closed at the close's sequence
	 * with each of its instruments' newest trade sequence. No worker confirms it. Every run, whenever it ended, is
	 * also recorded as stopped, so that a worker's later heartbeat is answered as for a finished run.
	 */
	int endBotRunsAtClose(UUID simulationId, long sequence, Instant at);

	/** A bot to create: its account, its name, and the hash of the token handed over for it. */
	record NewBot(UUID accountId, String displayName, byte[] tokenHash) {
	}

	/**
	 * Creates bots, all with this strategy and endowment, in a fixed number of statements however many there are.
	 * Each gets its account, its own GENESIS batch from the issuance account with its initial allocations, its cash
	 * and position rows, and its token hash; the token itself is never stored. They are created in the order given,
	 * each a microsecond after the one before and after every bot already in the simulation, so that bots list in the
	 * order they were made. Bots made untagged before a strategy was required keep a null one.
	 */
	void insertBots(UUID simulationId, long commandSequence, BotStrategy strategy, long cashCents,
			List<ProvisionAccount.Holding> holdings, List<NewBot> bots);

	/** Whether the account is a bot in this market. */
	boolean isBotAccount(UUID simulationId, UUID accountId);

	/** Every account that trades in this simulation: its traders' and its bots'. */
	Set<UUID> tradingAccounts(UUID simulationId);

	/** Whether the account trades in this simulation: a trader's or a bot's account of it. */
	boolean tradesIn(UUID simulationId, UUID accountId);

	/** Revokes the account's active tokens; how many there were. A revoked token stays revoked. */
	int revokeApiTokens(UUID accountId, Instant at);

	/** Revokes every active token of the simulation's bots, in one statement; how many there were. */
	int revokeBotTokens(UUID simulationId, Instant at);

	/** The simulation's bot accounts. */
	List<UUID> botAccounts(UUID simulationId);

	/**
	 * Funds a new account from the issuance account in one balanced GENESIS batch, creates its cash and position rows,
	 * and records each amount as an initial allocation linked to that batch.
	 */
	void insertGenesis(UUID simulationId, UUID accountId, String accountKind, long commandSequence, long cashCents,
			List<ProvisionAccount.Holding> holdings);

	/** The display name is null for none. Returns the row as written, its defaults included. */
	Instrument insertInstrument(UUID simulationId, UUID instrumentId, String symbol, String displayName,
			long referencePriceCents);

	/**
	 * What is available of each balance (cash with no instrument, shares of one): its total less what is reserved.
	 * A balance with no row has nothing available and is left out.
	 */
	Map<com.mia_princz.mse.execution.trading.domain.SettlementPlan.BalanceKey, Long> available(
			java.util.Collection<com.mia_princz.mse.execution.trading.domain.SettlementPlan.BalanceKey> balances);

	/** Each account with open or partially filled orders, and how many, to check the writer's own count. */
	Map<UUID, Integer> restingOrdersByAccount(UUID simulationId);

	/**
	 * Reads the simulation's order without a lock: only the writer changes orders. An order of another simulation is
	 * not found, whatever its state: the writer's books say nothing about it.
	 */
	Optional<ExistingOrder> findOrder(UUID simulationId, UUID orderId);

	/**
	 * Writes a transaction's collected rows, one statement per kind (more only if a kind has more rows than one
	 * statement takes), in the order the rows depend on each other: trade counters, new orders, maker fills, cancels,
	 * trades, journal batches with their postings, balances, account versions, outbox events in the order they were
	 * added, and stored answers. Each statement checks it changed exactly the rows it was given; a trade counter, a
	 * maker or a cancelled order that is not as the writer believed throws {@link OutOfStepException}. A missing cash
	 * or position row is inserted, as the first change to it, one statement each.
	 */
	void flush(UUID simulationId, WriteSet writes);

	/**
	 * Opens a trading command in one statement, in the order every command needs: locks the market row if
	 * {@code writerEpoch} still owns it, looks up the stored answer for {@code key}, and takes the next command sequence
	 * unless there is a stored answer (a replay takes none, so sequences stay gap-free). Empty when another writer has
	 * claimed the market since.
	 */
	<T> Optional<Opening<T>> open(UUID simulationId, long writerEpoch, CommandKey key, Class<T> type);

	/** A trading command's key in a run, with the type its stored answer is read as. */
	record RunKey(CommandKey key, Class<?> type) {
	}

	/**
	 * A run's start: the locked market, the sequences taken for it, from {@code firstSequence} to
	 * {@code lastSequence} (none when {@code firstSequence} is past {@code lastSequence}), and for each key, in the
	 * order given, its stored answer if it has one.
	 */
	record RunOpening(Market market, long firstSequence, long lastSequence, List<Optional<StoredResult<?>>> priors) {

		public RunOpening {
			priors = List.copyOf(priors);
		}

	}

	/**
	 * Opens a run of trading commands in one statement, as {@link #open} opens one: locks the market row if
	 * {@code writerEpoch} still owns it, looks up the stored answer of every key, and takes one command sequence for
	 * each distinct key that has none. A key given twice is looked up once and takes one sequence: its second command
	 * answers from the first. Empty when another writer has claimed the market since.
	 */
	Optional<RunOpening> openRun(UUID simulationId, long writerEpoch, List<RunKey> keys);

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
