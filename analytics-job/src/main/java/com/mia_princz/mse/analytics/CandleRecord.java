package com.mia_princz.mse.analytics;

import java.math.BigInteger;

/** One-minute aggregate kept in checkpointed state and emitted complete after each unique trade. */
public class CandleRecord {

	public String simulationId;
	public String instrumentId;
	public String symbol;
	public long bucketStartMillis;
	public long openCents;
	public long highCents;
	public long lowCents;
	public long closeCents;
	public long volume;
	public long tradeCount;
	public BigInteger notionalCents;
	public long sourceMaxTradeSequence;
	/** Ordering of open and close is (executedAt, tradeSequence), never Kafka arrival. */
	public long openAtMicros;
	public long openSequence;
	public long closeAtMicros;
	public long closeSequence;

	public CandleRecord() {
	}

	static CandleRecord start(TradeRecord trade, long bucketStartMillis) {
		CandleRecord candle = new CandleRecord();
		candle.simulationId = trade.simulationId;
		candle.instrumentId = trade.instrumentId;
		candle.symbol = trade.symbol;
		candle.bucketStartMillis = bucketStartMillis;
		candle.openCents = trade.priceCents;
		candle.highCents = trade.priceCents;
		candle.lowCents = trade.priceCents;
		candle.closeCents = trade.priceCents;
		candle.notionalCents = BigInteger.ZERO;
		candle.openAtMicros = trade.executedAtMicros;
		candle.openSequence = trade.tradeSequence;
		candle.closeAtMicros = trade.executedAtMicros;
		candle.closeSequence = trade.tradeSequence;
		return candle;
	}

	void add(TradeRecord trade) {
		highCents = Math.max(highCents, trade.priceCents);
		lowCents = Math.min(lowCents, trade.priceCents);
		volume = Math.addExact(volume, trade.quantity);
		tradeCount = Math.addExact(tradeCount, 1);
		notionalCents = notionalCents
			.add(BigInteger.valueOf(trade.priceCents).multiply(BigInteger.valueOf(trade.quantity)));
		sourceMaxTradeSequence = Math.max(sourceMaxTradeSequence, trade.tradeSequence);
		if (isBefore(trade, openAtMicros, openSequence)) {
			openCents = trade.priceCents;
			openAtMicros = trade.executedAtMicros;
			openSequence = trade.tradeSequence;
		}
		if (!isBefore(trade, closeAtMicros, closeSequence)) {
			closeCents = trade.priceCents;
			closeAtMicros = trade.executedAtMicros;
			closeSequence = trade.tradeSequence;
		}
	}

	private static boolean isBefore(TradeRecord trade, long atMicros, long sequence) {
		return trade.executedAtMicros < atMicros
				|| (trade.executedAtMicros == atMicros && trade.tradeSequence < sequence);
	}
}
