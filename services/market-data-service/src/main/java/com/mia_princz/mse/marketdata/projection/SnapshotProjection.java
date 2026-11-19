package com.mia_princz.mse.marketdata.projection;

import java.io.IOException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.jdbc.core.simple.JdbcClient;

import tools.jackson.databind.json.JsonMapper;

import com.mia_princz.mse.contracts.events.BookLevel;
import com.mia_princz.mse.contracts.events.MarketSnapshot;
import com.mia_princz.mse.contracts.events.PublicTrade;
import com.mia_princz.mse.contracts.events.VenueState;
import com.mia_princz.mse.contracts.events.WireEvents;

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

	/**
	 * The feed's handler: one snapshot at a time, or a whole poll at once. {@code changed} hears each instrument whose
	 * market changed, inside the transaction.
	 */
	public MarketFeedConsumer.BatchHandler handler(Consumer<UUID> changed) {
		return new MarketFeedConsumer.BatchHandler() {

			@Override
			public boolean apply(byte[] payload) throws IOException {
				MarketSnapshot snapshot = WireEvents.decodeSnapshot(payload);
				boolean applied = SnapshotProjection.this.apply(snapshot);
				if (applied) {
					changed.accept(snapshot.getInstrumentId());
				}
				return applied;
			}

			@Override
			public OptionalInt applyAll(List<byte[]> payloads) throws IOException {
				List<MarketSnapshot> snapshots = new ArrayList<>(payloads.size());
				for (byte[] payload : payloads) {
					snapshots.add(WireEvents.decodeSnapshot(payload));
				}
				return SnapshotProjection.this.applyAll(snapshots, changed);
			}

		};
	}

	/**
	 * A poll of snapshots in a few statements: the new event ids marked at once, then for each instrument only the
	 * newest new snapshot written, since each replaces the last only when newer. The projection ends as applying them
	 * one by one would leave it. Empty when the poll holds a removal: a removal applies even when seen before and its
	 * place in the order matters, so such a poll is applied one by one.
	 * <p>
	 * One by one, every new snapshot is offered to both tables, and PostgreSQL checks a row's constraints before it
	 * looks for a conflict, so a snapshot the tables refuse throws even when an older sequence means it would change
	 * nothing. The snapshots this method does not write are therefore offered too, after the newest, with
	 * {@code DO NOTHING}: every instrument then has its rows, so nothing is inserted, but a refused one still throws.
	 * <p>
	 * Returns how many snapshots one by one would have counted as changing a market: per instrument, those in the poll
	 * newer than every one before them, starting from the stored sequence. {@code changed} hears each instrument whose
	 * market changed once.
	 */
	public OptionalInt applyAll(List<MarketSnapshot> snapshots, Consumer<UUID> changed) {
		if (snapshots.stream().anyMatch(MarketSnapshot::getRemoved)) {
			return OptionalInt.empty();
		}
		Set<UUID> unseen = markSeen(snapshots.stream().map(MarketSnapshot::getEventId).toList());
		// Each new event once, in poll order: a second copy in the same poll is a repeat, as one by one would see it.
		List<MarketSnapshot> fresh = snapshots.stream().filter((snapshot) -> unseen.remove(snapshot.getEventId())).toList();
		// The newest new snapshot per instrument; of two with one sequence the first, as one by one would keep it.
		Map<UUID, MarketSnapshot> newest = new LinkedHashMap<>();
		// A new row takes the first snapshot's simulation, as one by one inserts it: an update never changes it.
		Map<UUID, UUID> simulations = new HashMap<>();
		for (MarketSnapshot snapshot : fresh) {
			simulations.putIfAbsent(snapshot.getInstrumentId(), snapshot.getSimulationId());
			newest.merge(snapshot.getInstrumentId(), snapshot, (kept, later) -> later
				.getSourceCommandSequence() > kept.getSourceCommandSequence() ? later : kept);
		}
		if (newest.isEmpty()) {
			return OptionalInt.of(0);
		}
		List<MarketSnapshot> written = List.copyOf(newest.values());
		List<MarketSnapshot> offered = fresh.stream()
			.filter((snapshot) -> newest.get(snapshot.getInstrumentId()) != snapshot)
			.toList();
		jdbc.sql(CATALOGUE_INSERT.formatted(values(written.size(), CATALOGUE_ROW)) + CATALOGUE_UPDATE)
			.params(catalogueRows(written, simulations))
			.update();
		// The main query reads the markets as they were before this statement, so each replaced one comes with the
		// sequence it held, or none for a new market.
		Map<UUID, Long> before = new LinkedHashMap<>();
		jdbc.sql("""
				WITH replaced AS (%s
				    RETURNING instrument_id)
				SELECT r.instrument_id, m.source_command_sequence
				FROM replaced r LEFT JOIN market_snapshot m ON m.instrument_id = r.instrument_id"""
			.formatted(MARKET_INSERT.formatted(values(written.size(), MARKET_ROW)) + MARKET_UPDATE))
			.params(marketRows(written, simulations))
			.query((rs) -> {
				before.put(rs.getObject(1, UUID.class), rs.getObject(2, Long.class));
			});
		if (!offered.isEmpty()) {
			jdbc.sql(CATALOGUE_INSERT.formatted(values(offered.size(), CATALOGUE_ROW)) + " ON CONFLICT (instrument_id) DO NOTHING")
				.params(catalogueRows(offered, Map.of()))
				.update();
			jdbc.sql(MARKET_INSERT.formatted(values(offered.size(), MARKET_ROW)) + " ON CONFLICT (instrument_id) DO NOTHING")
				.params(marketRows(offered, Map.of()))
				.update();
		}
		int counted = 0;
		Map<UUID, Long> highest = new LinkedHashMap<>();
		for (MarketSnapshot snapshot : fresh) {
			UUID instrument = snapshot.getInstrumentId();
			if (!before.containsKey(instrument)) {
				continue;
			}
			Long held = highest.containsKey(instrument) ? highest.get(instrument) : before.get(instrument);
			if (held == null || snapshot.getSourceCommandSequence() > held) {
				counted++;
				highest.put(instrument, snapshot.getSourceCommandSequence());
			}
			else {
				highest.put(instrument, held);
			}
		}
		before.keySet().forEach(changed);
		return OptionalInt.of(counted);
	}

	private static final String CATALOGUE_INSERT = """
			INSERT INTO instrument_projection (instrument_id, simulation_id, symbol, display_name, rule_version,
			    reference_price_cents, tick_cents, lot_size, min_quantity, max_quantity, max_price_cents,
			    source_command_sequence)
			VALUES %s""";

	private static final String CATALOGUE_ROW = "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

	private static final String CATALOGUE_UPDATE = """

			ON CONFLICT (instrument_id) DO UPDATE SET
			    symbol = EXCLUDED.symbol, display_name = EXCLUDED.display_name, rule_version = EXCLUDED.rule_version,
			    reference_price_cents = EXCLUDED.reference_price_cents, tick_cents = EXCLUDED.tick_cents,
			    lot_size = EXCLUDED.lot_size, min_quantity = EXCLUDED.min_quantity,
			    max_quantity = EXCLUDED.max_quantity, max_price_cents = EXCLUDED.max_price_cents,
			    source_command_sequence = EXCLUDED.source_command_sequence, updated_at = now()
			WHERE instrument_projection.source_command_sequence < EXCLUDED.source_command_sequence""";

	private static final String MARKET_INSERT = """
			INSERT INTO market_snapshot (instrument_id, simulation_id, venue_state, bids, asks, recent_trades,
			    last_price_cents, last_trade_sequence, source_command_sequence, generated_at)
			VALUES %s""";

	private static final String MARKET_ROW = "(?::uuid, ?::uuid, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::bigint, ?::bigint, ?::bigint, ?::timestamptz)";

	private static final String MARKET_UPDATE = """

			ON CONFLICT (instrument_id) DO UPDATE SET
			    venue_state = EXCLUDED.venue_state, bids = EXCLUDED.bids, asks = EXCLUDED.asks,
			    recent_trades = EXCLUDED.recent_trades, last_price_cents = EXCLUDED.last_price_cents,
			    last_trade_sequence = EXCLUDED.last_trade_sequence,
			    source_command_sequence = EXCLUDED.source_command_sequence,
			    generated_at = EXCLUDED.generated_at, projected_at = now()
			WHERE market_snapshot.source_command_sequence < EXCLUDED.source_command_sequence""";

	/** The rows' parameters; {@code simulations} overrides a snapshot's simulation for its instrument. */
	private static List<Object> catalogueRows(List<MarketSnapshot> snapshots, Map<UUID, UUID> simulations) {
		List<Object> rows = new ArrayList<>(snapshots.size() * 12);
		for (MarketSnapshot snapshot : snapshots) {
			var rules = snapshot.getRules();
			rows.addAll(List.of(snapshot.getInstrumentId(),
					simulations.getOrDefault(snapshot.getInstrumentId(), snapshot.getSimulationId()), snapshot.getSymbol()));
			rows.add(snapshot.getDisplayName());
			rows.addAll(List.of(rules.getRuleVersion(), rules.getReferencePriceCents(), rules.getTickCents(),
					rules.getLotSize(), rules.getMinQuantity(), rules.getMaxQuantity(), rules.getMaxPriceCents(),
					snapshot.getSourceCommandSequence()));
		}
		return rows;
	}

	private List<Object> marketRows(List<MarketSnapshot> snapshots, Map<UUID, UUID> simulations) {
		List<Object> rows = new ArrayList<>(snapshots.size() * 10);
		for (MarketSnapshot snapshot : snapshots) {
			PublicTrade last = snapshot.getLastTrade();
			rows.addAll(List.of(snapshot.getInstrumentId(),
					simulations.getOrDefault(snapshot.getInstrumentId(), snapshot.getSimulationId()),
					venueState(snapshot.getVenueState()), levels(snapshot.getBids()), levels(snapshot.getAsks()),
					trades(snapshot.getRecentTrades())));
			rows.add(last == null ? null : last.getPriceCents());
			rows.addAll(List.of(snapshot.getLastTradeSequence(), snapshot.getSourceCommandSequence(),
					Timestamp.from(snapshot.getGeneratedAt())));
		}
		return rows;
	}

	/** Marks the event ids seen, in one statement; returns those seen for the first time. */
	private Set<UUID> markSeen(List<UUID> eventIds) {
		List<Object> params = new ArrayList<>(eventIds.size() * 2);
		for (UUID eventId : eventIds) {
			params.add(eventId);
			params.add(TOPIC);
		}
		return new HashSet<>(jdbc.sql("""
				INSERT INTO processed_event (event_id, topic) VALUES %s ON CONFLICT DO NOTHING RETURNING event_id"""
			.formatted(values(eventIds.size(), "(?, ?)")))
			.params(params)
			.query(UUID.class)
			.list());
	}

	static String values(int rows, String row) {
		return String.join(", ", Collections.nCopies(rows, row));
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
