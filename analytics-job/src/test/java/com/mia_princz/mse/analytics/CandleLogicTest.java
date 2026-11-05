package com.mia_princz.mse.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.mia_princz.mse.analytics.CandleLogic.Kind;

class CandleLogicTest {

	private static final Instant START = Instant.parse("2026-11-05T10:15:00Z");

	private static final String SIMULATION = UUID.randomUUID().toString();

	private static final String INSTRUMENT = UUID.randomUUID().toString();

	@Test
	void aGapStopsTheInstrumentInsteadOfInventingACandle() throws Exception {
		CandleLogic logic = new CandleLogic(null);
		MemoryState state = new MemoryState();
		assertThat(kinds(logic, state, trade(1, 0, 10_000, 1), trade(2, 1, 10_100, 2), trade(2, 1, 10_100, 2),
				trade(4, 2, 10_200, 1), trade(5, 3, 10_300, 1), trade(3, 4, 10_000, 1)))
			.containsExactly(Kind.EMITTED, Kind.EMITTED, Kind.DUPLICATE, Kind.GAP, Kind.HELD, Kind.HELD);
		assertThat(state.gap).isEqualTo(3);
		assertThat(state.last).as("nothing after the gap is accepted").isEqualTo(2);
		CandleRecord candle = state.buckets.get(START.toEpochMilli());
		assertThat(candle.tradeCount).isEqualTo(2);
		assertThat(candle.sourceMaxTradeSequence).isEqualTo(2);
	}

	@Test
	void withoutAReplayBoundaryTheFirstTradeMustBeSequenceOne() throws Exception {
		MemoryState state = new MemoryState();
		assertThat(kinds(new CandleLogic(null), state, trade(3, 0, 10_000, 1))).containsExactly(Kind.GAP);
		assertThat(state.gap).isEqualTo(1);
		assertThat(state.buckets).isEmpty();
	}

	@Test
	void fromAReplayBoundaryTheSequenceStartsAnywhereButOnlyLaterTradesCount() throws Exception {
		CandleLogic logic = new CandleLogic(START.plusSeconds(60));
		MemoryState state = new MemoryState();
		assertThat(kinds(logic, state, trade(41, 50, 10_000, 1), trade(42, 59, 10_000, 1), trade(43, 60, 10_500, 2),
				trade(43, 60, 10_500, 2), trade(45, 70, 10_600, 1)))
			.containsExactly(Kind.BEFORE_BOUNDARY, Kind.BEFORE_BOUNDARY, Kind.EMITTED, Kind.DUPLICATE, Kind.GAP);
		assertThat(state.buckets).containsOnlyKeys(START.plusSeconds(60).toEpochMilli());
		assertThat(state.buckets.get(START.plusSeconds(60).toEpochMilli()).volume).isEqualTo(2);
	}

	@Test
	void bucketsOlderThanEightDaysArePrunedAtMostHourly() throws Exception {
		CandleLogic logic = new CandleLogic(null);
		MemoryState state = new MemoryState();
		long day = Duration.ofDays(1).toSeconds();
		logic.process(trade(1, 0, 10_000, 1), state);
		logic.process(trade(2, 2 * day, 10_000, 1), state);
		logic.process(trade(3, 8 * day - 60, 10_000, 1), state);
		assertThat(state.buckets).as("the first bucket is just inside eight days").hasSize(3);
		logic.process(trade(4, 8 * day + 90, 10_000, 1), state);
		assertThat(state.buckets).as("past eight days, but the hourly prune is not due yet").hasSize(4);
		logic.process(trade(5, 9 * day + 2 * 3600, 10_000, 1), state);
		assertThat(state.buckets.keySet()).containsExactly(START.plusSeconds(2 * day).toEpochMilli(),
				START.plusSeconds(8 * day - 60).toEpochMilli(), START.plusSeconds(8 * day + 60).toEpochMilli(),
				START.plusSeconds(9 * day + 2 * 3600).toEpochMilli());
		assertThat(state.last).as("the sequence is never pruned").isEqualTo(5);
	}

	/**
	 * A restart resumes from the last checkpoint: the state as it was then, and the trades delivered after it, some
	 * possibly delivered twice. Wherever the checkpoint falls, the candles emitted after it are the ones the
	 * uninterrupted run emitted, and the final candles equal a direct computation from the unique trades.
	 */
	@Test
	void resumingFromAnyCheckpointReplaysToExactlyTheSameCandles() throws Exception {
		for (long seed = 1; seed <= 25; seed++) {
			Random random = new Random(seed);
			List<TradeRecord> unique = stream(random, 60 + random.nextInt(60));
			List<TradeRecord> delivered = withRedeliveries(random, unique);
			CandleLogic logic = new CandleLogic(null);

			MemoryState uninterrupted = new MemoryState();
			List<MemoryState> checkpoints = new ArrayList<>();
			List<List<String>> emittedAfter = new ArrayList<>();
			List<String> emitted = new ArrayList<>();
			for (TradeRecord trade : delivered) {
				checkpoints.add(uninterrupted.copy());
				emittedAfter.add(new ArrayList<>(emitted));
				CandleLogic.Outcome outcome = logic.process(trade, uninterrupted);
				assertThat(outcome.kind()).isIn(Kind.EMITTED, Kind.DUPLICATE);
				if (outcome.kind() == Kind.EMITTED) {
					emitted.add(describe(outcome.candle()));
				}
			}
			assertThat(describeAll(uninterrupted.buckets)).as("seed %d", seed).isEqualTo(oracle(unique));

			for (int at = 0; at < delivered.size(); at += 1 + random.nextInt(7)) {
				MemoryState restored = checkpoints.get(at).copy();
				List<String> replayed = new ArrayList<>(emittedAfter.get(at));
				// The source rewinds to the checkpoint and may also hand over a few trades it had already sent.
				int rewind = Math.max(0, at - random.nextInt(4));
				for (TradeRecord trade : delivered.subList(rewind, delivered.size())) {
					CandleLogic.Outcome outcome = logic.process(trade, restored);
					if (outcome.kind() == Kind.EMITTED) {
						replayed.add(describe(outcome.candle()));
					}
				}
				assertThat(replayed).as("seed %d, checkpoint at %d", seed, at).isEqualTo(emitted);
				assertThat(describeAll(restored.buckets)).isEqualTo(oracle(unique));
			}
		}
	}

	@Test
	void aReplayBoundaryMustBeAWholeMinute() {
		assertThat(AnalyticsJob.replayBoundary(null)).isNull();
		assertThat(AnalyticsJob.replayBoundary(" ")).isNull();
		assertThat(AnalyticsJob.replayBoundary("2026-11-05T10:15:00Z")).isEqualTo(START);
		assertThatThrownBy(() -> AnalyticsJob.replayBoundary("2026-11-05T10:15:30Z"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	/** Sequences 1..n, executed in sequence order with seeded gaps of up to 40 seconds, some at the same instant. */
	private static List<TradeRecord> stream(Random random, int count) {
		List<TradeRecord> trades = new ArrayList<>();
		long at = random.nextInt(60);
		for (int sequence = 1; sequence <= count; sequence++) {
			at += random.nextInt(5) == 0 ? 0 : random.nextInt(40);
			trades.add(trade(sequence, at, 9_000 + random.nextInt(2_000), 1 + random.nextInt(50)));
		}
		return trades;
	}

	private static List<TradeRecord> withRedeliveries(Random random, List<TradeRecord> unique) {
		List<TradeRecord> delivered = new ArrayList<>();
		for (int i = 0; i < unique.size(); i++) {
			delivered.add(unique.get(i));
			if (random.nextInt(6) == 0) {
				delivered.add(unique.get(random.nextInt(i + 1)));
			}
		}
		return delivered;
	}

	/** Candles computed straight from the unique trades, independently of the logic under test. */
	private static List<String> oracle(List<TradeRecord> unique) {
		Map<Long, List<TradeRecord>> byMinute = new TreeMap<>();
		for (TradeRecord trade : unique) {
			byMinute.computeIfAbsent(Math.floorDiv(trade.executedAtMicros, 60_000_000L) * 60_000L,
					(minute) -> new ArrayList<>()).add(trade);
		}
		List<String> candles = new ArrayList<>();
		byMinute.forEach((minute, trades) -> {
			CandleRecord expected = new CandleRecord();
			TradeRecord first = trades.get(0);
			TradeRecord lastTrade = trades.get(0);
			expected.bucketStartMillis = minute;
			expected.highCents = Long.MIN_VALUE;
			expected.lowCents = Long.MAX_VALUE;
			expected.notionalCents = BigInteger.ZERO;
			for (TradeRecord trade : trades) {
				if (trade.executedAtMicros < first.executedAtMicros || (trade.executedAtMicros == first.executedAtMicros
						&& trade.tradeSequence < first.tradeSequence)) {
					first = trade;
				}
				if (trade.executedAtMicros > lastTrade.executedAtMicros
						|| (trade.executedAtMicros == lastTrade.executedAtMicros
								&& trade.tradeSequence > lastTrade.tradeSequence)) {
					lastTrade = trade;
				}
				expected.highCents = Math.max(expected.highCents, trade.priceCents);
				expected.lowCents = Math.min(expected.lowCents, trade.priceCents);
				expected.volume += trade.quantity;
				expected.tradeCount++;
				expected.notionalCents = expected.notionalCents
					.add(BigInteger.valueOf(trade.priceCents * trade.quantity));
				expected.sourceMaxTradeSequence = Math.max(expected.sourceMaxTradeSequence, trade.tradeSequence);
			}
			expected.openCents = first.priceCents;
			expected.closeCents = lastTrade.priceCents;
			candles.add(describe(expected));
		});
		return candles;
	}

	private static List<String> describeAll(Map<Long, CandleRecord> buckets) {
		return new TreeMap<>(buckets).values().stream().map(CandleLogicTest::describe).toList();
	}

	private static String describe(CandleRecord candle) {
		return candle.bucketStartMillis + " O" + candle.openCents + " H" + candle.highCents + " L" + candle.lowCents
				+ " C" + candle.closeCents + " V" + candle.volume + " N" + candle.tradeCount + " $"
				+ candle.notionalCents + " #" + candle.sourceMaxTradeSequence;
	}

	private static List<Kind> kinds(CandleLogic logic, CandleState state, TradeRecord... trades) throws Exception {
		List<Kind> kinds = new ArrayList<>();
		for (TradeRecord trade : trades) {
			kinds.add(logic.process(trade, state).kind());
		}
		return kinds;
	}

	private static TradeRecord trade(long sequence, long secondsAfterStart, long priceCents, long quantity) {
		TradeRecord trade = new TradeRecord();
		trade.simulationId = SIMULATION;
		trade.instrumentId = INSTRUMENT;
		trade.symbol = "NOVA";
		trade.tradeSequence = sequence;
		trade.executedAtMicros = CandleLogic.toMicros(START.plusSeconds(secondsAfterStart));
		trade.priceCents = priceCents;
		trade.quantity = quantity;
		return trade;
	}

	/** Plain state that can be copied, standing in for a checkpoint. */
	private static final class MemoryState implements CandleState {

		Long last;

		Long gap;

		Long nextCleanup;

		final Map<Long, CandleRecord> buckets = new TreeMap<>();

		MemoryState copy() {
			MemoryState copy = new MemoryState();
			copy.last = last;
			copy.gap = gap;
			copy.nextCleanup = nextCleanup;
			buckets.forEach((start, candle) -> copy.buckets.put(start, copyOf(candle)));
			return copy;
		}

		private static CandleRecord copyOf(CandleRecord source) {
			CandleRecord copy = new CandleRecord();
			copy.simulationId = source.simulationId;
			copy.instrumentId = source.instrumentId;
			copy.symbol = source.symbol;
			copy.bucketStartMillis = source.bucketStartMillis;
			copy.openCents = source.openCents;
			copy.highCents = source.highCents;
			copy.lowCents = source.lowCents;
			copy.closeCents = source.closeCents;
			copy.volume = source.volume;
			copy.tradeCount = source.tradeCount;
			copy.notionalCents = source.notionalCents;
			copy.sourceMaxTradeSequence = source.sourceMaxTradeSequence;
			copy.openAtMicros = source.openAtMicros;
			copy.openSequence = source.openSequence;
			copy.closeAtMicros = source.closeAtMicros;
			copy.closeSequence = source.closeSequence;
			return copy;
		}

		@Override
		public Long lastSequence() {
			return last;
		}

		@Override
		public void lastSequence(long sequence) {
			last = sequence;
		}

		@Override
		public Long gapExpected() {
			return gap;
		}

		@Override
		public void gapExpected(long sequence) {
			gap = sequence;
		}

		@Override
		public CandleRecord bucket(long bucketStartMillis) {
			return buckets.get(bucketStartMillis);
		}

		@Override
		public void putBucket(long bucketStartMillis, CandleRecord candle) {
			buckets.put(bucketStartMillis, candle);
		}

		@Override
		public Iterable<Long> bucketStarts() {
			return buckets.keySet();
		}

		@Override
		public void removeBucket(long bucketStartMillis) {
			buckets.remove(bucketStartMillis);
		}

		@Override
		public Long nextCleanupMicros() {
			return nextCleanup;
		}

		@Override
		public void nextCleanupMicros(long micros) {
			nextCleanup = micros;
		}

	}

}
