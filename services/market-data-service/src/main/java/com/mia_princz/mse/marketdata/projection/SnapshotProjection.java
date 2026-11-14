package com.mia_princz.mse.marketdata.projection;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;

import tools.jackson.databind.json.JsonMapper;

import com.mia_princz.mse.contracts.events.BookLevel;
import com.mia_princz.mse.contracts.events.MarketSnapshot;
import com.mia_princz.mse.contracts.events.PublicTrade;
import com.mia_princz.mse.contracts.events.VenueState;

/**
 * Applies market snapshots to the instrument and market projections. A snapshot replaces the instrument's row only
 * when its source command sequence is higher than the stored one, so duplicates, redeliveries and late arrivals
 * change nothing. The display name is replaced with the rest, so a newer snapshot without one clears it. A snapshot
 * that says the instrument was removed deletes both rows under the same rule, and after a removal it is the event id,
 * not the sequence, that stops a redelivered add. Each event id is recorded in the same transaction. Callers run each
 * batch in one transaction.
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
		if (snapshot.getRemoved()) {
			return remove(snapshot);
		}
		if (firstSeen == 0) {
			return false;
		}
		var rules = snapshot.getRules();
		jdbc.sql("""
				INSERT INTO instrument_projection (instrument_id, simulation_id, symbol, display_name, rule_version,
				    reference_price_cents, tick_cents, lot_size, min_quantity, max_quantity, max_price_cents,
				    source_command_sequence)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (instrument_id) DO UPDATE SET
				    symbol = EXCLUDED.symbol, display_name = EXCLUDED.display_name, rule_version = EXCLUDED.rule_version,
				    reference_price_cents = EXCLUDED.reference_price_cents, tick_cents = EXCLUDED.tick_cents,
				    lot_size = EXCLUDED.lot_size, min_quantity = EXCLUDED.min_quantity,
				    max_quantity = EXCLUDED.max_quantity, max_price_cents = EXCLUDED.max_price_cents,
				    source_command_sequence = EXCLUDED.source_command_sequence, updated_at = now()
				WHERE instrument_projection.source_command_sequence < EXCLUDED.source_command_sequence""")
			.params(snapshot.getInstrumentId(), snapshot.getSimulationId(), snapshot.getSymbol(),
					snapshot.getDisplayName(), rules.getRuleVersion(),
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
			.params(snapshot.getInstrumentId(), snapshot.getSimulationId(), venueState(snapshot.getVenueState()),
					levels(snapshot.getBids()), levels(snapshot.getAsks()), trades(snapshot.getRecentTrades()),
					last == null ? null : last.getPriceCents(), snapshot.getLastTradeSequence(),
					snapshot.getSourceCommandSequence(), Timestamp.from(snapshot.getGeneratedAt()))
			.update();
		return replaced == 1;
	}

	/**
	 * Deletes the instrument's market and then its catalogue entry, which the market refers to, each only below the
	 * removal's sequence. Both rows always come from the same snapshot, so they hold the same sequence and go
	 * together. Nothing is inserted, so a removal of an instrument never seen, as on a rebuild from the
	 * compacted topic where the removal may be its only record, changes nothing.
	 * <p>
	 * A removal applies even when its event id was seen before. When the outbox relay resends after a failure, an add
	 * still unmarked is sent again together with every later event, so the add's copy is always followed by the
	 * removal's. Compaction can drop the first add while the copy is still in an uncleaned segment, and a rebuild then
	 * inserts the copy under an event id the emptied table has not seen. The removal's copy has to delete it again.
	 * The deletes are guarded and idempotent, so a repeated removal is otherwise harmless.
	 */
	private boolean remove(MarketSnapshot snapshot) {
		int markets = jdbc.sql("DELETE FROM market_snapshot WHERE instrument_id = ? AND source_command_sequence < ?")
			.params(snapshot.getInstrumentId(), snapshot.getSourceCommandSequence())
			.update();
		int instruments = jdbc
			.sql("DELETE FROM instrument_projection WHERE instrument_id = ? AND source_command_sequence < ?")
			.params(snapshot.getInstrumentId(), snapshot.getSourceCommandSequence())
			.update();
		return markets + instruments > 0;
	}

	/**
	 * The venue state as stored and served. Every state the event can carry is named here, so a state added to the
	 * schema does not compile until it is decided. CLOSED is in the event from its second version, ahead of the
	 * simulations that end: until the API and this projection's CHECK say CLOSED, it is shown as UNKNOWN, as a reader
	 * of the first version sees it.
	 */
	static String venueState(VenueState state) {
		return switch (state) {
			case SETUP -> "SETUP";
			case OPEN -> "OPEN";
			case HALTED -> "HALTED";
			case UNKNOWN, CLOSED -> "UNKNOWN";
		};
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
