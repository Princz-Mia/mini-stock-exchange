package com.mia_princz.mse.execution.trading.adapter.web;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
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

	/** The trading phase as the writer last committed it. Empty before a market has been set up. */
	record MarketStatus(UUID simulationId, String state, Instant openedAt, long commandSequence) {
	}

	Optional<MarketStatus> market() {
		return jdbc.sql("SELECT id, state, opened_at, last_command_sequence FROM simulation")
			.query((row, index) -> {
				Timestamp openedAt = row.getTimestamp("opened_at");
				return new MarketStatus(row.getObject("id", UUID.class), row.getString("state"),
						openedAt == null ? null : openedAt.toInstant(), row.getLong("last_command_sequence"));
			})
			.optional();
	}

	/** Any signed-in user's ID, trading account or not; operators act as themselves. */
	Optional<UUID> userId(String username) {
		return jdbc.sql("SELECT id FROM app_user WHERE username = ?").param(username).query(UUID.class).optional();
	}

	/** An account and what it started with, for the operator. Cash is null if it started with shares only. */
	/** {@code token} is a bot's: ACTIVE while it has a token that works, REVOKED once none does; null for others. */
	record AccountSummary(UUID accountId, String username, String kind, Long cashCents, List<Allocation> holdings,
			Instant createdAt, String token) {
	}

	record Allocation(UUID instrumentId, String symbol, long quantity) {
	}

	/** Where a page of accounts ends: by creation, then by ID. */
	record AccountPosition(Instant createdAt, UUID id) {
	}

	/** Every trading account with its initial allocation, in creation order either way, in keyset pages. */
	List<AccountSummary> accounts(AccountPosition after, int limit, PageOrder order) {
		record Row(UUID id, String name, String kind, Instant createdAt, String token) {
		}
		List<Row> rows = jdbc.sql("""
				SELECT id, display_name, kind, created_at,
				    CASE WHEN kind <> 'BOT' THEN NULL
				         WHEN EXISTS (SELECT 1 FROM api_token t WHERE t.account_id = account.id AND t.revoked_at IS NULL)
				         THEN 'ACTIVE' ELSE 'REVOKED' END AS token
				FROM account
				WHERE kind <> 'ISSUANCE'%1$s
				ORDER BY created_at %2$s, id %2$s LIMIT :limit""".formatted(
				after == null ? "" : " AND (created_at, id) " + order.after() + " (:afterAt, :afterId)",
				order.direction()))
			.param("afterAt", after == null ? null : Timestamp.from(after.createdAt()))
			.param("afterId", after == null ? null : after.id())
			.param("limit", limit)
			.query((row, index) -> new Row(row.getObject("id", UUID.class), row.getString("display_name"),
					row.getString("kind"), row.getTimestamp("created_at").toInstant(), row.getString("token")))
			.list();
		if (rows.isEmpty()) {
			return List.of();
		}
		record Allocated(UUID accountId, UUID instrumentId, String symbol, long amount) {
		}
		List<Allocated> allocations = jdbc.sql("""
				SELECT a.account_id, a.instrument_id, i.symbol, a.amount FROM initial_allocation a
				LEFT JOIN instrument i ON i.id = a.instrument_id
				WHERE a.account_id IN (:ids) ORDER BY i.symbol NULLS FIRST""")
			.param("ids", rows.stream().map(Row::id).toList())
			.query((row, index) -> new Allocated(row.getObject("account_id", UUID.class),
					row.getObject("instrument_id", UUID.class), row.getString("symbol"), row.getLong("amount")))
			.list();
		return rows.stream().map((row) -> {
			Long cash = null;
			List<Allocation> holdings = new ArrayList<>();
			for (Allocated allocated : allocations) {
				if (!allocated.accountId().equals(row.id())) {
					continue;
				}
				if (allocated.instrumentId() == null) {
					cash = allocated.amount();
				}
				else {
					holdings.add(new Allocation(allocated.instrumentId(), allocated.symbol(), allocated.amount()));
				}
			}
			return new AccountSummary(row.id(), row.name(), row.kind(), cash, holdings, row.createdAt(), row.token());
		}).toList();
	}

	int botCount() {
		return jdbc.sql("SELECT count(*) FROM account WHERE kind = 'BOT'").query(Integer.class).single();
	}

	/** A user who may be given a trading account: one with the TRADER role. */
	Optional<UUID> traderUserId(String username) {
		return jdbc.sql("SELECT id FROM app_user WHERE username = ? AND role = 'TRADER'")
			.param(username)
			.query(UUID.class)
			.optional();
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

	/**
	 * Every order, rejected ones included, after {@code after} in either time order; one extra row signals another
	 * page.
	 */
	List<OrderView> orders(UUID accountId, OrderPosition after, int limit, PageOrder order, HistoryFilter filter) {
		JdbcClient.StatementSpec statement = jdbc.sql(ordersSql(order, filter, after != null))
			.param("account", accountId)
			.param("limit", limit);
		if (after != null) {
			statement = statement.param("afterAt", Timestamp.from(after.createdAt())).param("afterId", after.id());
		}
		if (filter.instrumentId() != null) {
			statement = statement.param("instrument", filter.instrumentId());
		}
		if (filter.side() != null) {
			statement = statement.param("side", filter.side());
		}
		if (filter.state() != null) {
			statement = statement.param("state", filter.state());
		}
		return statement.query((row, index) -> orderView(row)).list();
	}

	/**
	 * The order history statement. Every condition is fixed text added only when it applies: a filter that is set,
	 * and the keyset position on a page after the first. An optional condition such as {@code (:x IS NULL OR ...)}
	 * would hide the equality or the row comparison from the planner, which could then not start the scan at the
	 * filter's index range or at the page's position; once the driver prepares the statement on the server and the
	 * server settles on a generic plan, every later page would be read from the head of the range.
	 */
	static String ordersSql(PageOrder order, HistoryFilter filter, boolean continued) {
		String conditions = (filter.instrumentId() == null ? "" : " AND o.instrument_id = :instrument")
				+ (filter.side() == null ? "" : " AND o.side = :side")
				+ (filter.state() == null ? "" : " AND o.state = :state")
				+ (continued ? " AND (o.created_at, o.id) " + order.after() + " (:afterAt, :afterId)" : "");
		return "SELECT " + ORDER_COLUMNS + """

				FROM orders o JOIN instrument i ON i.id = o.instrument_id
				WHERE o.account_id = :account%1$s
				ORDER BY o.created_at %2$s, o.id %2$s LIMIT :limit""".formatted(conditions, order.direction());
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
	 * The caller's executions after {@code after}, in either order. Two index range scans, one per side of the trade,
	 * merged; the caller is never on both sides, because self-trades are prevented.
	 */
	List<OwnTrade> ownTrades(UUID accountId, TradePosition after, int limit, PageOrder order, HistoryFilter filter) {
		JdbcClient.StatementSpec statement = jdbc.sql(ownTradesSql(order, filter, after != null))
			.param("account", accountId)
			.param("limit", limit);
		if (after != null) {
			statement = statement.param("afterCommand", after.commandSequence())
				.param("afterTrade", after.tradeSequence());
		}
		if (filter.instrumentId() != null) {
			statement = statement.param("instrument", filter.instrumentId());
		}
		return statement
			.query((row, index) -> new OwnTrade(row.getObject("id", UUID.class),
					row.getObject("instrument_id", UUID.class), row.getString("symbol"), row.getString("side"),
					row.getString("liquidity"), row.getLong("price_cents"), row.getLong("quantity"),
					row.getTimestamp("executed_at").toInstant(), row.getLong("command_sequence"),
					row.getLong("trade_sequence")))
			.list();
	}

	/**
	 * The executions statement: one index range scan for one side, two merged for both. Conditions are fixed text
	 * added only when they apply, for the reason given at {@link #ordersSql}.
	 */
	static String ownTradesSql(PageOrder order, HistoryFilter filter, boolean continued) {
		String side = """
				SELECT t.id, t.instrument_id, i.symbol, '%1$s' AS side,
				    CASE WHEN m.account_id = :account THEN 'MAKER' ELSE 'TAKER' END AS liquidity,
				    t.price_cents, t.quantity, t.executed_at, t.command_sequence, t.trade_sequence
				FROM trades t JOIN instrument i ON i.id = t.instrument_id JOIN orders m ON m.id = t.maker_order_id
				WHERE t.%2$s = :account%3$s
				ORDER BY t.command_sequence %4$s, t.trade_sequence %4$s LIMIT :limit""";
		String direction = order.direction();
		String conditions = (filter.instrumentId() == null ? "" : " AND t.instrument_id = :instrument")
				+ (continued ? " AND (t.command_sequence, t.trade_sequence) " + order.after()
						+ " (:afterCommand, :afterTrade)" : "");
		String buys = side.formatted("BUY", "buyer_account_id", conditions, direction);
		String sells = side.formatted("SELL", "seller_account_id", conditions, direction);
		if ("BUY".equals(filter.side())) {
			return buys;
		}
		if ("SELL".equals(filter.side())) {
			return sells;
		}
		return "(" + buys + ") UNION ALL (" + sells + ") ORDER BY command_sequence " + direction + ", trade_sequence "
				+ direction + " LIMIT :limit";
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
