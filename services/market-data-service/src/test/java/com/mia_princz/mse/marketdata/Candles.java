package com.mia_princz.mse.marketdata;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.mia_princz.mse.contracts.events.CandleUpdated;

/**
 * Test candles: the complete aggregate of {@code trades} trades of 5 shares at 100.00 plus the sequence in cents, in
 * one minute, up to source sequence {@code trades}.
 */
final class Candles {

	static final Instant MINUTE = Instant.parse("2026-11-05T10:15:00Z");

	private Candles() {
	}

	static CandleUpdated candle(long epoch, UUID instrument, Instant minute, int trades) {
		long notional = 0;
		for (int sequence = 1; sequence <= trades; sequence++) {
			notional += 5 * (10_000L + sequence);
		}
		return CandleUpdated.newBuilder()
			.setEventId(UUID.randomUUID())
			.setComputationEpoch(epoch)
			.setSimulationId(Snapshots.SIMULATION)
			.setInstrumentId(instrument)
			.setSymbol("NOVA")
			.setBucketStart(minute)
			.setOpenCents(10_001)
			.setHighCents(10_000L + trades)
			.setLowCents(10_001)
			.setCloseCents(10_000L + trades)
			.setVolume(5L * trades)
			.setTradeCount(trades)
			.setNotionalCents(BigDecimal.valueOf(notional))
			.setSourceMaxTradeSequence(trades)
			.setGeneratedAt(Instant.now().truncatedTo(ChronoUnit.MICROS))
			.build();
	}

}
