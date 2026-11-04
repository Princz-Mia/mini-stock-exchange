package com.mia_princz.mse.execution.trading.adapter.web;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.mia_princz.mse.execution.trading.domain.Price;

/** Read-only queries for the trading API. They never go through the writer and never change anything. */
@Component
class TradingQueries {

	/** The person behind a session: their user ID is the principal, their account is where they trade. */
	record Trader(UUID userId, UUID accountId) {
	}

	record OrderView(String orderId, String instrumentId, String symbol, String side, String type, String timeInForce,
			String limitPrice, String protectionPrice, long requestedQuantity, long filledQuantity,
			long workingQuantity, long canceledQuantity, String state, String reason, String version, Instant createdAt,
			Instant updatedAt) {
	}

	/** Where a page of orders ends; the next page starts strictly after it. */
	record OrderPosition(Instant createdAt, UUID id) {
	}

	record StoredCommand(String route, String body) {
	}

	record Holding(UUID instrumentId, String symbol, long total, long reserved, long markCents, boolean lastTrade,
			Instant markedAt) {
	}

	/** Cash and holdings from one snapshot of committed state, with the account version and command it reflects. */
	record Portfolio(long cashTotalCents, long cashReservedCents, List<Holding> holdings, long accountVersion,
			long asOfCommandSequence) {
	}

	record OwnTrade(UUID tradeId, UUID instrumentId, String symbol, String side, String liquidity, long priceCents,
			long quantity, Instant executedAt, long commandSequence, long tradeSequence) {
	}

	/** Where a page of trades ends: newest first by command, then by trade within the command. */
	record TradePosition(long commandSequence, long tradeSequence) {
	}

	private static final String ORDER_COLUMNS = """
			o.id, o.instrument_id, i.symbol, o.side, o.type, o.time_in_force, o.bound_cents, o.requested_quantity,
			o.filled_quantity, o.working_quantity, o.canceled_quantity, o.state, o.reason, o.version, o.created_at,
			o.updated_at""";

	private final JdbcClient jdbc;

	private final TransactionTemplate snapshot;

	TradingQueries(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.snapshot = new TransactionTemplate(transactionManager);
		this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
		this.snapshot.setReadOnly(true);
	}

	Optional<Trader> trader(String username) {
		return jdbc.sql("""
				SELECT u.id AS user_id, a.id AS account_id FROM app_user u
				JOIN account a ON a.owner_user_id = u.id WHERE u.username = ?""")
			.param(username)
			.query((row, index) -> new Trader(row.getObject("user_id", UUID.class),
					row.getObject("account_id", UUID.class)))
			.optional();
	}

	/** Only the caller's own order; another account's order is indistinguishable from a missing one. */
	Optional<OrderView> order(UUID orderId, UUID accountId) {
		return jdbc.sql("SELECT " + ORDER_COLUMNS + """

				FROM orders o JOIN instrument i ON i.id = o.instrument_id
				WHERE o.id = ? AND o.account_id = ?""")
			.params(orderId, accountId)
			.query((row, index) -> orderView(row))
			.optional();
	}

	/** Open and partially filled orders, newest first; at most the per-account limit, so never paged. */
	List<OrderView> activeOrders(UUID accountId) {
		return jdbc.sql("SELECT " + ORDER_COLUMNS + """

				FROM orders o JOIN instrument i ON i.id = o.instrument_id
				WHERE o.account_id = ? AND o.state IN ('OPEN', 'PARTIALLY_FILLED')
				ORDER BY o.created_at DESC, o.id DESC""")
			.param(accountId)
			.query((row, index) -> orderView(row))
			.list();
	}

	/** Every order, rejected ones included, newest first after {@code after}; one extra row signals another page. */
	List<OrderView> orders(UUID accountId, OrderPosition after, int limit) {
		return jdbc.sql("SELECT " + ORDER_COLUMNS + """

				FROM orders o JOIN instrument i ON i.id = o.instrument_id
				WHERE o.account_id = :account
				  AND (CAST(:afterAt AS TIMESTAMPTZ) IS NULL OR (o.created_at, o.id) < (:afterAt, :afterId))
				ORDER BY o.created_at DESC, o.id DESC LIMIT :limit""")
			.param("account", accountId)
			.param("afterAt", after == null ? null : Timestamp.from(after.createdAt()))
			.param("afterId", after == null ? null : after.id())
			.param("limit", limit)
			.query((row, index) -> orderView(row))
			.list();
	}

	Optional<StoredCommand> command(UUID principalId, UUID idempotencyKey) {
		return jdbc.sql("""
				SELECT route, response_body::text AS body FROM command_result
				WHERE principal_id = ? AND idempotency_key = ?""")
			.params(principalId, idempotencyKey)
			.query((row, index) -> new StoredCommand(row.getString("route"), row.getString("body")))
			.optional();
	}

	/**
	 * One REPEATABLE READ, read-only transaction, so cash and every holding come from the same committed command:
	 * a trade committing in between cannot leave the cash of one state beside the shares of another.
	 */
	Portfolio portfolio(UUID accountId) {
		return snapshot.execute((status) -> {
			long[] cash = jdbc.sql("""
					SELECT c.total_cents, c.reserved_cents, a.version, s.last_command_sequence
					FROM account a JOIN simulation s ON s.id = a.simulation_id
					LEFT JOIN cash_balance c ON c.account_id = a.id
					WHERE a.id = ?""")
				.param(accountId)
				.query((row, index) -> new long[] { row.getLong("total_cents"), row.getLong("reserved_cents"),
						row.getLong("version"), row.getLong("last_command_sequence") })
				.single();
			List<Holding> holdings = jdbc.sql("""
					SELECT p.instrument_id, i.symbol, p.total_quantity, p.reserved_quantity, i.reference_price_cents,
					    t.price_cents AS last_price_cents, t.executed_at AS last_executed_at
					FROM position p JOIN instrument i ON i.id = p.instrument_id
					LEFT JOIN trades t ON t.simulation_id = i.simulation_id AND t.instrument_id = i.id
					    AND t.trade_sequence = i.last_trade_sequence
					WHERE p.account_id = ?
					ORDER BY i.symbol""")
				.param(accountId)
				.query((row, index) -> {
					Long last = row.getObject("last_price_cents", Long.class);
					Timestamp at = row.getTimestamp("last_executed_at");
					return new Holding(row.getObject("instrument_id", UUID.class), row.getString("symbol"),
							row.getLong("total_quantity"), row.getLong("reserved_quantity"),
							last != null ? last : row.getLong("reference_price_cents"), last != null,
							at == null ? null : at.toInstant());
				})
				.list();
			return new Portfolio(cash[0], cash[1], holdings, cash[2], cash[3]);
		});
	}

	/**
	 * The caller's executions, newest first after {@code after}. Two index range scans, one per side of the trade,
	 * merged; the caller is never on both sides, because self-trades are prevented.
	 */
	List<OwnTrade> ownTrades(UUID accountId, TradePosition after, int limit) {
		String side = """
				SELECT t.id, t.instrument_id, i.symbol, '%1$s' AS side,
				    CASE WHEN m.account_id = :account THEN 'MAKER' ELSE 'TAKER' END AS liquidity,
				    t.price_cents, t.quantity, t.executed_at, t.command_sequence, t.trade_sequence
				FROM trades t JOIN instrument i ON i.id = t.instrument_id JOIN orders m ON m.id = t.maker_order_id
				WHERE t.%2$s = :account
				  AND (CAST(:afterCommand AS BIGINT) IS NULL
				       OR (t.command_sequence, t.trade_sequence) < (:afterCommand, :afterTrade))
				ORDER BY t.command_sequence DESC, t.trade_sequence DESC LIMIT :limit""";
		return jdbc.sql("(" + side.formatted("BUY", "buyer_account_id") + ") UNION ALL ("
				+ side.formatted("SELL", "seller_account_id") + ") ORDER BY command_sequence DESC, trade_sequence DESC "
				+ "LIMIT :limit")
			.param("account", accountId)
			.param("afterCommand", after == null ? null : after.commandSequence())
			.param("afterTrade", after == null ? null : after.tradeSequence())
			.param("limit", limit)
			.query((row, index) -> new OwnTrade(row.getObject("id", UUID.class),
					row.getObject("instrument_id", UUID.class), row.getString("symbol"), row.getString("side"),
					row.getString("liquidity"), row.getLong("price_cents"), row.getLong("quantity"),
					row.getTimestamp("executed_at").toInstant(), row.getLong("command_sequence"),
					row.getLong("trade_sequence")))
			.list();
	}

	private static OrderView orderView(ResultSet row) throws SQLException {
		String bound = new Price(row.getLong("bound_cents")).format();
		boolean limit = "LIMIT".equals(row.getString("type"));
		return new OrderView(row.getString("id"), row.getString("instrument_id"), row.getString("symbol"),
				row.getString("side"), row.getString("type"), row.getString("time_in_force"), limit ? bound : null,
				limit ? null : bound, row.getLong("requested_quantity"), row.getLong("filled_quantity"),
				row.getLong("working_quantity"), row.getLong("canceled_quantity"), row.getString("state"),
				row.getString("reason"), Long.toString(row.getLong("version")),
				row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
	}

}
