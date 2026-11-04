package com.mia_princz.mse.marketdata;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.mia_princz.mse.contracts.events.TradeExecuted;

/** Test trades: 5 shares at 100.00 plus the sequence in cents, so each trade's price is distinct. */
final class Trades {

	private Trades() {
	}

	static TradeExecuted trade(UUID instrument, long tradeSequence) {
		return TradeExecuted.newBuilder()
			.setEventId(UUID.randomUUID())
			.setSimulationId(Snapshots.SIMULATION)
			.setInstrumentId(instrument)
			.setSymbol("NOVA")
			.setTradeId(UUID.randomUUID())
			.setTradeSequence(tradeSequence)
			.setCommandSequence(tradeSequence)
			.setExecutedAt(Instant.now().truncatedTo(ChronoUnit.MICROS))
			.setPriceCents(10_000 + tradeSequence)
			.setQuantity(5)
			.build();
	}

}
