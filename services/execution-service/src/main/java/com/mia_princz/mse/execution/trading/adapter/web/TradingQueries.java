package com.mia_princz.mse.execution.trading.adapter.web;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.mia_princz.mse.execution.trading.domain.Price;

/** Read-only queries for the trading API. They never go through the writer and never change anything. */
@Component
class TradingQueries {

	/** The person behind a session: their user ID is the principal, their account is where they trade. */
	record Trader(UUID userId, UUID accountId) {
	}

	record OrderView(String orderId, String instrumentId, String side, String type, String timeInForce,
			String limitPrice, String protectionPrice, long requestedQuantity, long filledQuantity,
			long workingQuantity, long canceledQuantity, String state, String reason, String version) {
	}

	record StoredCommand(String route, String body) {
	}

	private final JdbcClient jdbc;

	TradingQueries(JdbcClient jdbc) {
		this.jdbc = jdbc;
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
		return jdbc.sql("""
				SELECT id, instrument_id, side, type, time_in_force, bound_cents, requested_quantity, filled_quantity,
				    working_quantity, canceled_quantity, state, reason, version
				FROM orders WHERE id = ? AND account_id = ?""")
			.params(orderId, accountId)
			.query((row, index) -> {
				String bound = new Price(row.getLong("bound_cents")).format();
				boolean limit = "LIMIT".equals(row.getString("type"));
				return new OrderView(row.getString("id"), row.getString("instrument_id"), row.getString("side"),
						row.getString("type"), row.getString("time_in_force"), limit ? bound : null, limit ? null : bound,
						row.getLong("requested_quantity"), row.getLong("filled_quantity"),
						row.getLong("working_quantity"), row.getLong("canceled_quantity"), row.getString("state"),
						row.getString("reason"), Long.toString(row.getLong("version")));
			})
			.optional();
	}

	Optional<StoredCommand> command(UUID principalId, UUID idempotencyKey) {
		return jdbc.sql("""
				SELECT route, response_body::text AS body FROM command_result
				WHERE principal_id = ? AND idempotency_key = ?""")
			.params(principalId, idempotencyKey)
			.query((row, index) -> new StoredCommand(row.getString("route"), row.getString("body")))
			.optional();
	}

}
