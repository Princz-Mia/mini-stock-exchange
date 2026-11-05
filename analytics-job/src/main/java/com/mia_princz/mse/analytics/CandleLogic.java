package com.mia_princz.mse.analytics;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One instrument's trades to one-minute candles, free of Flink so the rules can be tested exactly. The relay publishes
 * each instrument's trades in sequence order on one partition, so:
 * <ul>
 * <li>a sequence at or below the last accepted one is a redelivery and is ignored;</li>
 * <li>a sequence that skips ahead is a gap: a trade went missing, and inventing the candle without it would be wrong.
 * The instrument stops producing candles until someone investigates and replays it into a new computation epoch;</li>
 * <li>the first sequence must be 1, unless the job was started from an explicit replay boundary.</li>
 * </ul>
 */
final class CandleLogic implements Serializable {

	private static final long serialVersionUID = 1L;

	static final long MICROS_PER_MINUTE = 60_000_000L;

	/** Longer than the trades topic's seven-day retention, so no bucket a retained trade can reach is dropped. */
	static final long RETENTION_MICROS = Duration.ofDays(8).toNanos() / 1_000;

	/** Pruning walks every bucket, so it runs at most once an hour of trading time per instrument. */
	static final long CLEANUP_EVERY_MICROS = Duration.ofHours(1).toNanos() / 1_000;

	/** What happened to one trade. */
	enum Kind {

		/** Counted; the complete updated candle is emitted. */
		EMITTED,
		/** Already counted; a redelivery. */
		DUPLICATE,
		/** Executed before the replay boundary: it only sets where the sequence starts. */
		BEFORE_BOUNDARY,
		/** This trade revealed a gap; the instrument stops here. */
		GAP,
		/** A later trade on an instrument already stopped by a gap. */
		HELD

	}

	record Outcome(Kind kind, CandleRecord candle, long expectedSequence) {
	}

	private final Long boundaryMicros;

	/** @param replayFrom null to start from the first trade, which must then be sequence 1 */
	CandleLogic(Instant replayFrom) {
		this.boundaryMicros = replayFrom == null ? null : toMicros(replayFrom);
	}

	Outcome process(TradeRecord trade, CandleState state) throws Exception {
		Long gap = state.gapExpected();
		Long last = state.lastSequence();
		if (last != null && trade.tradeSequence <= last) {
			return new Outcome(Kind.DUPLICATE, null, 0);
		}
		if (gap != null) {
			return new Outcome(Kind.HELD, null, gap);
		}
		if (last == null && boundaryMicros != null) {
			// Started from a replay boundary: wherever this instrument's sequence is, it starts here.
			last = trade.tradeSequence - 1;
		}
		long expected = last == null ? 1 : last + 1;
		if (trade.tradeSequence != expected) {
			state.gapExpected(expected);
			return new Outcome(Kind.GAP, null, expected);
		}
		state.lastSequence(trade.tradeSequence);
		if (boundaryMicros != null && trade.executedAtMicros < boundaryMicros) {
			return new Outcome(Kind.BEFORE_BOUNDARY, null, 0);
		}
		pruneIfDue(trade.executedAtMicros, state);
		long bucketStartMillis = Math.floorDiv(trade.executedAtMicros, MICROS_PER_MINUTE) * 60_000L;
		CandleRecord candle = state.bucket(bucketStartMillis);
		if (candle == null) {
			candle = CandleRecord.start(trade, bucketStartMillis);
		}
		candle.add(trade);
		state.putBucket(bucketStartMillis, candle);
		return new Outcome(Kind.EMITTED, candle, 0);
	}

	private static void pruneIfDue(long nowMicros, CandleState state) throws Exception {
		Long due = state.nextCleanupMicros();
		if (due != null && nowMicros < due) {
			return;
		}
		if (due != null) {
			long oldestKeptMillis = Math.floorDiv(nowMicros - RETENTION_MICROS, 1_000L);
			List<Long> expired = new ArrayList<>();
			for (Long start : state.bucketStarts()) {
				if (start < oldestKeptMillis) {
					expired.add(start);
				}
			}
			for (Long start : expired) {
				state.removeBucket(start);
			}
		}
		state.nextCleanupMicros(nowMicros + CLEANUP_EVERY_MICROS);
	}

	static long toMicros(Instant instant) {
		return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000L), instant.getNano() / 1_000L);
	}

}
