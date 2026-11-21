package com.mia_princz.mse.marketdata.projection;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.mia_princz.mse.contracts.events.BookLevel;
import com.mia_princz.mse.contracts.events.MarketSnapshot;
import com.mia_princz.mse.contracts.events.PublicTrade;

/**
 * Each instrument's book minute by minute (V10): every new snapshot is a sample of its instrument's minute, by the
 * writer's clock. A minute keeps its lowest and highest command sequence and the state at the highest; of two samples
 * with one sequence, the one held, as the table keeps it. The same rule merges a poll's samples before they are
 * written, so a poll and its snapshots one by one end the same, in one statement either way.
 */
final class SpreadMinutes {

	private SpreadMinutes() {
	}

	/** One instrument's minute: {@code bidCents} and {@code askCents} are null while that side is empty. */
	record Minute(UUID instrumentId, Instant start, long firstSequence, long lastSequence, Long bidCents,
			Long askCents, Long lastPriceCents) {

		/** This minute with another sample of it, the state taken from the higher sequence, this one's on a tie. */
		Minute with(Minute other) {
			Minute state = other.lastSequence > lastSequence ? other : this;
			return new Minute(instrumentId, start, Math.min(firstSequence, other.firstSequence),
					Math.max(lastSequence, other.lastSequence), state.bidCents, state.askCents, state.lastPriceCents);
		}

	}

	/**
	 * The snapshot as a sample of its minute. A sample the table would refuse is refused here, before any merging, so
	 * a poll holding one fails as the same snapshot one by one does, and the feed pauses on it either way.
	 */
	static Minute of(MarketSnapshot snapshot) {
		long sequence = snapshot.getSourceCommandSequence();
		Long bid = best(snapshot.getBids());
		Long ask = best(snapshot.getAsks());
		PublicTrade last = snapshot.getLastTrade();
		Long price = last == null ? null : last.getPriceCents();
		if (sequence <= 0 || (bid != null && bid <= 0) || (ask != null && ask <= 0) || (price != null && price <= 0)) {
			throw new UnusableRecordException("snapshot " + snapshot.getEventId() + " has a sequence or price below 1");
		}
		if (bid != null && ask != null && bid >= ask) {
			throw new UnusableRecordException("snapshot " + snapshot.getEventId() + " has a crossed book");
		}
		return new Minute(snapshot.getInstrumentId(), snapshot.getGeneratedAt().truncatedTo(ChronoUnit.MINUTES),
				sequence, sequence, bid, ask, price);
	}

	private static Long best(List<BookLevel> levels) {
		return levels.isEmpty() ? null : levels.getFirst().getPriceCents();
	}

	/** Adds the snapshots to their minutes, in one statement. */
	static void write(JdbcClient jdbc, List<MarketSnapshot> snapshots) {
		// PostgreSQL refuses a statement that updates one row twice, so each minute goes in once, merged as the table
		// would merge it.
		Map<List<Object>, Minute> minutes = new LinkedHashMap<>();
		for (MarketSnapshot snapshot : snapshots) {
			Minute sample = of(snapshot);
			minutes.merge(List.of(sample.instrumentId(), sample.start()), sample, Minute::with);
		}
		if (minutes.isEmpty()) {
			return;
		}
		List<Object> params = new ArrayList<>(minutes.size() * 7);
		for (Minute minute : minutes.values()) {
			params.add(minute.instrumentId());
			params.add(Timestamp.from(minute.start()));
			params.add(minute.firstSequence());
			params.add(minute.lastSequence());
			params.add(minute.bidCents());
			params.add(minute.askCents());
			params.add(minute.lastPriceCents());
		}
		jdbc.sql(UPSERT.formatted(SnapshotProjection.values(minutes.size(), ROW))).params(params).update();
	}

	private static final String ROW = "(?::uuid, ?::timestamptz, ?::bigint, ?::bigint, ?::bigint, ?::bigint, ?::bigint)";

	private static final String UPSERT = """
			INSERT INTO spread_minute AS m (instrument_id, minute_start, first_sequence, last_sequence, best_bid_cents,
			    best_ask_cents, last_price_cents)
			VALUES %s
			ON CONFLICT (instrument_id, minute_start) DO UPDATE SET
			    first_sequence = LEAST(m.first_sequence, EXCLUDED.first_sequence),
			    last_sequence = GREATEST(m.last_sequence, EXCLUDED.last_sequence),
			    best_bid_cents = CASE WHEN EXCLUDED.last_sequence > m.last_sequence
			        THEN EXCLUDED.best_bid_cents ELSE m.best_bid_cents END,
			    best_ask_cents = CASE WHEN EXCLUDED.last_sequence > m.last_sequence
			        THEN EXCLUDED.best_ask_cents ELSE m.best_ask_cents END,
			    last_price_cents = CASE WHEN EXCLUDED.last_sequence > m.last_sequence
			        THEN EXCLUDED.last_price_cents ELSE m.last_price_cents END""";

}
