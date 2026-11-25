package com.mia_princz.mse.execution.trading.adapter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.apache.avro.SchemaNormalization;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

import com.mia_princz.mse.contracts.events.AggressorSide;
import com.mia_princz.mse.contracts.events.BookLevel;
import com.mia_princz.mse.contracts.events.MarketRules;
import com.mia_princz.mse.contracts.events.MarketSnapshot;
import com.mia_princz.mse.contracts.events.PublicTrade;
import com.mia_princz.mse.contracts.events.TradeExecuted;
import com.mia_princz.mse.contracts.events.VenueState;
import com.mia_princz.mse.contracts.events.WireEvents;
import com.mia_princz.mse.execution.trading.application.BotJob;
import com.mia_princz.mse.execution.trading.application.BotStrategy;
import com.mia_princz.mse.execution.trading.application.CommandKey;
import com.mia_princz.mse.execution.trading.application.ProvisionAccount;
import com.mia_princz.mse.execution.trading.application.TapeTrade;
import com.mia_princz.mse.execution.trading.application.TradingStore;
import com.mia_princz.mse.execution.trading.application.WriteSet;
import com.mia_princz.mse.execution.trading.domain.BookOrder;
import com.mia_princz.mse.execution.trading.domain.DepthLevel;
import com.mia_princz.mse.execution.trading.domain.OrderRequest;
import com.mia_princz.mse.execution.trading.domain.OrderType;
import com.mia_princz.mse.execution.trading.domain.Posting;
import com.mia_princz.mse.execution.trading.domain.Price;
import com.mia_princz.mse.execution.trading.domain.Quantity;
import com.mia_princz.mse.execution.trading.domain.Side;
import com.mia_princz.mse.execution.trading.domain.SettlementPlan;

/** Plain SQL for the trading tables. Every write is checked against its expected row count. */
public final class JdbcTradingStore implements TradingStore {

	private static final String TRADES_TOPIC = "mse.trades.v1";

	/** Stands for cash where SQL needs a non-null asset key; no instrument has this ID. */
	private static final UUID CASH = new UUID(0, 0);

	private static final long TRADE_FINGERPRINT = SchemaNormalization
		.parsingFingerprint64(TradeExecuted.getClassSchema());

	private static final String MARKET_TOPIC = "mse.market.v1";

	private static final long SNAPSHOT_FINGERPRINT = SchemaNormalization
		.parsingFingerprint64(MarketSnapshot.getClassSchema());

	/** Public trades carried in each snapshot, newest first. */

	/** An encoded snapshot must stay under this; a bigger one fails its command instead of being published. */
	static final int SNAPSHOT_BUDGET_BYTES = 128 * 1024;

	/** Customer balances must equal the sum of their ledger postings, in total and in the reserved bucket. */
	static final String LEDGER_AGAINST_BALANCES = """
			WITH ledger AS (
			    SELECT e.account_id, coalesce(e.instrument_id, :cash) AS asset, sum(e.delta) AS total,
			        coalesce(sum(e.delta) FILTER (WHERE e.bucket = 'RESERVED'), 0) AS reserved
			    FROM ledger_entry e JOIN account a ON a.id = e.account_id
			    WHERE a.simulation_id = :simulation AND a.kind <> 'ISSUANCE'
			    GROUP BY 1, 2),
			balances AS (
			    SELECT b.account_id, :cash AS asset, b.total_cents AS total, b.reserved_cents AS reserved
			    FROM cash_balance b JOIN account a ON a.id = b.account_id WHERE a.simulation_id = :simulation
			    UNION ALL
			    SELECT p.account_id, p.instrument_id, p.total_quantity, p.reserved_quantity
			    FROM position p JOIN account a ON a.id = p.account_id WHERE a.simulation_id = :simulation)
			SELECT coalesce(l.account_id, b.account_id) AS account_id, coalesce(l.asset, b.asset) AS asset,
			    coalesce(l.total, 0) AS ledger_total, coalesce(b.total, 0) AS balance_total,
			    coalesce(l.reserved, 0) AS ledger_reserved, coalesce(b.reserved, 0) AS balance_reserved
			FROM ledger l FULL JOIN balances b ON l.account_id = b.account_id AND l.asset = b.asset
			WHERE coalesce(l.total, 0) <> coalesce(b.total, 0) OR coalesce(l.reserved, 0) <> coalesce(b.reserved, 0)
			LIMIT 20""";

	/** The commit-time trigger already enforces this; checking again covers a dropped trigger. */
	static final String UNBALANCED_BATCHES = """
			SELECT e.batch_id FROM ledger_entry e JOIN journal_batch b ON b.id = e.batch_id
			WHERE b.simulation_id = ? GROUP BY e.batch_id, e.instrument_id HAVING sum(e.delta) <> 0 LIMIT 20""";

	/**
	 * A key's stored answers in every simulation, by each simulation's own key index: one probe for each simulation,
	 * however many answers it has stored.
	 */
	private static final String STORED_BY_KEY = """
			SELECT r.route, r.request_hash, r.response_body::text AS body
			FROM simulation s JOIN command_result r
			    ON r.simulation_id = s.id AND r.principal_id = ? AND r.idempotency_key = ?
			""";

	private final JdbcClient jdbc;

	private final JsonMapper json;

	/** The most parameters one statement of a flush binds; PostgreSQL takes at most 65,535. */
	private final int maxParameters;

	public JdbcTradingStore(JdbcClient jdbc, JsonMapper json) {
		this(jdbc, json, 30_000);
	}

	/** {@code maxParameters} smaller than the default makes a flush split its rows sooner, as tests need. */
	public JdbcTradingStore(JdbcClient jdbc, JsonMapper json, int maxParameters) {
		this.jdbc = jdbc;
		this.json = json;
		this.maxParameters = maxParameters;
	}

	@Override
	public boolean hasSimulation() {
		return jdbc.sql("SELECT EXISTS (SELECT 1 FROM simulation)").query(Boolean.class).single();
	}

	@Override
	public long createMarket(UUID simulationId, UUID issuanceAccountId) {
		long epoch = jdbc
			.sql("INSERT INTO simulation (id, state, writer_epoch) VALUES (?, 'SETUP', 1) RETURNING writer_epoch")
			.param(simulationId)
			.query(Long.class)
			.single();
		expectOne(jdbc.sql("""
				INSERT INTO account (id, simulation_id, kind, display_name) VALUES (?, ?, 'ISSUANCE', 'Issuance')""")
			.params(issuanceAccountId, simulationId)
			.update(), "insert issuance account");
		return epoch;
	}

	@Override
	public void startMarket(UUID simulationId, UUID issuanceAccountId, String name, long writerEpoch) {
		expectOne(jdbc.sql("""
				INSERT INTO simulation (id, state, writer_epoch, name, last_command_sequence)
				VALUES (?, 'SETUP', ?, ?, 1)""")
			.params(simulationId, writerEpoch, name)
			.update(), "insert simulation");
		expectOne(jdbc.sql("""
				INSERT INTO account (id, simulation_id, kind, display_name) VALUES (?, ?, 'ISSUANCE', 'Issuance')""")
			.params(issuanceAccountId, simulationId)
			.update(), "insert issuance account");
	}

	@Override
	public Market lockMarket() {
		return jdbc.sql("""
				SELECT id, state, writer_epoch, max_open_orders_per_account, max_open_orders_total, max_fills_per_command
				FROM simulation %s FOR UPDATE""".formatted(CurrentSimulation.FIRST))
			.query((row, index) -> market(row))
			.single();
	}

	@Override
	public UUID currentSimulation() {
		return jdbc.sql("SELECT id FROM simulation " + CurrentSimulation.FIRST).query(UUID.class).single();
	}

	@Override
	public Optional<Market> lockOwnedMarket(UUID simulationId, long writerEpoch) {
		return jdbc.sql("""
				SELECT id, state, writer_epoch, max_open_orders_per_account, max_open_orders_total, max_fills_per_command
				FROM simulation WHERE id = ? AND writer_epoch = ? FOR UPDATE""")
			.params(simulationId, writerEpoch)
			.query((row, index) -> market(row))
			.optional();
	}

	private static Market market(java.sql.ResultSet row) throws java.sql.SQLException {
		return new Market(row.getObject("id", UUID.class), row.getString("state"), row.getLong("writer_epoch"),
				row.getInt("max_open_orders_per_account"), row.getInt("max_open_orders_total"),
				row.getInt("max_fills_per_command"));
	}

	@Override
	public void retireMarket(UUID simulationId, long writerEpoch) {
		expectOne(jdbc.sql("""
				UPDATE simulation SET writer_epoch = 0 WHERE id = ? AND writer_epoch = ? AND state = 'CLOSED'""")
			.params(simulationId, writerEpoch)
			.update(), "retire the closed simulation");
	}

	@Override
	public boolean simulationNameTaken(String name) {
		return jdbc.sql("SELECT EXISTS (SELECT 1 FROM simulation WHERE lower(name) = lower(?))")
			.param(name)
			.query(Boolean.class)
			.single();
	}

	@Override
	public <T> List<StoredResult<T>> storedInAnySimulation(CommandKey key, Class<T> type) {
		return stored(jdbc.sql(STORED_BY_KEY + "ORDER BY s.created_at, s.id")
			.params(key.principalId(), key.idempotencyKey()), key, type);
	}

	@Override
	public <T> List<StoredResult<T>> storedInOtherSimulations(UUID simulationId, CommandKey key, Class<T> type) {
		return stored(jdbc.sql(STORED_BY_KEY + "WHERE s.id <> ? ORDER BY s.created_at, s.id")
			.params(key.principalId(), key.idempotencyKey(), simulationId), key, type);
	}

	private <T> List<StoredResult<T>> stored(JdbcClient.StatementSpec statement, CommandKey key, Class<T> type) {
		return statement
			.query((row, index) -> {
				String route = row.getString("route");
				byte[] hash = row.getBytes("request_hash");
				// Another route's answer need not fit this route's type; the caller refuses it as a conflict.
				return new StoredResult<>(route, hash,
						key.sameRequest(route, hash) ? json.readValue(row.getString("body"), type) : null);
			})
			.list();
	}

	@Override
	public long claimWriterEpoch(UUID simulationId) {
		return jdbc.sql("UPDATE simulation SET writer_epoch = writer_epoch + 1 WHERE id = ? RETURNING writer_epoch")
			.param(simulationId)
			.query(Long.class)
			.single();
	}

	@Override
	public List<BookOrder> activeOrders(UUID instrumentId) {
		return jdbc.sql("""
				SELECT id, account_id, side, bound_cents, working_quantity, accepted_sequence FROM orders
				WHERE instrument_id = ? AND state IN ('OPEN', 'PARTIALLY_FILLED') ORDER BY accepted_sequence""")
			.param(instrumentId)
			.query((row, index) -> new BookOrder(row.getObject("id", UUID.class), row.getObject("account_id", UUID.class),
					Side.valueOf(row.getString("side")), new Price(row.getLong("bound_cents")),
					row.getLong("working_quantity"), row.getLong("accepted_sequence")))
			.list();
	}

	@Override
	public List<String> reconciliationProblems(UUID simulationId) {
		List<String> problems = new ArrayList<>();
		problems.addAll(readingWholeLedger(() -> jdbc.sql(LEDGER_AGAINST_BALANCES)
			.param("simulation", simulationId)
			.param("cash", CASH)
			.query((row, index) -> "Balance of account " + row.getObject("account_id") + " in " + asset(row.getObject("asset", UUID.class))
					+ " is " + row.getLong("balance_total") + " (reserved " + row.getLong("balance_reserved")
					+ ") but the ledger says " + row.getLong("ledger_total") + " (reserved " + row.getLong("ledger_reserved") + ")")
			.list()));
		// Every reserved amount must equal exactly the holds of that account's open orders.
		problems.addAll(jdbc.sql("""
				WITH holds AS (
				    SELECT account_id, CASE WHEN side = 'BUY' THEN :cash ELSE instrument_id END AS asset,
				        sum(CASE WHEN side = 'BUY' THEN bound_cents * working_quantity ELSE working_quantity END) AS held
				    FROM orders WHERE simulation_id = :simulation AND state IN ('OPEN', 'PARTIALLY_FILLED')
				    GROUP BY 1, 2),
				reserved AS (
				    SELECT b.account_id, :cash AS asset, b.reserved_cents AS reserved
				    FROM cash_balance b JOIN account a ON a.id = b.account_id
				    WHERE a.simulation_id = :simulation AND b.reserved_cents <> 0
				    UNION ALL
				    SELECT p.account_id, p.instrument_id, p.reserved_quantity
				    FROM position p JOIN account a ON a.id = p.account_id
				    WHERE a.simulation_id = :simulation AND p.reserved_quantity <> 0)
				SELECT coalesce(h.account_id, r.account_id) AS account_id, coalesce(h.asset, r.asset) AS asset,
				    coalesce(h.held, 0) AS held, coalesce(r.reserved, 0) AS reserved
				FROM holds h FULL JOIN reserved r ON h.account_id = r.account_id AND h.asset = r.asset
				WHERE coalesce(h.held, 0) <> coalesce(r.reserved, 0)
				LIMIT 20""")
			.param("simulation", simulationId)
			.param("cash", CASH)
			.query((row, index) -> "Account " + row.getObject("account_id") + " reserves " + row.getLong("reserved") + " in "
					+ asset(row.getObject("asset", UUID.class)) + " but its open orders hold " + row.getLong("held"))
			.list());
		// The writer numbers trades in memory, so each instrument's counter must name its newest trade.
		problems.addAll(jdbc.sql("""
				SELECT i.symbol, i.last_trade_sequence, coalesce(t.newest, 0) AS newest
				FROM instrument i
				LEFT JOIN (SELECT instrument_id, max(trade_sequence) AS newest FROM trades
				    WHERE simulation_id = :simulation GROUP BY instrument_id) t ON t.instrument_id = i.id
				WHERE i.simulation_id = :simulation AND i.last_trade_sequence <> coalesce(t.newest, 0)""")
			.param("simulation", simulationId)
			.query((row, index) -> "Instrument " + row.getString("symbol") + " has last trade sequence "
					+ row.getLong("last_trade_sequence") + " but its newest trade is " + row.getLong("newest"))
			.list());
		problems.addAll(readingWholeLedger(() -> jdbc.sql(UNBALANCED_BATCHES)
			.param(simulationId)
			.query((row, index) -> "Journal batch " + row.getObject("batch_id") + " does not balance")
			.list()));
		return problems;
	}

	/**
	 * Runs a check that reads the whole ledger with index and bitmap scans turned off, then puts both back as they were.
	 * The ledger's indexes follow random UUIDs, and the planner, which takes the tables to be cached, would rather walk
	 * one, per batch or per account, fetching a page for each entry it finds. On a ledger larger than memory that is
	 * close to one read per entry. Read in order, each table is read once. The settings last until the transaction
	 * ends, so this works inside the startup transaction; outside one, it leaves the planner's choice alone.
	 */
	<T> T readingWholeLedger(Supplier<T> check) {
		String[] before = jdbc.sql("SELECT current_setting('enable_indexscan'), current_setting('enable_bitmapscan')")
			.query((row, index) -> new String[] { row.getString(1), row.getString(2) })
			.single();
		planWith("off", "off");
		T found = check.get();
		planWith(before[0], before[1]);
		return found;
	}

	private void planWith(String indexScan, String bitmapScan) {
		jdbc.sql("SELECT set_config('enable_indexscan', ?, true), set_config('enable_bitmapscan', ?, true)")
			.params(indexScan, bitmapScan)
			.query((row, index) -> row.getString(1))
			.single();
	}

	private static String asset(UUID asset) {
		return CASH.equals(asset) ? "cash" : "instrument " + asset;
	}

	@Override
	public void updateMarketState(UUID simulationId, String state) {
		expectOne(jdbc.sql("UPDATE simulation SET state = ?, opened_at = COALESCE(opened_at, now()) WHERE id = ?")
			.params(state, simulationId)
			.update(), "update market state");
	}

	@Override
	public void closeMarket(UUID simulationId, Instant closedAt) {
		expectOne(jdbc.sql("UPDATE simulation SET state = 'CLOSED', closed_at = ? WHERE id = ?")
			.params(Timestamp.from(closedAt), simulationId)
			.update(), "close market");
	}

	@Override
	public void scheduleClose(UUID simulationId, Instant closesAt) {
		expectOne(jdbc.sql("UPDATE simulation SET closes_at = ? WHERE id = ?")
			.params(Timestamp.from(closesAt), simulationId)
			.update(), "schedule the close");
	}

	@Override
	public Optional<Instant> closesAt(UUID simulationId) {
		return jdbc.sql("SELECT closes_at FROM simulation WHERE id = ?")
			.param(simulationId)
			.query((row, index) -> Optional.ofNullable(row.getTimestamp(1)).map(Timestamp::toInstant))
			.single();
	}

	@Override
	public Optional<UUID> openedBy(UUID simulationId) {
		return jdbc.sql("""
				SELECT actor_user_id FROM audit_log
				WHERE simulation_id = ? AND action = 'MARKET_OPEN' AND outcome = 'APPLIED'""")
			.param(simulationId)
			.query(UUID.class)
			.optional();
	}

	@Override
	public Optional<AppliedClose> appliedClose(UUID simulationId) {
		return jdbc.sql("""
				SELECT actor_user_id, command_sequence FROM audit_log
				WHERE simulation_id = ? AND action IN ('MARKET_CLOSE', 'MARKET_CLOSE_SCHEDULED') AND outcome = 'APPLIED'""")
			.param(simulationId)
			.query((row, index) -> new AppliedClose(row.getObject(1, UUID.class), row.getLong(2)))
			.optional();
	}

	@Override
	public void insertAudit(UUID simulationId, UUID actorUserId, String action, String outcome, UUID correlationId,
			long commandSequence) {
		expectOne(jdbc.sql("""
				INSERT INTO audit_log (simulation_id, actor_user_id, action, outcome, correlation_id, command_sequence)
				VALUES (?, ?, ?, ?, ?, ?)""")
			.params(simulationId, actorUserId, action, outcome, correlationId, commandSequence)
			.update(), "insert audit row");
	}

	@Override
	public boolean symbolTaken(UUID simulationId, String symbol) {
		return jdbc.sql("SELECT EXISTS (SELECT 1 FROM instrument WHERE simulation_id = ? AND symbol = ?)")
			.params(simulationId, symbol)
			.query(Boolean.class)
			.single();
	}

	@Override
	public Optional<String> instrumentSymbol(UUID simulationId, UUID instrumentId) {
		return jdbc.sql("SELECT symbol FROM instrument WHERE simulation_id = ? AND id = ?")
			.params(simulationId, instrumentId)
			.query(String.class)
			.optional();
	}

	@Override
	public Instrument updateInstrument(UUID simulationId, UUID instrumentId, long referencePriceCents,
			String displayName) {
		return jdbc.sql("""
				UPDATE instrument SET reference_price_cents = ?, display_name = ? WHERE simulation_id = ? AND id = ?
				RETURNING id, symbol, display_name, reference_price_cents, tick_cents, lot_size, rule_version, last_trade_sequence""")
			.params(referencePriceCents, displayName, simulationId, instrumentId)
			.query((row, index) -> instrument(row))
			.single();
	}

	@Override
	public InstrumentUsage instrumentUsage(UUID simulationId, UUID instrumentId, int holdersNamed) {
		String holders = """
				WITH holder AS (
				    SELECT account_id FROM initial_allocation WHERE instrument_id = :instrument
				    UNION SELECT account_id FROM position WHERE instrument_id = :instrument)
				""";
		List<String> named = jdbc.sql(holders + """
				SELECT a.display_name FROM holder h JOIN account a ON a.id = h.account_id
				ORDER BY a.display_name, a.id LIMIT :limit""")
			.param("instrument", instrumentId)
			.param("limit", holdersNamed)
			.query(String.class)
			.list();
		return jdbc.sql(holders + """
				SELECT (SELECT count(*) FROM holder) AS holders,
				    (SELECT count(*) FROM orders WHERE simulation_id = :simulation AND instrument_id = :instrument)
				        AS orders,
				    (SELECT count(*) FROM ledger_entry WHERE instrument_id = :instrument)
				        + (SELECT count(*) FROM trades WHERE simulation_id = :simulation AND instrument_id = :instrument)
				        + (SELECT count(*) FROM bot_run_instrument
				           WHERE simulation_id = :simulation AND instrument_id = :instrument) AS others""")
			.param("instrument", instrumentId)
			.param("simulation", simulationId)
			.query((row, index) -> new InstrumentUsage(named, row.getLong("holders"), row.getLong("orders"),
					row.getLong("others")))
			.single();
	}

	@Override
	public void deleteInstrument(UUID simulationId, UUID instrumentId) {
		expectOne(jdbc.sql("DELETE FROM instrument WHERE simulation_id = ? AND id = ?")
			.params(simulationId, instrumentId)
			.update(), "delete instrument");
	}

	@Override
	public boolean hasAccount(UUID simulationId, UUID ownerUserId) {
		return jdbc.sql("SELECT EXISTS (SELECT 1 FROM account WHERE simulation_id = ? AND owner_user_id = ?)")
			.params(simulationId, ownerUserId)
			.query(Boolean.class)
			.single();
	}

	@Override
	public boolean usernameTaken(String username) {
		return jdbc.sql("SELECT EXISTS (SELECT 1 FROM app_user WHERE lower(username) = lower(?))")
			.param(username)
			.query(Boolean.class)
			.single();
	}

	@Override
	public void insertTraderUser(UUID userId, String username, String passwordHash) {
		expectOne(jdbc.sql("INSERT INTO app_user (id, username, password_hash) VALUES (?, ?, ?)")
			.params(userId, username, passwordHash)
			.update(), "insert trader user");
		expectOne(jdbc.sql("INSERT INTO user_role (user_id, role) VALUES (?, 'TRADER')").param(userId).update(),
				"insert trader role");
	}

	@Override
	public void insertTraderAccount(UUID simulationId, UUID accountId, UUID ownerUserId, String displayName) {
		expectOne(jdbc.sql("""
				INSERT INTO account (id, simulation_id, kind, owner_user_id, display_name) VALUES (?, ?, 'TRADER', ?, ?)""")
			.params(accountId, simulationId, ownerUserId, displayName)
			.update(), "insert trader account");
	}

	@Override
	public int botCount(UUID simulationId) {
		return jdbc.sql("SELECT count(*) FROM account WHERE simulation_id = ? AND kind = 'BOT'")
			.param(simulationId)
			.query(Integer.class)
			.single();
	}

	@Override
	public Map<BotStrategy, Integer> taggedBotCounts(UUID simulationId) {
		Map<BotStrategy, Integer> counts = new EnumMap<>(BotStrategy.class);
		jdbc.sql("""
				SELECT bot_strategy, count(*) AS bots FROM account
				WHERE simulation_id = ? AND kind = 'BOT' AND bot_strategy IS NOT NULL GROUP BY bot_strategy""")
			.param(simulationId)
			.query((row) -> {
				counts.put(BotStrategy.valueOf(row.getString("bot_strategy")), row.getInt("bots"));
			});
		return counts;
	}

	@Override
	public Optional<RunningBotRun> runningBotRun(UUID simulationId) {
		return jdbc.sql("SELECT id, ends_at FROM bot_run WHERE simulation_id = ? AND desired_state = 'RUNNING'")
			.param(simulationId)
			.query((row, index) -> {
				Timestamp endsAt = row.getTimestamp("ends_at");
				return new RunningBotRun(row.getObject("id", UUID.class), endsAt == null ? null : endsAt.toInstant());
			})
			.optional();
	}

	@Override
	public Optional<String> botRunState(UUID simulationId, UUID runId) {
		return jdbc.sql("SELECT desired_state FROM bot_run WHERE simulation_id = ? AND id = ?")
			.params(simulationId, runId)
			.query(String.class)
			.optional();
	}

	@Override
	public void insertBotRun(NewBotRun run) {
		expectOne(jdbc.sql("""
				INSERT INTO bot_run (id, simulation_id, desired_state, seed, commands_per_second, max_order_quantity,
				    max_position, started_by, started_at, start_sequence, ends_at)
				VALUES (?, ?, 'RUNNING', ?, ?, ?, ?, ?, ?, ?, ?)""")
			.params(run.id(), run.simulationId(), run.seed(), BigDecimal.valueOf(run.commandsPerSecondHundredths(), 2),
					run.maxOrderQuantity(), run.maxPosition(), run.startedBy(), Timestamp.from(run.startedAt()),
					run.startSequence(), run.endsAt() == null ? null : Timestamp.from(run.endsAt()))
			.update(), "insert bot run");
	}

	@Override
	public void insertBotRunInstrument(UUID runId, UUID simulationId, UUID instrumentId) {
		expectOne(jdbc.sql("""
				INSERT INTO bot_run_instrument (run_id, simulation_id, instrument_id, start_trade_sequence)
				SELECT ?, simulation_id, id, last_trade_sequence FROM instrument WHERE simulation_id = ? AND id = ?""")
			.params(runId, simulationId, instrumentId)
			.update(), "insert bot run instrument");
	}

	@Override
	public int assignOldestBots(UUID runId, UUID simulationId, BotStrategy strategy, int count) {
		return jdbc.sql("""
				INSERT INTO bot_assignment (run_id, account_id, account_kind, strategy, account_created_at)
				SELECT ?, id, 'BOT', bot_strategy, created_at FROM account
				WHERE simulation_id = ? AND kind = 'BOT' AND bot_strategy = ?
				ORDER BY created_at, id LIMIT ?""")
			.params(runId, simulationId, strategy.name(), count)
			.update();
	}

	@Override
	public boolean requestBotRunStop(UUID runId, Instant at, Long stopSequence) {
		return jdbc.sql("""
				UPDATE bot_run SET desired_state = 'STOPPED', stop_requested_at = ?, stop_sequence = ?
				WHERE id = ? AND desired_state = 'RUNNING'""")
			.params(Timestamp.from(at), stopSequence, runId)
			.update() == 1;
	}

	@Override
	public boolean botRunStopping(UUID simulationId, Instant now) {
		Timestamp at = Timestamp.from(now);
		return jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM bot_run WHERE simulation_id = ? AND worker_id IS NOT NULL
				    AND stopped_at IS NULL AND lease_expires_at > ? AND (desired_state = 'STOPPED' OR ends_at <= ?))""")
			.params(simulationId, at, at)
			.query(Boolean.class)
			.single();
	}

	@Override
	public void endUnfinishedBotRuns(UUID simulationId, long endSequence, Instant endedAt) {
		// The runs first, then their instruments: the order the worker's confirmation takes its locks in.
		List<UUID> ended = jdbc.sql("""
				UPDATE bot_run SET end_sequence = ?, ended_by = 'NEXT_START', ended_at = ?
				WHERE simulation_id = ? AND ended_by IS NULL
				RETURNING id""")
			.params(endSequence, Timestamp.from(endedAt), simulationId)
			.query(UUID.class)
			.list();
		if (!ended.isEmpty()) {
			jdbc.sql("""
					UPDATE bot_run_instrument r SET end_trade_sequence = i.last_trade_sequence
					FROM instrument i
					WHERE r.run_id = ANY(?::uuid[]) AND i.simulation_id = r.simulation_id AND i.id = r.instrument_id""")
				.param(ended.stream().map(UUID::toString).toArray(String[]::new))
				.update();
		}
	}

	@Override
	public int endBotRunsAtClose(UUID simulationId, long sequence, Instant at) {
		Timestamp closedAt = Timestamp.from(at);
		// The runs first, then their instruments: the order the worker's confirmation takes its locks in. A run its
		// operator had already asked to stop keeps that stop's sequence. A clock set back since a run started must not
		// make its end fall before its start, which the table refuses.
		List<UUID> ended = jdbc.sql("""
				UPDATE bot_run SET stop_sequence = coalesce(stop_sequence, ?), end_sequence = ?,
				    ended_by = 'MARKET_CLOSE', ended_at = GREATEST(?, started_at)
				WHERE simulation_id = ? AND ended_by IS NULL
				RETURNING id""")
			.params(sequence, sequence, closedAt, simulationId)
			.query(UUID.class)
			.list();
		if (!ended.isEmpty()) {
			jdbc.sql("""
					UPDATE bot_run_instrument r SET end_trade_sequence = i.last_trade_sequence
					FROM instrument i
					WHERE r.run_id = ANY(?::uuid[]) AND i.simulation_id = r.simulation_id AND i.id = r.instrument_id""")
				.param(ended.stream().map(UUID::toString).toArray(String[]::new))
				.update();
		}
		// Then every run no worker has confirmed is recorded as stopped, now: the ones just ended, and any that ended
		// earlier (by the next start, or before windows were kept) and keep that end.
		jdbc.sql("""
				UPDATE bot_run SET desired_state = 'STOPPED', stop_requested_at = coalesce(stop_requested_at, ?),
				    stopped_at = ?
				WHERE simulation_id = ? AND stopped_at IS NULL""")
			.params(closedAt, closedAt, simulationId)
			.update();
		return ended.size();
	}

	/**
	 * One statement for every bot of the command, its arrays sent once, in place of a dozen statements for each bot:
	 * the round trips, not the rows, were nearly all of the cost, and a statement with large arrays costs a round trip
	 * of its own on top (seven such statements measured three times as long as this one). Each table is written by its
	 * own part of the statement; the foreign keys between them are checked once the whole statement has run, and each
	 * genesis batch's balance at the commit, as for any batch. The cash and every holding are one list of assets, cash
	 * first, as one bot's genesis batch lists them.
	 * <p>
	 * The creation times start just after the newest bot of the simulation, read through its index for each strategy,
	 * or at the clock if that is later: the clock alone would give hundreds of bots made by one statement the same
	 * time, and they would then list in id order, not in the order they were made and named.
	 */
	@Override
	public void insertBots(UUID simulationId, long commandSequence, BotStrategy strategy, long cashCents,
			List<ProvisionAccount.Holding> holdings, List<NewBot> bots) {
		if (bots.isEmpty()) {
			return;
		}
		int count = bots.size();
		String[] accounts = new String[count];
		String[] names = new String[count];
		String[] hashes = new String[count];
		HexFormat hex = HexFormat.of();
		for (int i = 0; i < count; i++) {
			NewBot bot = bots.get(i);
			accounts[i] = bot.accountId().toString();
			names[i] = bot.displayName();
			hashes[i] = hex.formatHex(bot.tokenHash());
		}
		String[] instruments = holdings.stream().map((holding) -> holding.instrumentId().toString()).toArray(String[]::new);
		String[] quantities = holdings.stream().map((holding) -> Long.toString(holding.quantity())).toArray(String[]::new);
		int assets = holdings.size() + (cashCents > 0 ? 1 : 0);
		List<Integer> written = jdbc.sql("""
				WITH newest AS (
				    SELECT (SELECT max(created_at) FROM account
				            WHERE simulation_id = ? AND kind = 'BOT' AND bot_strategy = s.strategy) AS at
				    FROM unnest(ARRAY['MARKET_MAKER', 'NOISE', 'MOMENTUM']) AS s(strategy)
				    UNION ALL
				    SELECT max(created_at) FROM account WHERE simulation_id = ? AND kind = 'BOT' AND bot_strategy IS NULL),
				first AS (SELECT GREATEST(clock_timestamp(), max(at) + interval '1 microsecond') AS at FROM newest),
				bot AS MATERIALIZED (
				    SELECT b.account_id, b.name, b.hash, b.n, gen_random_uuid() AS batch_id
				    FROM unnest(?::uuid[], ?::text[], ?::text[]) WITH ORDINALITY AS b(account_id, name, hash, n)),
				asset AS MATERIALIZED (
				    SELECT NULL::uuid AS instrument_id, ?::bigint AS amount, 0::bigint AS k WHERE ?::bigint > 0
				    UNION ALL
				    SELECT * FROM unnest(?::uuid[], ?::bigint[]) WITH ORDINALITY AS h(instrument_id, amount, k)),
				issuance AS (SELECT id FROM account WHERE simulation_id = ? AND kind = 'ISSUANCE'),
				accounts AS (
				    INSERT INTO account (id, simulation_id, kind, display_name, bot_strategy, created_at)
				    SELECT bot.account_id, ?, 'BOT', bot.name, ?, first.at + (bot.n - 1) * interval '1 microsecond'
				    FROM first, bot ORDER BY bot.n
				    RETURNING 1),
				batches AS (
				    INSERT INTO journal_batch (id, simulation_id, cause, command_sequence)
				    SELECT bot.batch_id, ?, 'GENESIS', ? FROM bot ORDER BY bot.n
				    RETURNING 1),
				postings AS (
				    INSERT INTO ledger_entry (batch_id, account_id, instrument_id, bucket, delta)
				    SELECT bot.batch_id, p.account_id, asset.instrument_id, 'AVAILABLE', p.delta
				    FROM bot CROSS JOIN asset CROSS JOIN issuance
				    CROSS JOIN LATERAL (VALUES (1, issuance.id, -asset.amount), (2, bot.account_id, asset.amount))
				        AS p(side, account_id, delta)
				    ORDER BY bot.n, asset.k, p.side
				    RETURNING 1),
				allocations AS (
				    INSERT INTO initial_allocation (account_id, instrument_id, amount, journal_batch_id)
				    SELECT bot.account_id, asset.instrument_id, asset.amount, bot.batch_id
				    FROM bot CROSS JOIN asset ORDER BY bot.n, asset.k
				    RETURNING 1),
				cash AS (
				    INSERT INTO cash_balance (account_id, account_kind, total_cents, reserved_cents)
				    SELECT bot.account_id, 'BOT', ?, 0 FROM bot ORDER BY bot.n
				    RETURNING 1),
				positions AS (
				    INSERT INTO position (account_id, account_kind, instrument_id, total_quantity, reserved_quantity)
				    SELECT bot.account_id, 'BOT', asset.instrument_id, asset.amount, 0
				    FROM bot CROSS JOIN asset WHERE asset.instrument_id IS NOT NULL ORDER BY bot.n, asset.k
				    RETURNING 1),
				tokens AS (
				    INSERT INTO api_token (id, account_id, account_kind, token_hash, scope)
				    SELECT gen_random_uuid(), bot.account_id, 'BOT', decode(bot.hash, 'hex'), 'TRADE' FROM bot ORDER BY bot.n
				    RETURNING 1)
				SELECT unnest(ARRAY[(SELECT count(*) FROM accounts), (SELECT count(*) FROM batches),
				    (SELECT count(*) FROM postings), (SELECT count(*) FROM allocations), (SELECT count(*) FROM cash),
				    (SELECT count(*) FROM positions), (SELECT count(*) FROM tokens), (SELECT count(*) FROM issuance)])""")
			.params(simulationId, simulationId, accounts, names, hashes, cashCents, cashCents, instruments, quantities,
					simulationId, simulationId, strategy.name(), simulationId, commandSequence, cashCents)
			.query(Integer.class)
			.list();
		List<Integer> expected = List.of(count, count, count * assets * 2, count * assets, count,
				count * holdings.size(), count, 1);
		if (!written.equals(expected)) {
			throw new IllegalStateException("Inserting " + count + " bots wrote " + written
					+ " rows (accounts, batches, postings, allocations, cash, positions, tokens, issuance accounts); "
					+ "expected " + expected);
		}
	}

	@Override
	public void insertBotJob(BotJob job) {
		expectOne(jdbc.sql("""
				INSERT INTO bot_job (id, simulation_id, started_by, idempotency_key, state, part_size, market_makers, noise,
				    momentum, created_market_makers, created_noise, created_momentum, parts_done, cash_cents, holdings,
				    started_at, run_started_at, created_at_run_start, last_part_at, finished_at, failure, resumed)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)""")
			.params(job.id(), job.simulationId(), job.startedBy(), job.idempotencyKey(), job.state().name(),
					job.partSize(), job.requested().marketMakers(), job.requested().noise(), job.requested().momentum(),
					job.created().marketMakers(), job.created().noise(), job.created().momentum(), job.partsDone(),
					job.cashCents(), BotJobRows.holdings(job, json), BotJobRows.timestamp(job.startedAt()),
					BotJobRows.timestamp(job.runStartedAt()), job.createdAtRunStart(),
					BotJobRows.timestamp(job.lastPartAt()), BotJobRows.timestamp(job.finishedAt()), job.failure(),
					job.resumed())
			.update(), "insert bot job");
	}

	@Override
	public void updateBotJob(BotJob job) {
		expectOne(jdbc.sql("""
				UPDATE bot_job SET state = ?, created_market_makers = ?, created_noise = ?, created_momentum = ?,
				    parts_done = ?, run_started_at = ?, created_at_run_start = ?, last_part_at = ?, finished_at = ?,
				    failure = ?, resumed = ?
				WHERE id = ? AND simulation_id = ?""")
			.params(job.state().name(), job.created().marketMakers(), job.created().noise(), job.created().momentum(),
					job.partsDone(), BotJobRows.timestamp(job.runStartedAt()), job.createdAtRunStart(),
					BotJobRows.timestamp(job.lastPartAt()), BotJobRows.timestamp(job.finishedAt()), job.failure(),
					job.resumed(), job.id(), job.simulationId())
			.update(), "update bot job");
	}

	@Override
	public Optional<BotJob> botJob(UUID simulationId, UUID jobId) {
		return jdbc.sql("SELECT " + BotJobRows.COLUMNS + " FROM bot_job WHERE simulation_id = ? AND id = ?")
			.params(simulationId, jobId)
			.query((row, index) -> BotJobRows.read(row, json))
			.optional();
	}

	@Override
	public Optional<BotJob> runningBotJob(UUID simulationId) {
		return jdbc.sql("SELECT " + BotJobRows.COLUMNS + " FROM bot_job WHERE simulation_id = ? AND state = 'RUNNING'")
			.param(simulationId)
			.query((row, index) -> BotJobRows.read(row, json))
			.optional();
	}

	@Override
	public boolean isBotAccount(UUID simulationId, UUID accountId) {
		return jdbc.sql("SELECT count(*) FROM account WHERE simulation_id = ? AND id = ? AND kind = 'BOT'")
			.params(simulationId, accountId)
			.query(Integer.class)
			.single() == 1;
	}

	@Override
	public Set<UUID> tradingAccounts(UUID simulationId) {
		return jdbc.sql("SELECT id FROM account WHERE simulation_id = ? AND kind <> 'ISSUANCE'")
			.param(simulationId)
			.query(UUID.class)
			.set();
	}

	@Override
	public boolean tradesIn(UUID simulationId, UUID accountId) {
		return jdbc
			.sql("SELECT EXISTS (SELECT 1 FROM account WHERE simulation_id = ? AND id = ? AND kind <> 'ISSUANCE')")
			.params(simulationId, accountId)
			.query(Boolean.class)
			.single();
	}

	@Override
	public int revokeApiTokens(UUID accountId, Instant at) {
		return jdbc.sql("UPDATE api_token SET revoked_at = ? WHERE account_id = ? AND revoked_at IS NULL")
			.params(Timestamp.from(at), accountId)
			.update();
	}

	@Override
	public int revokeBotTokens(UUID simulationId, Instant at) {
		return jdbc.sql("""
				UPDATE api_token t SET revoked_at = ? FROM account a
				WHERE a.id = t.account_id AND a.simulation_id = ? AND t.revoked_at IS NULL""")
			.params(Timestamp.from(at), simulationId)
			.update();
	}

	@Override
	public List<UUID> botAccounts(UUID simulationId) {
		return jdbc.sql("SELECT id FROM account WHERE simulation_id = ? AND kind = 'BOT'")
			.param(simulationId)
			.query(UUID.class)
			.list();
	}

	@Override
	public void insertGenesis(UUID simulationId, UUID accountId, String accountKind, long commandSequence,
			long cashCents, List<ProvisionAccount.Holding> holdings) {
		UUID issuance = jdbc.sql("SELECT id FROM account WHERE simulation_id = ? AND kind = 'ISSUANCE'")
			.param(simulationId)
			.query(UUID.class)
			.single();
		UUID batch = UUID.randomUUID();
		expectOne(jdbc.sql("""
				INSERT INTO journal_batch (id, simulation_id, cause, command_sequence) VALUES (?, ?, 'GENESIS', ?)""")
			.params(batch, simulationId, commandSequence)
			.update(), "insert genesis batch");
		if (cashCents > 0) {
			allocate(batch, issuance, accountId, null, cashCents);
		}
		expectOne(jdbc.sql("""
				INSERT INTO cash_balance (account_id, account_kind, total_cents, reserved_cents)
				VALUES (?, ?, ?, 0)""")
			.params(accountId, accountKind, cashCents)
			.update(), "insert cash balance");
		for (ProvisionAccount.Holding holding : holdings) {
			allocate(batch, issuance, accountId, holding.instrumentId(), holding.quantity());
			expectOne(jdbc.sql("""
					INSERT INTO position (account_id, account_kind, instrument_id, total_quantity, reserved_quantity)
					VALUES (?, ?, ?, ?, 0)""")
				.params(accountId, accountKind, holding.instrumentId(), holding.quantity())
				.update(), "insert position");
		}
	}

	/** The issuance account gives, the new account receives, and the allocation points at the batch. */
	private void allocate(UUID batch, UUID issuance, UUID accountId, UUID instrumentId, long amount) {
		String posting = "INSERT INTO ledger_entry (batch_id, account_id, instrument_id, bucket, delta) "
				+ "VALUES (?, ?, ?, 'AVAILABLE', ?)";
		expectOne(jdbc.sql(posting).params(batch, issuance, instrumentId, -amount).update(), "insert issuance posting");
		expectOne(jdbc.sql(posting).params(batch, accountId, instrumentId, amount).update(), "insert account posting");
		expectOne(jdbc.sql("""
				INSERT INTO initial_allocation (account_id, instrument_id, amount, journal_batch_id) VALUES (?, ?, ?, ?)""")
			.params(accountId, instrumentId, amount, batch)
			.update(), "insert initial allocation");
	}

	@Override
	public Instrument insertInstrument(UUID simulationId, UUID instrumentId, String symbol, String displayName,
			long referencePriceCents) {
		return jdbc.sql("""
				INSERT INTO instrument (id, simulation_id, symbol, display_name, reference_price_cents)
				VALUES (?, ?, ?, ?, ?)
				RETURNING id, symbol, display_name, reference_price_cents, tick_cents, lot_size, rule_version, last_trade_sequence""")
			.params(instrumentId, simulationId, symbol, displayName, referencePriceCents)
			.query((row, index) -> instrument(row))
			.single();
	}

	@Override
	public List<Instrument> instruments(UUID simulationId) {
		return jdbc.sql("SELECT id, symbol, display_name, reference_price_cents, tick_cents, lot_size, rule_version, last_trade_sequence FROM instrument WHERE simulation_id = ? ORDER BY symbol")
			.param(simulationId)
			.query((row, index) -> instrument(row))
			.list();
	}

	private static Instrument instrument(java.sql.ResultSet row) throws java.sql.SQLException {
		return new Instrument(row.getObject("id", UUID.class), row.getString("symbol"), row.getString("display_name"),
				row.getLong("reference_price_cents"), row.getLong("tick_cents"), row.getLong("lot_size"),
				row.getInt("rule_version"), row.getLong("last_trade_sequence"));
	}

	/** Every balance asked for in one round trip: cash by account, positions by account and instrument. */
	@Override
	public Map<SettlementPlan.BalanceKey, Long> available(java.util.Collection<SettlementPlan.BalanceKey> balances) {
		Map<SettlementPlan.BalanceKey, Long> available = new HashMap<>();
		if (balances.isEmpty()) {
			return available;
		}
		List<String> cash = new ArrayList<>();
		List<String> accounts = new ArrayList<>();
		List<String> instruments = new ArrayList<>();
		for (SettlementPlan.BalanceKey key : new java.util.LinkedHashSet<>(balances)) {
			if (key.instrumentId() == null) {
				cash.add(key.accountId().toString());
			}
			else {
				accounts.add(key.accountId().toString());
				instruments.add(key.instrumentId().toString());
			}
		}
		jdbc.sql("""
				SELECT account_id, NULL::uuid AS instrument_id, total_cents - reserved_cents AS available
				FROM cash_balance WHERE account_id = ANY(?::uuid[])
				UNION ALL
				SELECT p.account_id, p.instrument_id, p.total_quantity - p.reserved_quantity
				FROM position p JOIN unnest(?::uuid[], ?::uuid[]) AS k(account_id, instrument_id)
				    ON p.account_id = k.account_id AND p.instrument_id = k.instrument_id""")
			.params(cash.toArray(String[]::new), accounts.toArray(String[]::new), instruments.toArray(String[]::new))
			.query((java.sql.ResultSet row) -> {
				available.put(new SettlementPlan.BalanceKey(row.getObject(1, UUID.class), row.getObject(2, UUID.class)),
						row.getLong(3));
			});
		return available;
	}

	@Override
	public Map<UUID, Integer> restingOrdersByAccount(UUID simulationId) {
		Map<UUID, Integer> counts = new HashMap<>();
		jdbc.sql("""
				SELECT account_id, count(*) AS resting FROM orders
				WHERE simulation_id = ? AND state IN ('OPEN', 'PARTIALLY_FILLED') GROUP BY account_id""")
			.param(simulationId)
			.query((row, index) -> counts.put(row.getObject("account_id", UUID.class), row.getInt("resting")))
			.list();
		return counts;
	}

	@Override
	public Optional<ExistingOrder> findOrder(UUID simulationId, UUID orderId) {
		return jdbc.sql("""
				SELECT id, account_id, instrument_id, state, working_quantity FROM orders
				WHERE id = ? AND simulation_id = ?""")
			.params(orderId, simulationId)
			.query((row, index) -> new ExistingOrder(row.getObject("id", UUID.class),
					row.getObject("account_id", UUID.class), row.getObject("instrument_id", UUID.class),
					row.getString("state"), row.getLong("working_quantity")))
			.optional();
	}

	/**
	 * One round trip instead of three. The UPDATE takes the row lock and checks the epoch; the sequence rises only when
	 * the key has no stored answer. The lookup shares the statement's snapshot, taken before any wait for the row lock:
	 * only this writer stores answers, and a writer that takes over first commits its new epoch, so no answer for the
	 * key can be committed between that snapshot and the lock.
	 */
	@Override
	public <T> Optional<Opening<T>> open(UUID simulationId, long writerEpoch, CommandKey key, Class<T> type) {
		return jdbc.sql("""
				WITH prior AS (
				    SELECT route, request_hash, response_body::text AS body FROM command_result
				    WHERE simulation_id = ? AND principal_id = ? AND idempotency_key = ?),
				market AS (
				    UPDATE simulation
				    SET last_command_sequence = last_command_sequence
				        + CASE WHEN EXISTS (SELECT 1 FROM prior) THEN 0 ELSE 1 END
				    WHERE id = ? AND writer_epoch = ?
				    RETURNING id, state, writer_epoch, max_open_orders_per_account, max_open_orders_total,
				        max_fills_per_command, last_command_sequence)
				SELECT market.*, prior.route, prior.request_hash, prior.body
				FROM market LEFT JOIN prior ON true""")
			.params(simulationId, key.principalId(), key.idempotencyKey(), simulationId, writerEpoch)
			.query((row, index) -> {
				Market market = new Market(row.getObject("id", UUID.class), row.getString("state"),
						row.getLong("writer_epoch"), row.getInt("max_open_orders_per_account"),
						row.getInt("max_open_orders_total"), row.getInt("max_fills_per_command"));
				String route = row.getString("route");
				Optional<StoredResult<T>> prior = Optional.empty();
				if (route != null) {
					byte[] hash = row.getBytes("request_hash");
					// Another route's answer need not fit this route's type; the caller refuses it as a conflict.
					prior = Optional.of(new StoredResult<>(route, hash,
							key.sameRequest(route, hash) ? json.readValue(row.getString("body"), type) : null));
				}
				return new Opening<>(market, row.getLong("last_command_sequence"), prior);
			})
			.optional();
	}

	/** A stored answer as the run's opening statement read it, before it is read as its command's type. */
	private record StoredRow(String route, byte[] requestHash, String body) {
	}

	/**
	 * The opening of a run, as {@link #open} is the opening of one command, with the same reasoning about the snapshot.
	 * The keys go as two arrays, each distinct key once; the sequence rises by the distinct keys without an answer.
	 */
	@Override
	public Optional<RunOpening> openRun(UUID simulationId, long writerEpoch, List<RunKey> keys) {
		Map<List<UUID>, Integer> distinct = new LinkedHashMap<>();
		for (RunKey key : keys) {
			distinct.putIfAbsent(identity(key.key()), distinct.size());
		}
		String[] principals = distinct.keySet().stream().map((id) -> id.get(0).toString()).toArray(String[]::new);
		String[] idempotencyKeys = distinct.keySet().stream().map((id) -> id.get(1).toString()).toArray(String[]::new);
		Market[] market = new Market[1];
		long[] last = new long[1];
		Map<List<UUID>, StoredRow> stored = new HashMap<>();
		jdbc.sql("""
				WITH run AS (
				    SELECT * FROM unnest(?::uuid[], ?::uuid[]) AS run(principal_id, idempotency_key)),
				prior AS (
				    SELECT c.principal_id, c.idempotency_key, c.route, c.request_hash, c.response_body::text AS body
				    FROM command_result c JOIN run
				        ON c.principal_id = run.principal_id AND c.idempotency_key = run.idempotency_key
				    WHERE c.simulation_id = ?),
				market AS (
				    UPDATE simulation
				    SET last_command_sequence = last_command_sequence + ? - (SELECT count(*) FROM prior)
				    WHERE id = ? AND writer_epoch = ?
				    RETURNING id, state, writer_epoch, max_open_orders_per_account, max_open_orders_total,
				        max_fills_per_command, last_command_sequence)
				SELECT market.*, prior.principal_id, prior.idempotency_key, prior.route, prior.request_hash, prior.body
				FROM market LEFT JOIN prior ON true""")
			.params(principals, idempotencyKeys, simulationId, distinct.size(), simulationId, writerEpoch)
			.query((java.sql.ResultSet row) -> {
				market[0] = new Market(row.getObject("id", UUID.class), row.getString("state"),
						row.getLong("writer_epoch"), row.getInt("max_open_orders_per_account"),
						row.getInt("max_open_orders_total"), row.getInt("max_fills_per_command"));
				last[0] = row.getLong("last_command_sequence");
				String route = row.getString("route");
				if (route != null) {
					stored.put(List.of(row.getObject("principal_id", UUID.class),
							row.getObject("idempotency_key", UUID.class)),
							new StoredRow(route, row.getBytes("request_hash"), row.getString("body")));
				}
			});
		if (market[0] == null) {
			return Optional.empty();
		}
		List<Optional<StoredResult<?>>> priors = new ArrayList<>(keys.size());
		for (RunKey key : keys) {
			StoredRow row = stored.get(identity(key.key()));
			if (row == null) {
				priors.add(Optional.empty());
				continue;
			}
			// Another route's answer need not fit this route's type; the caller refuses it as a conflict.
			Object result = key.key().sameRequest(row.route(), row.requestHash())
					? json.readValue(row.body(), key.type()) : null;
			priors.add(Optional.of(new StoredResult<>(row.route(), row.requestHash(), result)));
		}
		int taken = distinct.size() - stored.size();
		return Optional.of(new RunOpening(market[0], last[0] - taken + 1, last[0], priors));
	}

	private static List<UUID> identity(CommandKey key) {
		return List.of(key.principalId(), key.idempotencyKey());
	}

	@Override
	public List<TapeTrade> recentTrades(UUID instrumentId, int limit) {
		return jdbc.sql("""
				SELECT t.id, t.trade_sequence, t.price_cents, t.quantity, t.executed_at, o.side
				FROM trades t JOIN orders o ON o.id = t.taker_order_id
				WHERE t.simulation_id = (SELECT simulation_id FROM instrument WHERE id = ?) AND t.instrument_id = ?
				ORDER BY t.trade_sequence DESC LIMIT ?""")
			.params(instrumentId, instrumentId, limit)
			.query((row, index) -> new TapeTrade(row.getObject("id", UUID.class), row.getLong("trade_sequence"),
					row.getLong("price_cents"), row.getLong("quantity"), Side.valueOf(row.getString("side")),
					row.getTimestamp("executed_at").toInstant()))
			.list();
	}

	@Override
	public void insertRemovedSnapshot(MarketView view) {
		insertOutbox(List.of(snapshotRow(view, true)));
	}

	@Override
	public void flush(UUID simulationId, WriteSet writes) {
		// The counters first, so one that disagrees halts the writer before any trade is written under a number
		// already taken; then the orders before anything that refers to them.
		advanceTradeSequences(writes.tradeSequences());
		insertOrders(writes.orders());
		fillMakers(writes.makerFills());
		cancelOrders(writes.cancels());
		insertTrades(simulationId, writes.trades());
		// After the trades: a trade's batch refers to it.
		insertBatches(simulationId, writes.batches());
		applyBalances(writes.balances());
		touchAccounts(writes.accounts());
		List<OutboxRow> rows = new ArrayList<>(writes.events().size());
		for (WriteSet.Event event : writes.events()) {
			rows.add(switch (event) {
				case WriteSet.TradeEvent trade -> tradeRow(simulationId, trade);
				case WriteSet.Snapshot snapshot -> snapshotRow(snapshot.view(), false);
			});
		}
		insertOutbox(rows);
		insertResults(simulationId, writes.results());
	}

	/** {@code count} copies of a VALUES row, for one statement that writes or matches every row of a kind. */
	private static String rows(int count, String row) {
		return String.join(", ", java.util.Collections.nCopies(count, row));
	}

	/**
	 * The rows in groups small enough for one statement each: PostgreSQL takes at most 65,535 parameters in one, and a
	 * run of commands with many fills can need more. Usually there is one group.
	 */
	private <T> List<List<T>> chunks(List<T> rows, int parametersPerRow) {
		int size = Math.max(1, maxParameters / parametersPerRow);
		List<List<T>> chunks = new ArrayList<>();
		for (int from = 0; from < rows.size(); from += size) {
			chunks.add(rows.subList(from, Math.min(rows.size(), from + size)));
		}
		return chunks;
	}

	private static void expectRows(int rows, int expected, String what) {
		if (rows != expected) {
			throw new IllegalStateException(what + " affected " + rows + " rows, expected " + expected);
		}
	}

	/** Each instrument's counter moved once, from where the writer believes it is; any other value halts the writer. */
	private void advanceTradeSequences(List<WriteSet.TradeSequence> moves) {
		for (List<WriteSet.TradeSequence> chunk : chunks(moves, 3)) {
			List<Object> params = new ArrayList<>(chunk.size() * 3);
			for (WriteSet.TradeSequence move : chunk) {
				params.addAll(Arrays.asList(move.instrumentId(), move.from(), move.to()));
			}
			List<UUID> moved = jdbc.sql("""
					UPDATE instrument i SET last_trade_sequence = m.last
					FROM (VALUES %s) AS m(id, first, last)
					WHERE i.id = m.id AND i.last_trade_sequence = m.first
					RETURNING i.id""".formatted(rows(chunk.size(), "(?::uuid, ?::bigint, ?::bigint)")))
				.params(params)
				.query((row, index) -> row.getObject(1, UUID.class))
				.list();
			if (moved.size() != chunk.size()) {
				for (WriteSet.TradeSequence move : chunk) {
					if (!moved.contains(move.instrumentId())) {
						throw new OutOfStepException("Instrument " + move.instrumentId() + " was not at trade sequence "
								+ move.from());
					}
				}
			}
		}
	}

	private void insertOrders(List<NewOrder> orders) {
		for (List<NewOrder> chunk : chunks(orders, 15)) {
			List<Object> params = new ArrayList<>(chunk.size() * 15);
			for (NewOrder order : chunk) {
				OrderRequest request = order.request();
				params.addAll(Arrays.asList(order.id(), order.simulationId(), request.accountId(), order.instrumentId(),
						request.side().name(), request.type().name(), request.type() == OrderType.LIMIT ? "GTC" : "IOC",
						request.bound().cents(), request.quantity().shares(), order.filled(), order.working(),
						order.canceled(), order.state().name(), order.reason(), order.acceptedSequence()));
			}
			expectRows(jdbc.sql("""
					INSERT INTO orders (id, simulation_id, account_id, instrument_id, side, type, time_in_force, bound_cents,
					    requested_quantity, filled_quantity, working_quantity, canceled_quantity, state, reason,
					    accepted_sequence)
					VALUES %s""".formatted(rows(chunk.size(),
					"(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::bigint)")))
				.params(params)
				.update(), chunk.size(), "insert orders");
		}
	}

	/**
	 * Each resting order's fills in the transaction at once. The guard is the old one per fill, summed: still resting,
	 * with at least the quantity filled. An order the book matched that the database no longer has resting means the
	 * book is out of step, so the writer halts. The version rises once per command that filled the order.
	 */
	private void fillMakers(List<WriteSet.MakerFill> fills) {
		for (List<WriteSet.MakerFill> chunk : chunks(fills, 3)) {
			List<Object> params = new ArrayList<>(chunk.size() * 3);
			for (WriteSet.MakerFill fill : chunk) {
				params.addAll(Arrays.asList(fill.orderId(), fill.quantity(), fill.fills()));
			}
			int filled = jdbc.sql("""
					UPDATE orders o SET filled_quantity = o.filled_quantity + f.quantity,
					    working_quantity = o.working_quantity - f.quantity,
					    state = CASE WHEN o.working_quantity = f.quantity THEN 'FILLED' ELSE 'PARTIALLY_FILLED' END,
					    version = o.version + f.fills, updated_at = now()
					FROM (VALUES %s) AS f(id, quantity, fills)
					WHERE o.id = f.id AND o.state IN ('OPEN', 'PARTIALLY_FILLED') AND o.working_quantity >= f.quantity"""
				.formatted(rows(chunk.size(), "(?::uuid, ?::bigint, ?::int)")))
				.params(params)
				.update();
			if (filled != chunk.size()) {
				throw new OutOfStepException("Filled " + filled + " of " + chunk.size() + " resting orders the book matched");
			}
		}
	}

	/**
	 * Cancels orders the book held for their owners, after the transaction's fills of them, each guarded by what the
	 * book said: still resting, the owner's, with exactly that quantity working. A row that differs halts the writer.
	 * A market's close cancels through here too, many orders at once.
	 */
	private void cancelOrders(List<WriteSet.Cancel> cancels) {
		for (List<WriteSet.Cancel> chunk : chunks(cancels, 4)) {
			List<Object> params = new ArrayList<>(chunk.size() * 4);
			for (WriteSet.Cancel cancel : chunk) {
				params.addAll(Arrays.asList(cancel.orderId(), cancel.accountId(), cancel.quantity(), cancel.reason()));
			}
			int canceled = jdbc.sql("""
					UPDATE orders o SET canceled_quantity = o.canceled_quantity + c.quantity, working_quantity = 0,
					    state = 'CANCELED', reason = c.reason, version = o.version + 1, updated_at = now()
					FROM (VALUES %s) AS c(id, account_id, quantity, reason)
					WHERE o.id = c.id AND o.account_id = c.account_id AND o.working_quantity = c.quantity
					    AND o.state IN ('OPEN', 'PARTIALLY_FILLED')"""
				.formatted(rows(chunk.size(), "(?::uuid, ?::uuid, ?::bigint, ?::text)")))
				.params(params)
				.update();
			if (canceled != chunk.size()) {
				throw new OutOfStepException("Cancelled " + canceled + " of " + chunk.size()
						+ " orders the book held; the book and the database disagree");
			}
		}
	}

	private void insertTrades(UUID simulationId, List<WriteSet.NewTrade> trades) {
		for (List<WriteSet.NewTrade> chunk : chunks(trades, 12)) {
			List<Object> params = new ArrayList<>(chunk.size() * 12);
			for (WriteSet.NewTrade trade : chunk) {
				params.addAll(Arrays.asList(trade.id(), simulationId, trade.instrumentId(), trade.tradeSequence(),
						trade.makerOrderId(), trade.takerOrderId(), trade.buyerAccountId(), trade.sellerAccountId(),
						trade.priceCents(), trade.quantity(), Timestamp.from(trade.executedAt()), trade.commandSequence()));
			}
			expectRows(jdbc.sql("""
					INSERT INTO trades (id, simulation_id, instrument_id, trade_sequence, maker_order_id, taker_order_id,
					    buyer_account_id, seller_account_id, price_cents, quantity, executed_at, command_sequence)
					VALUES %s""".formatted(rows(chunk.size(), "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")))
				.params(params)
				.update(), chunk.size(), "insert trades");
		}
	}

	/**
	 * Batches and their postings in one statement, a batch never split from its postings. The postings' foreign key to
	 * their batch is checked at the end of the statement, when both inserts have run; whether each batch balances is
	 * checked at commit, as before.
	 */
	private void insertBatches(UUID simulationId, List<WriteSet.Batch> batches) {
		List<WriteSet.Batch> group = new ArrayList<>();
		int parameters = 0;
		for (WriteSet.Batch batch : batches) {
			int needed = 5 + 5 * batch.postings().size();
			if (!group.isEmpty() && parameters + needed > maxParameters) {
				insertBatchGroup(simulationId, group);
				group = new ArrayList<>();
				parameters = 0;
			}
			group.add(batch);
			parameters += needed;
		}
		if (!group.isEmpty()) {
			insertBatchGroup(simulationId, group);
		}
	}

	private void insertBatchGroup(UUID simulationId, List<WriteSet.Batch> batches) {
		List<Object> params = new ArrayList<>();
		int postings = 0;
		for (WriteSet.Batch batch : batches) {
			params.addAll(Arrays.asList(batch.id(), simulationId, batch.cause().name(), batch.commandSequence(),
					batch.tradeId()));
		}
		for (WriteSet.Batch batch : batches) {
			for (Posting posting : batch.postings()) {
				params.addAll(Arrays.asList(batch.id(), posting.accountId(), posting.instrumentId(),
						posting.bucket().name(), posting.delta()));
				postings++;
			}
		}
		int expectedPostings = postings;
		long[] written = jdbc.sql("""
				WITH batch AS (
				    INSERT INTO journal_batch (id, simulation_id, cause, command_sequence, trade_id) VALUES %s RETURNING 1),
				entry AS (
				    INSERT INTO ledger_entry (batch_id, account_id, instrument_id, bucket, delta) VALUES %s RETURNING 1)
				SELECT (SELECT count(*) FROM batch), (SELECT count(*) FROM entry)"""
			.formatted(rows(batches.size(), "(?, ?, ?, ?, ?)"), rows(expectedPostings, "(?, ?, ?, ?, ?)")))
			.params(params)
			.query((row, index) -> new long[] { row.getLong(1), row.getLong(2) })
			.single();
		expectRows((int) written[0], batches.size(), "insert journal batches");
		expectRows((int) written[1], expectedPostings, "insert ledger entries");
	}

	/**
	 * Every cash and position change, cash and positions together in one statement that returns the rows it found
	 * (more statements only for more rows than one takes, each half sized so the two fit). A balance with no row yet
	 * gets one, as the first change to it.
	 */
	private void applyBalances(Map<SettlementPlan.BalanceKey, SettlementPlan.BalanceChange> changes) {
		List<Map.Entry<SettlementPlan.BalanceKey, SettlementPlan.BalanceChange>> cash = new ArrayList<>();
		List<Map.Entry<SettlementPlan.BalanceKey, SettlementPlan.BalanceChange>> positions = new ArrayList<>();
		for (Map.Entry<SettlementPlan.BalanceKey, SettlementPlan.BalanceChange> change : changes.entrySet()) {
			(change.getKey().instrumentId() == null ? cash : positions).add(change);
		}
		List<List<Map.Entry<SettlementPlan.BalanceKey, SettlementPlan.BalanceChange>>> cashChunks = chunks(cash, 6);
		List<List<Map.Entry<SettlementPlan.BalanceKey, SettlementPlan.BalanceChange>>> positionChunks = chunks(positions,
				8);
		java.util.Set<SettlementPlan.BalanceKey> found = new java.util.HashSet<>();
		for (int i = 0; i < Math.max(cashChunks.size(), positionChunks.size()); i++) {
			List<String> parts = new ArrayList<>();
			List<String> selects = new ArrayList<>();
			List<Object> params = new ArrayList<>();
			if (i < cashChunks.size()) {
				List<Map.Entry<SettlementPlan.BalanceKey, SettlementPlan.BalanceChange>> chunk = cashChunks.get(i);
				for (Map.Entry<SettlementPlan.BalanceKey, SettlementPlan.BalanceChange> change : chunk) {
					params.addAll(Arrays.asList(change.getKey().accountId(), change.getValue().totalDelta(),
							change.getValue().reservedDelta()));
				}
				parts.add("""
						cash AS (
						    UPDATE cash_balance b SET total_cents = b.total_cents + u.total,
						        reserved_cents = b.reserved_cents + u.reserved
						    FROM (VALUES %s) AS u(account_id, total, reserved)
						    WHERE b.account_id = u.account_id
						    RETURNING b.account_id, NULL::uuid AS instrument_id)"""
					.formatted(rows(chunk.size(), "(?::uuid, ?::bigint, ?::bigint)")));
				selects.add("SELECT account_id, instrument_id FROM cash");
			}
			if (i < positionChunks.size()) {
				List<Map.Entry<SettlementPlan.BalanceKey, SettlementPlan.BalanceChange>> chunk = positionChunks.get(i);
				for (Map.Entry<SettlementPlan.BalanceKey, SettlementPlan.BalanceChange> change : chunk) {
					params.addAll(Arrays.asList(change.getKey().accountId(), change.getKey().instrumentId(),
							change.getValue().totalDelta(), change.getValue().reservedDelta()));
				}
				parts.add("""
						held AS (
						    UPDATE position b SET total_quantity = b.total_quantity + u.total,
						        reserved_quantity = b.reserved_quantity + u.reserved
						    FROM (VALUES %s) AS u(account_id, instrument_id, total, reserved)
						    WHERE b.account_id = u.account_id AND b.instrument_id = u.instrument_id
						    RETURNING b.account_id, b.instrument_id)"""
					.formatted(rows(chunk.size(), "(?::uuid, ?::uuid, ?::bigint, ?::bigint)")));
				selects.add("SELECT account_id, instrument_id FROM held");
			}
			found.addAll(jdbc.sql("WITH " + String.join(", ", parts) + " " + String.join(" UNION ALL ", selects))
				.params(params)
				.query((row, index) -> new SettlementPlan.BalanceKey(row.getObject("account_id", UUID.class),
						row.getObject("instrument_id", UUID.class)))
				.list());
		}
		for (Map.Entry<SettlementPlan.BalanceKey, SettlementPlan.BalanceChange> change : changes.entrySet()) {
			if (!found.contains(change.getKey())) {
				insertBalance(change.getKey(), change.getValue());
			}
		}
	}

	private void insertBalance(SettlementPlan.BalanceKey key, SettlementPlan.BalanceChange change) {
		if (key.instrumentId() == null) {
			expectOne(jdbc.sql("""
					INSERT INTO cash_balance (account_id, account_kind, total_cents, reserved_cents)
					SELECT id, kind, ?, ? FROM account WHERE id = ?""")
				.params(change.totalDelta(), change.reservedDelta(), key.accountId())
				.update(), "insert cash balance");
			return;
		}
		expectOne(jdbc.sql("""
				INSERT INTO position (account_id, account_kind, instrument_id, total_quantity, reserved_quantity)
				SELECT id, kind, ?, ?, ? FROM account WHERE id = ?""")
			.params(key.instrumentId(), change.totalDelta(), change.reservedDelta(), key.accountId())
			.update(), "insert position");
	}

	/** Each account's version rises once for each command that changed it. */
	private void touchAccounts(Map<UUID, Integer> accounts) {
		List<Map.Entry<UUID, Integer>> all = new ArrayList<>(accounts.entrySet());
		for (List<Map.Entry<UUID, Integer>> chunk : chunks(all, 2)) {
			List<Object> params = new ArrayList<>(chunk.size() * 2);
			for (Map.Entry<UUID, Integer> account : chunk) {
				params.add(account.getKey());
				params.add(account.getValue());
			}
			expectRows(jdbc.sql("""
					UPDATE account a SET version = a.version + v.commands
					FROM (VALUES %s) AS v(id, commands) WHERE a.id = v.id"""
				.formatted(rows(chunk.size(), "(?::uuid, ?::int)")))
				.params(params)
				.update(), chunk.size(), "bump account versions");
		}
	}

	/** One outbox row, encoded, with the sequence of the command that wrote it. */
	private record OutboxRow(UUID eventId, String topic, String key, long fingerprint, byte[] payload,
			long commandSequence) {
	}

	/** Outbox rows; their ids rise in the order given, the order the relay publishes them in. */
	private void insertOutbox(List<OutboxRow> rows) {
		for (List<OutboxRow> chunk : chunks(rows, 6)) {
			List<Object> params = new ArrayList<>(chunk.size() * 6);
			for (OutboxRow row : chunk) {
				params.addAll(Arrays.asList(row.eventId(), row.topic(), row.key(), row.fingerprint(), row.payload(),
						row.commandSequence()));
			}
			expectRows(jdbc.sql("""
					INSERT INTO outbox (event_id, topic, event_key, schema_fingerprint, payload, command_sequence)
					VALUES %s""".formatted(rows(chunk.size(), "(?, ?, ?, ?, ?, ?)")))
				.params(params)
				.update(), chunk.size(), "insert outbox events");
		}
	}

	/** Each command's first answer, with the request it answered, so a retry with the same key gets it back. */
	private void insertResults(UUID simulationId, List<WriteSet.Result> results) {
		for (List<WriteSet.Result> chunk : chunks(results, 8)) {
			List<Object> params = new ArrayList<>(chunk.size() * 8);
			for (WriteSet.Result result : chunk) {
				CommandKey key = result.key();
				params.addAll(Arrays.asList(simulationId, key.principalId(), key.idempotencyKey(), key.route(),
						key.requestHash(), result.httpStatus(), json.writeValueAsString(result.result()),
						result.commandSequence()));
			}
			expectRows(jdbc.sql("""
					INSERT INTO command_result (simulation_id, principal_id, idempotency_key, route, request_hash,
					    response_status, response_body, command_sequence)
					VALUES %s""".formatted(rows(chunk.size(), "(?, ?, ?, ?, ?, ?, ?::jsonb, ?)")))
				.params(params)
				.update(), chunk.size(), "insert command results");
		}
	}

	private OutboxRow tradeRow(UUID simulationId, WriteSet.TradeEvent event) {
		WriteSet.NewTrade trade = event.trade();
		TradeExecuted executed = TradeExecuted.newBuilder()
			.setEventId(UUID.randomUUID())
			.setSimulationId(simulationId)
			.setInstrumentId(trade.instrumentId())
			.setSymbol(event.symbol())
			.setTradeId(trade.id())
			.setTradeSequence(trade.tradeSequence())
			.setCommandSequence(trade.commandSequence())
			.setExecutedAt(trade.executedAt())
			.setPriceCents(trade.priceCents())
			.setQuantity(trade.quantity())
			.build();
		try {
			return new OutboxRow(executed.getEventId(), TRADES_TOPIC, simulationId + "|" + trade.instrumentId(),
					TRADE_FINGERPRINT, WireEvents.encode(executed), trade.commandSequence());
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private OutboxRow snapshotRow(MarketView view, boolean removed) {
		Instrument instrument = view.instrument();
		MarketSnapshot.Builder snapshot = MarketSnapshot.newBuilder()
			.setSymbol(instrument.symbol())
			.setDisplayName(instrument.displayName())
			.setLastTradeSequence(instrument.lastTradeSequence())
			.setRules(new MarketRules(instrument.ruleVersion(), instrument.referencePriceCents(), instrument.tickCents(),
					instrument.lotSize(), Quantity.MIN_SHARES, Quantity.MAX_SHARES, Price.MAX_CENTS,
					view.market().maxOpenOrdersPerAccount(), view.market().maxFillsPerCommand()));
		List<PublicTrade> recent = view.recentTrades()
			.stream()
			.map((trade) -> new PublicTrade(trade.tradeId(), trade.tradeSequence(), trade.priceCents(), trade.quantity(),
					AggressorSide.valueOf(trade.aggressor().name()), trade.executedAt()))
			.toList();
		MarketSnapshot event = snapshot.setEventId(UUID.randomUUID())
			.setSimulationId(view.simulationId())
			.setInstrumentId(instrument.id())
			.setVenueState(VenueState.valueOf(view.market().state()))
			.setBids(levels(view.bids()))
			.setAsks(levels(view.asks()))
			.setRecentTrades(recent)
			.setLastTrade(recent.isEmpty() ? null : recent.getFirst())
			.setSourceCommandSequence(view.commandSequence())
			.setGeneratedAt(view.generatedAt())
			.setRemoved(removed)
			.build();
		byte[] payload;
		try {
			payload = WireEvents.encode(event);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		if (payload.length > SNAPSHOT_BUDGET_BYTES) {
			throw new IllegalStateException("Market snapshot for " + event.getSymbol() + " is " + payload.length
					+ " bytes, over its " + SNAPSHOT_BUDGET_BYTES + "-byte budget");
		}
		return new OutboxRow(event.getEventId(), MARKET_TOPIC, view.simulationId() + "|" + instrument.id(),
				SNAPSHOT_FINGERPRINT, payload, view.commandSequence());
	}

	private static List<BookLevel> levels(List<DepthLevel> depth) {
		return depth.stream()
			.map((level) -> new BookLevel(level.price().cents(), level.quantity(), level.orderCount()))
			.toList();
	}

	private static void expectOne(int rows, String what) {
		if (rows != 1) {
			throw new IllegalStateException(what + " affected " + rows + " rows, expected 1");
		}
	}

}
