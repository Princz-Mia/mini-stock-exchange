package com.mia_princz.mse.marketdata.projection;

import java.sql.Timestamp;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.mia_princz.mse.contracts.events.TradeExecuted;

/**
 * Adds executions to the public tape. A trade id is inserted once, so a redelivery, even under a new event id,
 * changes nothing. A different trade under a sequence the instrument already has is a contradiction, not a
 * duplicate, and pauses the feed. Callers run each batch in one transaction.
 */
public final class TradeProjection {

	static final String TOPIC = "mse.trades.v1";

	private final JdbcClient jdbc;

	public TradeProjection(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Returns whether the trade was new. */
	public boolean apply(TradeExecuted trade) {
		int firstSeen = jdbc.sql("INSERT INTO processed_event (event_id, topic) VALUES (?, ?) ON CONFLICT DO NOTHING")
			.params(trade.getEventId(), TOPIC)
			.update();
		if (firstSeen == 0) {
			return false;
		}
		// No conflict target: a clash on either key keeps the transaction usable; the check below tells which.
		int inserted = jdbc.sql("""
				INSERT INTO public_trade (trade_id, simulation_id, instrument_id, symbol, trade_sequence,
				    command_sequence, price_cents, quantity, executed_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT DO NOTHING""")
			.params(trade.getTradeId(), trade.getSimulationId(), trade.getInstrumentId(), trade.getSymbol(),
					trade.getTradeSequence(), trade.getCommandSequence(), trade.getPriceCents(), trade.getQuantity(),
					Timestamp.from(trade.getExecutedAt()))
			.update();
		if (inserted == 1) {
			return true;
		}
		boolean known = jdbc.sql("SELECT EXISTS (SELECT 1 FROM public_trade WHERE trade_id = ?)")
			.param(trade.getTradeId())
			.query(Boolean.class)
			.single();
		if (!known) {
			throw new UnusableRecordException("Trade " + trade.getTradeId() + " claims sequence "
					+ trade.getTradeSequence() + " of instrument " + trade.getInstrumentId()
					+ ", which another trade already has");
		}
		return false;
	}

}
