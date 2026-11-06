package com.mia_princz.mse.marketdata.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.mia_princz.mse.marketdata.reconciliation.CandleReconciler.Candle;
import com.mia_princz.mse.marketdata.reconciliation.CandleReconciler.Mismatch;
import com.mia_princz.mse.marketdata.reconciliation.CandleReconciler.Trade;

class CandleReconcilerTest {

	private static final Instant M = Instant.parse("2026-11-06T09:30:00Z");

	private static final UUID INSTRUMENT = UUID.randomUUID();

	@Test
	void candlesComputedFromTheTradesReconcile() {
		List<Trade> trades = List.of(trade(1, 0, 10_000, 3), trade(2, 10, 10_100, 4), trade(3, 70, 9_900, 5));
		List<Candle> candles = List.of(
				candle(M, 10_000, 10_100, 10_000, 10_100, 7, 2, 70_400, 2),
				candle(M.plusSeconds(60), 9_900, 9_900, 9_900, 9_900, 5, 1, 49_500, 3));

		CandleReconciler.Report report = CandleReconciler.reconcile(INSTRUMENT, trades, candles);

		assertThat(report.reconciled()).as(report.mismatches().toString()).isTrue();
		assertThat(report.candlesChecked()).isEqualTo(2);
		assertThat(report.candlesThroughTradeSequence()).isEqualTo(3);
	}

	@Test
	void candlesThatAreBehindStillReconcileForWhatTheyCover() {
		List<Trade> trades = List.of(trade(1, 0, 10_000, 3), trade(2, 10, 10_100, 4), trade(3, 20, 10_200, 1),
				trade(4, 80, 10_300, 2));
		// The job has processed up to sequence 2 only.
		List<Candle> candles = List.of(candle(M, 10_000, 10_100, 10_000, 10_100, 7, 2, 70_400, 2));

		CandleReconciler.Report report = CandleReconciler.reconcile(INSTRUMENT, trades, candles);

		assertThat(report.reconciled()).as(report.mismatches().toString()).isTrue();
		assertThat(report.exportedThroughTradeSequence()).isEqualTo(4);
	}

	/**
	 * Candles are keyed by minute, so different minutes can sit on different partitions and be stored out of order:
	 * minute two's candle up to trade 5 can arrive while minute one's update for trade 3 has not. Each candle is checked
	 * against its own source sequence, not the newest of all.
	 */
	@Test
	void aMinuteWhoseLatestUpdateHasNotArrivedStillReconciles() {
		List<Trade> trades = List.of(trade(1, 0, 10_000, 3), trade(2, 10, 10_100, 4), trade(3, 20, 10_200, 1),
				trade(4, 70, 10_300, 2), trade(5, 80, 10_400, 2));
		List<Candle> candles = List.of(candle(M, 10_000, 10_100, 10_000, 10_100, 7, 2, 70_400, 2),
				candle(M.plusSeconds(60), 10_300, 10_400, 10_300, 10_400, 4, 2, 41_400, 5));

		CandleReconciler.Report report = CandleReconciler.reconcile(INSTRUMENT, trades, candles);

		assertThat(report.reconciled()).as(report.mismatches().toString()).isTrue();
	}

	@Test
	void eachKindOfDifferenceIsNamed() {
		List<Trade> trades = List.of(trade(1, 0, 10_000, 3), trade(2, 70, 10_100, 4), trade(3, 130, 10_200, 5));
		List<Candle> candles = List.of(
				candle(M, 10_000, 10_000, 10_000, 10_000, 4, 1, 40_000, 1),
				candle(M.plusSeconds(120), 10_200, 10_200, 10_200, 10_200, 5, 1, 51_000, 3),
				candle(M.plusSeconds(300), 10_200, 10_200, 10_200, 10_200, 5, 1, 51_000, 3));

		List<Mismatch> mismatches = CandleReconciler.reconcile(INSTRUMENT, trades, candles).mismatches();

		assertThat(mismatches).extracting(Mismatch::problem).containsExactly("DIFFERENT", "MISSING", "EXTRA");
		assertThat(mismatches.get(0).detail()).contains("volume trades 3, candle 4")
			.contains("notionalCents trades 30000, candle 40000")
			.doesNotContain("open");
		assertThat(mismatches.get(1).bucketStart()).isEqualTo(M.plusSeconds(60));
		assertThat(mismatches.get(2).bucketStart()).isEqualTo(M.plusSeconds(300));
	}

	@Test
	void openAndCloseFollowExecutionTimeThenSequenceNotExportOrder() {
		List<Trade> trades = List.of(trade(2, 5, 10_100, 1), trade(1, 5, 10_000, 1), trade(3, 1, 9_900, 1));
		Candle aggregate = CandleReconciler.aggregate(M, trades);
		assertThat(aggregate.openCents()).as("earliest execution").isEqualTo(9_900);
		assertThat(aggregate.closeCents()).as("same instant: the higher sequence closes").isEqualTo(10_100);
	}

	/** Seeded streams against an independent computation, with candles stopped at random points behind. */
	@Test
	void seededStreamsReconcileAndAnyAlteredFieldIsCaught() {
		for (long seed = 1; seed <= 40; seed++) {
			Random random = new Random(seed);
			List<Trade> trades = new ArrayList<>();
			long at = 0;
			for (int sequence = 1; sequence <= 30 + random.nextInt(60); sequence++) {
				at += random.nextInt(4) == 0 ? 0 : random.nextInt(25_000_000);
				trades.add(new Trade(sequence, 9_000 + random.nextInt(2_000), 1 + random.nextInt(40),
						micros(M) + at));
			}
			long through = 1 + random.nextInt(trades.size());
			List<Candle> candles = oracle(trades, through);
			assertThat(CandleReconciler.reconcile(INSTRUMENT, trades, candles).reconciled()).as("seed %d", seed)
				.isTrue();

			int victim = random.nextInt(candles.size());
			Candle c = candles.get(victim);
			List<Candle> altered = new ArrayList<>(candles);
			altered.set(victim, switch (random.nextInt(4)) {
				case 0 -> new Candle(c.bucketStart(), c.openCents(), c.highCents(), c.lowCents(), c.closeCents(),
						c.volume() + 1, c.tradeCount(), c.notionalCents(), c.sourceMaxTradeSequence());
				case 1 -> new Candle(c.bucketStart(), c.openCents(), c.highCents(), c.lowCents(), c.closeCents(),
						c.volume(), c.tradeCount(), c.notionalCents().add(BigInteger.ONE), c.sourceMaxTradeSequence());
				case 2 -> new Candle(c.bucketStart(), c.closeCents(), c.highCents(), c.lowCents(), c.openCents() + 1,
						c.volume(), c.tradeCount(), c.notionalCents(), c.sourceMaxTradeSequence());
				default -> new Candle(c.bucketStart(), c.openCents(), c.highCents(), c.lowCents(), c.closeCents(),
						c.volume(), c.tradeCount() + 1, c.notionalCents(), c.sourceMaxTradeSequence());
			});
			assertThat(CandleReconciler.reconcile(INSTRUMENT, trades, altered).reconciled()).as("seed %d altered", seed)
				.isFalse();
		}
	}

	/** Candles as the job would have them after processing trades up to {@code through}: written independently. */
	private static List<Candle> oracle(List<Trade> trades, long through) {
		Map<Long, List<Trade>> byMinute = new TreeMap<>();
		for (Trade trade : trades) {
			if (trade.sequence() <= through) {
				byMinute.computeIfAbsent(Math.floorDiv(trade.executedAtMicros(), 60_000_000L), (m) -> new ArrayList<>())
					.add(trade);
			}
		}
		List<Candle> candles = new ArrayList<>();
		byMinute.forEach((minute, minuteTrades) -> {
			long open = 0;
			long close = 0;
			long openAt = Long.MAX_VALUE;
			long closeAt = Long.MIN_VALUE;
			long openSeq = Long.MAX_VALUE;
			long closeSeq = Long.MIN_VALUE;
			long high = 0;
			long low = Long.MAX_VALUE;
			long volume = 0;
			BigInteger notional = BigInteger.ZERO;
			long max = 0;
			for (Trade t : minuteTrades) {
				if (t.executedAtMicros() < openAt || (t.executedAtMicros() == openAt && t.sequence() < openSeq)) {
					open = t.priceCents();
					openAt = t.executedAtMicros();
					openSeq = t.sequence();
				}
				if (t.executedAtMicros() > closeAt || (t.executedAtMicros() == closeAt && t.sequence() > closeSeq)) {
					close = t.priceCents();
					closeAt = t.executedAtMicros();
					closeSeq = t.sequence();
				}
				high = Math.max(high, t.priceCents());
				low = Math.min(low, t.priceCents());
				volume += t.quantity();
				notional = notional.add(BigInteger.valueOf(t.priceCents() * t.quantity()));
				max = Math.max(max, t.sequence());
			}
			candles.add(new Candle(Instant.ofEpochSecond(minute * 60), open, high, low, close, volume,
					minuteTrades.size(), notional, max));
		});
		return candles;
	}

	private static Trade trade(long sequence, long secondsAfter, long priceCents, long quantity) {
		return new Trade(sequence, priceCents, quantity, micros(M.plusSeconds(secondsAfter)));
	}

	private static Candle candle(Instant start, long open, long high, long low, long close, long volume, long count,
			long notional, long sourceMax) {
		return new Candle(start, open, high, low, close, volume, count, BigInteger.valueOf(notional), sourceMax);
	}

	private static long micros(Instant instant) {
		return instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1_000L;
	}

}
