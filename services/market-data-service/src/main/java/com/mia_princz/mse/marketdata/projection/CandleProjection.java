package com.mia_princz.mse.marketdata.projection;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Map;
import java.util.Objects;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.mia_princz.mse.contracts.events.CandleUpdated;

/**
 * Applies one-minute candles from the analytics job. Each update is the complete aggregate up to a source trade
 * sequence, so a minute's row is replaced only by a higher sequence in the same computation epoch: redeliveries and
 * stale versions change nothing. Two different aggregates for the same minute and sequence mean the job is not
 * deterministic, which pauses the feed. Callers run each batch in one transaction.
 */
public final class CandleProjection {

	static final String TOPIC = "mse.candles.v1";

	private final JdbcClient jdbc;

	public CandleProjection(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Returns whether the candle changed the projection. */
	public boolean apply(CandleUpdated candle) {
		int firstSeen = jdbc.sql("INSERT INTO processed_event (event_id, topic) VALUES (?, ?) ON CONFLICT DO NOTHING")
			.params(candle.getEventId(), TOPIC)
			.update();
		if (firstSeen == 0) {
			return false;
		}
		Timestamp bucket = Timestamp.from(candle.getBucketStart());
		int written = jdbc.sql("""
				INSERT INTO candle_1m (computation_epoch, simulation_id, instrument_id, bucket_start, symbol, open_cents,
				    high_cents, low_cents, close_cents, volume, trade_count, notional_cents, source_max_trade_sequence,
				    generated_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (computation_epoch, simulation_id, instrument_id, bucket_start) DO UPDATE SET
				    symbol = EXCLUDED.symbol, open_cents = EXCLUDED.open_cents, high_cents = EXCLUDED.high_cents,
				    low_cents = EXCLUDED.low_cents, close_cents = EXCLUDED.close_cents, volume = EXCLUDED.volume,
				    trade_count = EXCLUDED.trade_count, notional_cents = EXCLUDED.notional_cents,
				    source_max_trade_sequence = EXCLUDED.source_max_trade_sequence,
				    generated_at = EXCLUDED.generated_at, projected_at = now()
				WHERE candle_1m.source_max_trade_sequence < EXCLUDED.source_max_trade_sequence""")
			.params(candle.getComputationEpoch(), candle.getSimulationId(), candle.getInstrumentId(), bucket,
					candle.getSymbol(), candle.getOpenCents(), candle.getHighCents(), candle.getLowCents(),
					candle.getCloseCents(), candle.getVolume(), candle.getTradeCount(), candle.getNotionalCents(),
					candle.getSourceMaxTradeSequence(), Timestamp.from(candle.getGeneratedAt()))
			.update();
		if (written == 1) {
			return true;
		}
		Map<String, Object> stored = jdbc.sql("""
				SELECT open_cents, high_cents, low_cents, close_cents, volume, trade_count, notional_cents,
				    source_max_trade_sequence
				FROM candle_1m
				WHERE computation_epoch = ? AND simulation_id = ? AND instrument_id = ? AND bucket_start = ?""")
			.params(candle.getComputationEpoch(), candle.getSimulationId(), candle.getInstrumentId(), bucket)
			.query()
			.singleRow();
		if (((Number) stored.get("source_max_trade_sequence")).longValue() == candle.getSourceMaxTradeSequence()
				&& !sameAggregate(stored, candle)) {
			throw new UnusableRecordException("Candle " + candle.getEventId() + " for " + candle.getSymbol() + " at "
					+ candle.getBucketStart() + " in epoch " + candle.getComputationEpoch()
					+ " disagrees with the stored aggregate up to the same trade sequence "
					+ candle.getSourceMaxTradeSequence());
		}
		return false;
	}

	private static boolean sameAggregate(Map<String, Object> stored, CandleUpdated candle) {
		return number(stored, "open_cents") == candle.getOpenCents()
				&& number(stored, "high_cents") == candle.getHighCents()
				&& number(stored, "low_cents") == candle.getLowCents()
				&& number(stored, "close_cents") == candle.getCloseCents()
				&& number(stored, "volume") == candle.getVolume()
				&& number(stored, "trade_count") == candle.getTradeCount()
				&& ((BigDecimal) Objects.requireNonNull(stored.get("notional_cents")))
					.compareTo(candle.getNotionalCents()) == 0;
	}

	private static long number(Map<String, Object> row, String column) {
		return ((Number) row.get(column)).longValue();
	}

}
