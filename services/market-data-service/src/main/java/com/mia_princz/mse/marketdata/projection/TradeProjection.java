package com.mia_princz.mse.marketdata.projection;

import java.sql.Timestamp;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.mia_princz.mse.contracts.events.TradeExecuted;

/**
 * Adds executions to the public tape, and each new one to its instrument's session figures. A trade id is inserted
 * once, so a redelivery, even under a new event id, changes nothing. A different trade under a sequence the instrument
 * already has is a contradiction, not a duplicate, and pauses the feed. Callers run each batch in one transaction, so
 * the tape and the figures never disagree.
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
			addToSession(trade);
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

	/**
	 * Every change is order-free: counts and sums add, the extremes take the greater or lesser price, and the last price
	 * is the one with the highest trade sequence. Trades may arrive in any order and still give the same figures.
	 */
	private void addToSession(TradeExecuted trade) {
		long notional = Math.multiplyExact(trade.getPriceCents(), trade.getQuantity());
		jdbc.sql("""
				INSERT INTO instrument_session AS s (instrument_id, simulation_id, trade_count, volume, notional_cents,
				    high_cents, low_cents, last_price_cents, last_trade_sequence)
				VALUES (:instrument, :simulation, 1, :quantity, :notional, :price, :price, :price, :sequence)
				ON CONFLICT (instrument_id) DO UPDATE SET
				    trade_count = s.trade_count + 1,
				    volume = s.volume + EXCLUDED.volume,
				    notional_cents = s.notional_cents + EXCLUDED.notional_cents,
				    high_cents = GREATEST(s.high_cents, EXCLUDED.high_cents),
				    low_cents = LEAST(s.low_cents, EXCLUDED.low_cents),
				    last_price_cents = CASE WHEN EXCLUDED.last_trade_sequence > s.last_trade_sequence
				        THEN EXCLUDED.last_price_cents ELSE s.last_price_cents END,
				    last_trade_sequence = GREATEST(s.last_trade_sequence, EXCLUDED.last_trade_sequence),
				    updated_at = now()""")
			.param("instrument", trade.getInstrumentId())
			.param("simulation", trade.getSimulationId())
			.param("quantity", trade.getQuantity())
			.param("notional", notional)
			.param("price", trade.getPriceCents())
			.param("sequence", trade.getTradeSequence())
			.update();
		jdbc.sql("""
				INSERT INTO session_minute AS m (instrument_id, minute_start, last_price_cents, last_trade_sequence)
				VALUES (:instrument, date_trunc('minute', CAST(:at AS TIMESTAMPTZ)), :price, :sequence)
				ON CONFLICT (instrument_id, minute_start) DO UPDATE SET
				    last_price_cents = CASE WHEN EXCLUDED.last_trade_sequence > m.last_trade_sequence
				        THEN EXCLUDED.last_price_cents ELSE m.last_price_cents END,
				    last_trade_sequence = GREATEST(m.last_trade_sequence, EXCLUDED.last_trade_sequence)""")
			.param("instrument", trade.getInstrumentId())
			.param("at", Timestamp.from(trade.getExecutedAt()))
			.param("price", trade.getPriceCents())
			.param("sequence", trade.getTradeSequence())
			.update();
	}

}
