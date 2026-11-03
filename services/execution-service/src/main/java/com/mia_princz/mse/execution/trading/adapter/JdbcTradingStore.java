package com.mia_princz.mse.execution.trading.adapter;

import java.io.IOException;
import java.io.UncheckedIOException;
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

import com.mia_princz.mse.contracts.events.TradeExecuted;
import com.mia_princz.mse.contracts.events.WireEvents;
import com.mia_princz.mse.execution.trading.application.CommandKey;
import com.mia_princz.mse.execution.trading.application.TradingStore;
import com.mia_princz.mse.execution.trading.domain.BookOrder;
import com.mia_princz.mse.execution.trading.domain.Fill;
import com.mia_princz.mse.execution.trading.domain.OrderRequest;
import com.mia_princz.mse.execution.trading.domain.OrderType;
import com.mia_princz.mse.execution.trading.domain.Posting;
import com.mia_princz.mse.execution.trading.domain.Price;
import com.mia_princz.mse.execution.trading.domain.Side;
import com.mia_princz.mse.execution.trading.domain.SettlementPlan;

/** Plain SQL for the trading tables. Every write is checked against its expected row count. */
public final class JdbcTradingStore implements TradingStore {

	private static final String TRADES_TOPIC = "mse.trades.v1";

	/** Stands for cash where SQL needs a non-null asset key; no instrument has this ID. */
	private static final UUID CASH = new UUID(0, 0);

	private static final long TRADE_FINGERPRINT = SchemaNormalization
		.parsingFingerprint64(TradeExecuted.getClassSchema());

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
			.query((row, index) -> new StoredResult<>(row.getString("route"), row.getBytes("request_hash"),
					json.readValue(row.getString("body"), type)))
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

	private static void expectOne(int rows, String what) {
		if (rows != 1) {
			throw new IllegalStateException(what + " affected " + rows + " rows, expected 1");
		}
	}

}
