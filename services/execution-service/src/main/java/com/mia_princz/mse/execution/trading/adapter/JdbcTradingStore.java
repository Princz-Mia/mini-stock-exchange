package com.mia_princz.mse.execution.trading.adapter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.apache.avro.SchemaNormalization;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.mia_princz.mse.contracts.events.TradeExecuted;
import com.mia_princz.mse.contracts.events.WireEvents;
import com.mia_princz.mse.execution.trading.application.TradingStore;
import com.mia_princz.mse.execution.trading.domain.Fill;
import com.mia_princz.mse.execution.trading.domain.OrderRequest;
import com.mia_princz.mse.execution.trading.domain.OrderType;
import com.mia_princz.mse.execution.trading.domain.Posting;
import com.mia_princz.mse.execution.trading.domain.Side;
import com.mia_princz.mse.execution.trading.domain.SettlementPlan;

/** Plain SQL for the trading tables. Every write is checked against its expected row count. */
public final class JdbcTradingStore implements TradingStore {

	private static final String TRADES_TOPIC = "mse.trades.v1";

	private static final long TRADE_FINGERPRINT = SchemaNormalization
		.parsingFingerprint64(TradeExecuted.getClassSchema());

	private final JdbcClient jdbc;

	public JdbcTradingStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
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
