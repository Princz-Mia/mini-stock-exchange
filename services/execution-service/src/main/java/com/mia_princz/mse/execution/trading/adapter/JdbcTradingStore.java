package com.mia_princz.mse.execution.trading.adapter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
import com.mia_princz.mse.execution.trading.application.CommandKey;
import com.mia_princz.mse.execution.trading.application.ProvisionAccount;
import com.mia_princz.mse.execution.trading.application.TapeTrade;
import com.mia_princz.mse.execution.trading.application.TradingStore;
import com.mia_princz.mse.execution.trading.domain.BookOrder;
import com.mia_princz.mse.execution.trading.domain.DepthLevel;
import com.mia_princz.mse.execution.trading.domain.Fill;
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

	private final JdbcClient jdbc;

	private final JsonMapper json;

	public JdbcTradingStore(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
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
	public Market lockMarket() {
		return jdbc.sql("""
				SELECT id, state, writer_epoch, max_open_orders_per_account, max_open_orders_total, max_fills_per_command
				FROM simulation FOR UPDATE""")
			.query((row, index) -> new Market(row.getObject("id", UUID.class), row.getString("state"),
					row.getLong("writer_epoch"), row.getInt("max_open_orders_per_account"),
					row.getInt("max_open_orders_total"), row.getInt("max_fills_per_command")))
			.single();
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
		// Customer balances must equal the sum of their ledger postings, in total and in the reserved bucket.
		problems.addAll(jdbc.sql("""
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
				LIMIT 20""")
			.param("simulation", simulationId)
			.param("cash", CASH)
			.query((row, index) -> "Balance of account " + row.getObject("account_id") + " in " + asset(row.getObject("asset", UUID.class))
					+ " is " + row.getLong("balance_total") + " (reserved " + row.getLong("balance_reserved")
					+ ") but the ledger says " + row.getLong("ledger_total") + " (reserved " + row.getLong("ledger_reserved") + ")")
			.list());
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
		// The commit-time trigger already enforces this; checking again costs little and covers a dropped trigger.
		problems.addAll(jdbc.sql("""
				SELECT e.batch_id FROM ledger_entry e JOIN journal_batch b ON b.id = e.batch_id
				WHERE b.simulation_id = ? GROUP BY e.batch_id, e.instrument_id HAVING sum(e.delta) <> 0 LIMIT 20""")
			.param(simulationId)
			.query((row, index) -> "Journal batch " + row.getObject("batch_id") + " does not balance")
			.list());
		return problems;
	}

	private static String asset(UUID asset) {
		return CASH.equals(asset) ? "cash" : "instrument " + asset;
	}

	@Override
	public long nextCommandSequence(UUID simulationId) {
		return jdbc.sql("UPDATE simulation SET last_command_sequence = last_command_sequence + 1 WHERE id = ? "
				+ "RETURNING last_command_sequence")
			.param(simulationId)
			.query(Long.class)
			.single();
	}

	@Override
	public void updateMarketState(UUID simulationId, String state) {
		expectOne(jdbc.sql("UPDATE simulation SET state = ?, opened_at = COALESCE(opened_at, now()) WHERE id = ?")
			.params(state, simulationId)
			.update(), "update market state");
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
	public void updateInstrument(UUID simulationId, UUID instrumentId, long referencePriceCents, String displayName) {
		expectOne(jdbc.sql("""
				UPDATE instrument SET reference_price_cents = ?, display_name = ? WHERE simulation_id = ? AND id = ?""")
			.params(referencePriceCents, displayName, simulationId, instrumentId)
			.update(), "update instrument");
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
	public List<UUID> botAccountIds(UUID simulationId) {
		return jdbc.sql("SELECT id FROM account WHERE simulation_id = ? AND kind = 'BOT' ORDER BY created_at, id")
			.param(simulationId)
			.query(UUID.class)
			.list();
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
		expectOne(jdbc.sql("INSERT INTO bot_run_instrument (run_id, simulation_id, instrument_id) VALUES (?, ?, ?)")
			.params(runId, simulationId, instrumentId)
			.update(), "insert bot run instrument");
	}

	@Override
	public void insertBotAssignment(UUID runId, UUID accountId, String strategy) {
		expectOne(jdbc.sql("""
				INSERT INTO bot_assignment (run_id, account_id, account_kind, strategy) VALUES (?, ?, 'BOT', ?)""")
			.params(runId, accountId, strategy)
			.update(), "insert bot assignment");
	}

	@Override
	public void requestBotRunStop(UUID runId, Instant at) {
		expectOne(jdbc.sql("""
				UPDATE bot_run SET desired_state = 'STOPPED', stop_requested_at = ?
				WHERE id = ? AND desired_state = 'RUNNING'""")
			.params(Timestamp.from(at), runId)
			.update(), "request bot run stop");
	}

	@Override
	public void insertBotAccount(UUID simulationId, UUID accountId, String displayName) {
		// clock_timestamp, not the transaction's now(): bots made by one command then list in the order they were made.
		expectOne(jdbc.sql("""
				INSERT INTO account (id, simulation_id, kind, display_name, created_at)
				VALUES (?, ?, 'BOT', ?, clock_timestamp())""")
			.params(accountId, simulationId, displayName)
			.update(), "insert bot account");
	}

	@Override
	public void insertApiToken(UUID tokenId, UUID accountId, byte[] tokenHash) {
		expectOne(jdbc.sql("""
				INSERT INTO api_token (id, account_id, account_kind, token_hash, scope) VALUES (?, ?, 'BOT', ?, 'TRADE')""")
			.params(tokenId, accountId, tokenHash)
			.update(), "insert api token");
	}

	@Override
	public boolean isBotAccount(UUID simulationId, UUID accountId) {
		return jdbc.sql("SELECT count(*) FROM account WHERE simulation_id = ? AND id = ? AND kind = 'BOT'")
			.params(simulationId, accountId)
			.query(Integer.class)
			.single() == 1;
	}

	@Override
	public int revokeApiTokens(UUID accountId, Instant at) {
		return jdbc.sql("UPDATE api_token SET revoked_at = ? WHERE account_id = ? AND revoked_at IS NULL")
			.params(Timestamp.from(at), accountId)
			.update();
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
	public void insertInstrument(UUID simulationId, UUID instrumentId, String symbol, String displayName,
			long referencePriceCents) {
		expectOne(jdbc.sql("""
				INSERT INTO instrument (id, simulation_id, symbol, display_name, reference_price_cents)
				VALUES (?, ?, ?, ?, ?)""")
			.params(instrumentId, simulationId, symbol, displayName, referencePriceCents)
			.update(), "insert instrument");
	}

	@Override
	public List<Instrument> instruments(UUID simulationId) {
		return jdbc.sql("SELECT id, symbol FROM instrument WHERE simulation_id = ? ORDER BY symbol")
			.param(simulationId)
			.query((row, index) -> new Instrument(row.getObject("id", UUID.class), row.getString("symbol")))
			.list();
	}

	@Override
	public Available available(UUID accountId, UUID instrumentId) {
		long cash = jdbc.sql("SELECT total_cents - reserved_cents FROM cash_balance WHERE account_id = ?")
			.param(accountId)
			.query(Long.class)
			.optional()
			.orElse(0L);
		long shares = jdbc
			.sql("SELECT total_quantity - reserved_quantity FROM position WHERE account_id = ? AND instrument_id = ?")
			.params(accountId, instrumentId)
			.query(Long.class)
			.optional()
			.orElse(0L);
		return new Available(cash, shares);
	}

	@Override
	public int openOrders(UUID accountId) {
		return jdbc.sql("SELECT count(*) FROM orders WHERE account_id = ? AND state IN ('OPEN', 'PARTIALLY_FILLED')")
			.param(accountId)
			.query(Integer.class)
			.single();
	}

	@Override
	public int openOrdersTotal(UUID simulationId) {
		return jdbc
			.sql("SELECT count(*) FROM orders WHERE simulation_id = ? AND state IN ('OPEN', 'PARTIALLY_FILLED')")
			.param(simulationId)
			.query(Integer.class)
			.single();
	}

	@Override
	public void insertOrder(NewOrder order) {
		OrderRequest request = order.request();
		expectOne(jdbc.sql("""
				INSERT INTO orders (id, simulation_id, account_id, instrument_id, side, type, time_in_force, bound_cents,
				    requested_quantity, filled_quantity, working_quantity, canceled_quantity, state, reason, accepted_sequence)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
			.params(order.id(), order.simulationId(), request.accountId(), order.instrumentId(), request.side().name(),
					request.type().name(),
					request.type() == OrderType.LIMIT ? "GTC" : "IOC",
					request.bound().cents(), request.quantity().shares(), order.filled(), order.working(),
					order.canceled(), order.state().name(), order.reason(), order.acceptedSequence())
			.update(), "insert order");
	}

	@Override
	public void recordMakerFill(UUID makerOrderId, long quantity) {
		expectOne(jdbc.sql("""
				UPDATE orders SET filled_quantity = filled_quantity + :quantity,
				    working_quantity = working_quantity - :quantity,
				    state = CASE WHEN working_quantity = :quantity THEN 'FILLED' ELSE 'PARTIALLY_FILLED' END,
				    version = version + 1, updated_at = now()
				WHERE id = :id AND state IN ('OPEN', 'PARTIALLY_FILLED') AND working_quantity >= :quantity""")
			.param("quantity", quantity)
			.param("id", makerOrderId)
			.update(), "fill maker " + makerOrderId);
	}

	@Override
	public Optional<ExistingOrder> lockOrder(UUID orderId) {
		return jdbc.sql("SELECT id, account_id, instrument_id, state, working_quantity FROM orders WHERE id = ? FOR UPDATE")
			.param(orderId)
			.query((row, index) -> new ExistingOrder(row.getObject("id", UUID.class),
					row.getObject("account_id", UUID.class), row.getObject("instrument_id", UUID.class),
					row.getString("state"), row.getLong("working_quantity")))
			.optional();
	}

	@Override
	public void cancelOrder(UUID orderId, long quantity, String reason) {
		expectOne(jdbc.sql("""
				UPDATE orders SET canceled_quantity = canceled_quantity + :quantity, working_quantity = 0,
				    state = 'CANCELED', reason = :reason, version = version + 1, updated_at = now()
				WHERE id = :id AND working_quantity = :quantity AND state IN ('OPEN', 'PARTIALLY_FILLED')""")
			.param("quantity", quantity)
			.param("reason", reason)
			.param("id", orderId)
			.update(), "cancel order " + orderId);
	}

	@Override
	public Trade insertTrade(UUID simulationId, UUID instrumentId, OrderRequest taker, Fill fill, Instant executedAt,
			long commandSequence) {
		long tradeSequence = jdbc
			.sql("UPDATE instrument SET last_trade_sequence = last_trade_sequence + 1 WHERE id = ? "
					+ "RETURNING last_trade_sequence")
			.param(instrumentId)
			.query(Long.class)
			.single();
		boolean takerBuys = taker.side() == Side.BUY;
		UUID id = UUID.randomUUID();
		expectOne(jdbc.sql("""
				INSERT INTO trades (id, simulation_id, instrument_id, trade_sequence, maker_order_id, taker_order_id,
				    buyer_account_id, seller_account_id, price_cents, quantity, executed_at, command_sequence)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
			.params(id, simulationId, instrumentId, tradeSequence, fill.makerOrderId(), taker.orderId(),
					takerBuys ? taker.accountId() : fill.makerAccountId(),
					takerBuys ? fill.makerAccountId() : taker.accountId(), fill.price().cents(), fill.quantity(),
					Timestamp.from(executedAt), commandSequence)
			.update(), "insert trade");
		return new Trade(id, tradeSequence);
	}

	@Override
	public void insertBatch(UUID simulationId, SettlementPlan.Cause cause, UUID tradeId, long commandSequence,
			List<Posting> postings) {
		UUID batch = UUID.randomUUID();
		expectOne(jdbc
			.sql("INSERT INTO journal_batch (id, simulation_id, cause, command_sequence, trade_id) VALUES (?, ?, ?, ?, ?)")
			.params(batch, simulationId, cause.name(), commandSequence, tradeId)
			.update(), "insert journal batch");
		for (Posting posting : postings) {
			expectOne(jdbc.sql(
					"INSERT INTO ledger_entry (batch_id, account_id, instrument_id, bucket, delta) VALUES (?, ?, ?, ?, ?)")
				.params(batch, posting.accountId(), posting.instrumentId(), posting.bucket().name(), posting.delta())
				.update(), "insert ledger entry");
		}
	}

	@Override
	public void applyBalance(SettlementPlan.BalanceKey key, SettlementPlan.BalanceChange change) {
		int updated;
		if (key.instrumentId() == null) {
			updated = jdbc.sql("""
					UPDATE cash_balance SET total_cents = total_cents + ?, reserved_cents = reserved_cents + ?
					WHERE account_id = ?""")
				.params(change.totalDelta(), change.reservedDelta(), key.accountId())
				.update();
			if (updated == 0) {
				expectOne(jdbc.sql("""
						INSERT INTO cash_balance (account_id, account_kind, total_cents, reserved_cents)
						SELECT id, kind, ?, ? FROM account WHERE id = ?""")
					.params(change.totalDelta(), change.reservedDelta(), key.accountId())
					.update(), "insert cash balance");
			}
			return;
		}
		updated = jdbc.sql("""
				UPDATE position SET total_quantity = total_quantity + ?, reserved_quantity = reserved_quantity + ?
				WHERE account_id = ? AND instrument_id = ?""")
			.params(change.totalDelta(), change.reservedDelta(), key.accountId(), key.instrumentId())
			.update();
		if (updated == 0) {
			expectOne(jdbc.sql("""
					INSERT INTO position (account_id, account_kind, instrument_id, total_quantity, reserved_quantity)
					SELECT id, kind, ?, ?, ? FROM account WHERE id = ?""")
				.params(key.instrumentId(), change.totalDelta(), change.reservedDelta(), key.accountId())
				.update(), "insert position");
		}
	}

	@Override
	public void touchAccounts(Collection<UUID> accountIds) {
		for (UUID account : accountIds) {
			expectOne(jdbc.sql("UPDATE account SET version = version + 1 WHERE id = ?").param(account).update(),
					"bump account version");
		}
	}

	@Override
	public <T> Optional<StoredResult<T>> findResult(UUID simulationId, CommandKey key, Class<T> type) {
		return jdbc.sql("""
				SELECT route, request_hash, response_body::text AS body FROM command_result
				WHERE simulation_id = ? AND principal_id = ? AND idempotency_key = ?""")
			.params(simulationId, key.principalId(), key.idempotencyKey())
			.query((row, index) -> {
				String route = row.getString("route");
				byte[] hash = row.getBytes("request_hash");
				// Another route's answer need not fit this route's type; the caller refuses it as a conflict.
				return new StoredResult<>(route, hash,
						key.sameRequest(route, hash) ? json.readValue(row.getString("body"), type) : null);
			})
			.optional();
	}

	@Override
	public void insertResult(UUID simulationId, CommandKey key, int status, Object result, long commandSequence) {
		expectOne(jdbc.sql("""
				INSERT INTO command_result (simulation_id, principal_id, idempotency_key, route, request_hash,
				    response_status, response_body, command_sequence)
				VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)""")
			.params(simulationId, key.principalId(), key.idempotencyKey(), key.route(), key.requestHash(), status,
					json.writeValueAsString(result), commandSequence)
			.update(), "insert command result");
	}

	@Override
	public void insertTradeEvent(UUID simulationId, UUID instrumentId, String symbol, Trade trade, Fill fill,
			Instant executedAt, long commandSequence) {
		TradeExecuted event = TradeExecuted.newBuilder()
			.setEventId(UUID.randomUUID())
			.setSimulationId(simulationId)
			.setInstrumentId(instrumentId)
			.setSymbol(symbol)
			.setTradeId(trade.id())
			.setTradeSequence(trade.tradeSequence())
			.setCommandSequence(commandSequence)
			.setExecutedAt(executedAt)
			.setPriceCents(fill.price().cents())
			.setQuantity(fill.quantity())
			.build();
		byte[] payload;
		try {
			payload = WireEvents.encode(event);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		expectOne(jdbc.sql("""
				INSERT INTO outbox (event_id, topic, event_key, schema_fingerprint, payload, command_sequence)
				VALUES (?, ?, ?, ?, ?, ?)""")
			.params(event.getEventId(), TRADES_TOPIC, simulationId + "|" + instrumentId, TRADE_FINGERPRINT, payload,
					commandSequence)
			.update(), "insert trade event");
	}

	@Override
	public List<TapeTrade> recentTrades(UUID instrumentId, int limit) {
		return jdbc.sql("""
				SELECT t.id, t.trade_sequence, t.price_cents, t.quantity, t.executed_at, o.side
				FROM trades t JOIN orders o ON o.id = t.taker_order_id
				WHERE t.instrument_id = ?
				ORDER BY t.trade_sequence DESC LIMIT ?""")
			.params(instrumentId, limit)
			.query((row, index) -> new TapeTrade(row.getObject("id", UUID.class), row.getLong("trade_sequence"),
					row.getLong("price_cents"), row.getLong("quantity"), Side.valueOf(row.getString("side")),
					row.getTimestamp("executed_at").toInstant()))
			.list();
	}

	@Override
	public void insertMarketSnapshot(MarketView view) {
		insertSnapshot(view, false);
	}

	@Override
	public void insertRemovedSnapshot(MarketView view) {
		insertSnapshot(view, true);
	}

	private void insertSnapshot(MarketView view, boolean removed) {
		MarketSnapshot.Builder snapshot = jdbc.sql("""
				SELECT symbol, display_name, reference_price_cents, tick_cents, lot_size, rule_version,
				    last_trade_sequence
				FROM instrument WHERE id = ? AND simulation_id = ?""")
			.params(view.instrumentId(), view.simulationId())
			.query((row, index) -> MarketSnapshot.newBuilder()
				.setSymbol(row.getString("symbol"))
				.setDisplayName(row.getString("display_name"))
				.setLastTradeSequence(row.getLong("last_trade_sequence"))
				.setRules(new MarketRules(row.getInt("rule_version"), row.getLong("reference_price_cents"),
						row.getLong("tick_cents"), row.getLong("lot_size"), Quantity.MIN_SHARES, Quantity.MAX_SHARES,
						Price.MAX_CENTS, view.market().maxOpenOrdersPerAccount(), view.market().maxFillsPerCommand())))
			.single();
		List<PublicTrade> recent = view.recentTrades()
			.stream()
			.map((trade) -> new PublicTrade(trade.tradeId(), trade.tradeSequence(), trade.priceCents(), trade.quantity(),
					AggressorSide.valueOf(trade.aggressor().name()), trade.executedAt()))
			.toList();
		MarketSnapshot event = snapshot.setEventId(UUID.randomUUID())
			.setSimulationId(view.simulationId())
			.setInstrumentId(view.instrumentId())
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
		expectOne(jdbc.sql("""
				INSERT INTO outbox (event_id, topic, event_key, schema_fingerprint, payload, command_sequence)
				VALUES (?, ?, ?, ?, ?, ?)""")
			.params(event.getEventId(), MARKET_TOPIC, view.simulationId() + "|" + view.instrumentId(),
					SNAPSHOT_FINGERPRINT, payload, view.commandSequence())
			.update(), "insert market snapshot");
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
