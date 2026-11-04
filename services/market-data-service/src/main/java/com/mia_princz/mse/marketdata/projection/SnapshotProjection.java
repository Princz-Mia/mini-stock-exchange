package com.mia_princz.mse.marketdata.projection;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;

import tools.jackson.databind.json.JsonMapper;

import com.mia_princz.mse.contracts.events.BookLevel;
import com.mia_princz.mse.contracts.events.MarketSnapshot;
import com.mia_princz.mse.contracts.events.PublicTrade;

/**
 * Applies market snapshots to the instrument and market projections. A snapshot replaces the instrument's row only
 * when its source command sequence is higher than the stored one, so duplicates, redeliveries and late arrivals
 * change nothing. Its event id is recorded in the same transaction. Callers run each batch in one transaction.
 */
public final class SnapshotProjection {

	static final String TOPIC = "mse.market.v1";

	private final JdbcClient jdbc;

	private final JsonMapper json;

	public SnapshotProjection(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	/** Returns whether the snapshot changed the projection. */
	public boolean apply(MarketSnapshot snapshot) {
		int firstSeen = jdbc.sql("INSERT INTO processed_event (event_id, topic) VALUES (?, ?) ON CONFLICT DO NOTHING")
			.params(snapshot.getEventId(), TOPIC)
			.update();
		if (firstSeen == 0) {
			return false;
		}
		var rules = snapshot.getRules();
		jdbc.sql("""
				INSERT INTO instrument_projection (instrument_id, simulation_id, symbol, rule_version,
				    reference_price_cents, tick_cents, lot_size, min_quantity, max_quantity, max_price_cents,
				    source_command_sequence)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (instrument_id) DO UPDATE SET
				    symbol = EXCLUDED.symbol, rule_version = EXCLUDED.rule_version,
				    reference_price_cents = EXCLUDED.reference_price_cents, tick_cents = EXCLUDED.tick_cents,
				    lot_size = EXCLUDED.lot_size, min_quantity = EXCLUDED.min_quantity,
				    max_quantity = EXCLUDED.max_quantity, max_price_cents = EXCLUDED.max_price_cents,
				    source_command_sequence = EXCLUDED.source_command_sequence, updated_at = now()
				WHERE instrument_projection.source_command_sequence < EXCLUDED.source_command_sequence""")
			.params(snapshot.getInstrumentId(), snapshot.getSimulationId(), snapshot.getSymbol(), rules.getRuleVersion(),
					rules.getReferencePriceCents(), rules.getTickCents(), rules.getLotSize(), rules.getMinQuantity(),
					rules.getMaxQuantity(), rules.getMaxPriceCents(), snapshot.getSourceCommandSequence())
			.update();
		PublicTrade last = snapshot.getLastTrade();
		int replaced = jdbc.sql("""
				INSERT INTO market_snapshot (instrument_id, simulation_id, venue_state, bids, asks, recent_trades,
				    last_price_cents, last_trade_sequence, source_command_sequence, generated_at)
				VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?)
				ON CONFLICT (instrument_id) DO UPDATE SET
				    venue_state = EXCLUDED.venue_state, bids = EXCLUDED.bids, asks = EXCLUDED.asks,
				    recent_trades = EXCLUDED.recent_trades, last_price_cents = EXCLUDED.last_price_cents,
				    last_trade_sequence = EXCLUDED.last_trade_sequence,
				    source_command_sequence = EXCLUDED.source_command_sequence,
				    generated_at = EXCLUDED.generated_at, projected_at = now()
				WHERE market_snapshot.source_command_sequence < EXCLUDED.source_command_sequence""")
			.params(snapshot.getInstrumentId(), snapshot.getSimulationId(), snapshot.getVenueState().name(),
					levels(snapshot.getBids()), levels(snapshot.getAsks()), trades(snapshot.getRecentTrades()),
					last == null ? null : last.getPriceCents(), snapshot.getLastTradeSequence(),
					snapshot.getSourceCommandSequence(), Timestamp.from(snapshot.getGeneratedAt()))
			.update();
		return replaced == 1;
	}

	private String levels(List<BookLevel> levels) {
		return json.writeValueAsString(levels.stream()
			.map((level) -> Map.of("priceCents", level.getPriceCents(), "quantity", level.getQuantity(), "orderCount",
					level.getOrderCount()))
			.toList());
	}

	private String trades(List<PublicTrade> trades) {
		return json.writeValueAsString(trades.stream()
			.map((trade) -> Map.of("tradeId", trade.getTradeId().toString(), "tradeSequence", trade.getTradeSequence(),
					"priceCents", trade.getPriceCents(), "quantity", trade.getQuantity(), "aggressorSide",
					trade.getAggressorSide().name(), "executedAt", trade.getExecutedAt().toString()))
			.toList());
	}

}
