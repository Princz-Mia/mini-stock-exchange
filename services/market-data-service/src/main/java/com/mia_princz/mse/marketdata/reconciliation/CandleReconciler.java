package com.mia_princz.mse.marketdata.reconciliation;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Checks stored one-minute candles against the authoritative trades, exactly. A candle is the complete aggregate of
 * its minute's trades up to its source trade sequence, so each one is recomputed from the exported trades with that
 * bound and compared field by field. Candles that are merely behind still reconcile: what they cover must be exact.
 */
final class CandleReconciler {

	private static final long MICROS_PER_MINUTE = 60_000_000L;

	private CandleReconciler() {
	}

	/** One trade as the export gives it. */
	record Trade(long sequence, long priceCents, long quantity, long executedAtMicros) {
	}

	/** One stored candle. */
	record Candle(Instant bucketStart, long openCents, long highCents, long lowCents, long closeCents, long volume,
			long tradeCount, BigInteger notionalCents, long sourceMaxTradeSequence) {
	}

	/** {@code MISSING}: trades the candles cover but no candle; {@code EXTRA}: a candle without trades. */
	record Mismatch(Instant bucketStart, String problem, String detail) {
	}

	record Report(UUID instrumentId, long candlesThroughTradeSequence, long exportedThroughTradeSequence,
			int candlesChecked, List<Mismatch> mismatches) {

		boolean reconciled() {
			return mismatches.isEmpty();
		}

	}

	static Report reconcile(UUID instrumentId, List<Trade> trades, List<Candle> candles) {
		long through = candles.stream().mapToLong(Candle::sourceMaxTradeSequence).max().orElse(0);
		long exported = trades.stream().mapToLong(Trade::sequence).max().orElse(0);
		Map<Instant, List<Trade>> byMinute = new TreeMap<>();
		for (Trade trade : trades) {
			if (trade.sequence() <= through) {
				byMinute.computeIfAbsent(minute(trade), (start) -> new ArrayList<>()).add(trade);
			}
		}
		List<Mismatch> mismatches = new ArrayList<>();
		Set<Instant> stored = new HashSet<>();
		for (Candle candle : candles) {
			stored.add(candle.bucketStart());
			List<Trade> covered = byMinute.getOrDefault(candle.bucketStart(), List.of())
				.stream()
				.filter((trade) -> trade.sequence() <= candle.sourceMaxTradeSequence())
				.toList();
			if (covered.isEmpty()) {
				mismatches.add(new Mismatch(candle.bucketStart(), "EXTRA",
						"no exported trade in this minute up to sequence " + candle.sourceMaxTradeSequence()));
				continue;
			}
			String differences = differences(aggregate(candle.bucketStart(), covered), candle);
			if (!differences.isEmpty()) {
				mismatches.add(new Mismatch(candle.bucketStart(), "DIFFERENT", differences));
			}
		}
		byMinute.forEach((start, minuteTrades) -> {
			if (!stored.contains(start)) {
				mismatches.add(new Mismatch(start, "MISSING", minuteTrades.size() + " trades up to sequence "
						+ minuteTrades.stream().mapToLong(Trade::sequence).max().orElseThrow() + " have no candle"));
			}
		});
		mismatches.sort((a, b) -> a.bucketStart().compareTo(b.bucketStart()));
		return new Report(instrumentId, through, exported, candles.size(), List.copyOf(mismatches));
	}

	/** Open and close by (executedAt, tradeSequence), never by arrival; notional exact. */
	static Candle aggregate(Instant bucketStart, List<Trade> trades) {
		Trade first = trades.get(0);
		Trade last = trades.get(0);
		long high = Long.MIN_VALUE;
		long low = Long.MAX_VALUE;
		long volume = 0;
		BigInteger notional = BigInteger.ZERO;
		long maxSequence = 0;
		for (Trade trade : trades) {
			if (before(trade, first)) {
				first = trade;
			}
			if (before(last, trade)) {
				last = trade;
			}
			high = Math.max(high, trade.priceCents());
			low = Math.min(low, trade.priceCents());
			volume = Math.addExact(volume, trade.quantity());
			notional = notional.add(BigInteger.valueOf(trade.priceCents()).multiply(BigInteger.valueOf(trade.quantity())));
			maxSequence = Math.max(maxSequence, trade.sequence());
		}
		return new Candle(bucketStart, first.priceCents(), high, low, last.priceCents(), volume, trades.size(), notional,
				maxSequence);
	}

	private static boolean before(Trade a, Trade b) {
		return a.executedAtMicros() < b.executedAtMicros()
				|| (a.executedAtMicros() == b.executedAtMicros() && a.sequence() < b.sequence());
	}

	private static String differences(Candle expected, Candle stored) {
		List<String> fields = new ArrayList<>();
		compare(fields, "open", expected.openCents(), stored.openCents());
		compare(fields, "high", expected.highCents(), stored.highCents());
		compare(fields, "low", expected.lowCents(), stored.lowCents());
		compare(fields, "close", expected.closeCents(), stored.closeCents());
		compare(fields, "volume", expected.volume(), stored.volume());
		compare(fields, "tradeCount", expected.tradeCount(), stored.tradeCount());
		compare(fields, "sourceMaxTradeSequence", expected.sourceMaxTradeSequence(), stored.sourceMaxTradeSequence());
		if (!expected.notionalCents().equals(stored.notionalCents())) {
			fields.add("notionalCents trades " + expected.notionalCents() + ", candle " + stored.notionalCents());
		}
		return String.join("; ", fields);
	}

	private static void compare(List<String> fields, String name, long expected, long stored) {
		if (expected != stored) {
			fields.add(name + " trades " + expected + ", candle " + stored);
		}
	}

	private static Instant minute(Trade trade) {
		long micros = Math.floorDiv(trade.executedAtMicros(), MICROS_PER_MINUTE) * MICROS_PER_MINUTE;
		return Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000L);
	}

}
