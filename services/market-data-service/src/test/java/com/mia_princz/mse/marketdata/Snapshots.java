package com.mia_princz.mse.marketdata;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mia_princz.mse.contracts.events.AggressorSide;
import com.mia_princz.mse.contracts.events.BookLevel;
import com.mia_princz.mse.contracts.events.MarketRules;
import com.mia_princz.mse.contracts.events.MarketSnapshot;
import com.mia_princz.mse.contracts.events.PublicTrade;
import com.mia_princz.mse.contracts.events.VenueState;

/** Test snapshots: {@code levels} bid levels below 100.00, and one trade when {@code traded}. */
final class Snapshots {

	static final UUID SIMULATION = UUID.randomUUID();

	private Snapshots() {
	}

	/** An instrument before its first trade: no book, no tape, no last trade. */
	static MarketSnapshot untraded(UUID instrument, long commandSequence) {
		return MarketSnapshot.newBuilder()
			.setEventId(UUID.randomUUID())
			.setSimulationId(SIMULATION)
			.setInstrumentId(instrument)
			.setSymbol("NOVA")
			.setVenueState(VenueState.OPEN)
			.setRules(new MarketRules(1, 10_000L, 1L, 1L, 1L, 10_000L, 100_000_000L, 20, 1_000))
			.setBids(List.of())
			.setAsks(List.of())
			.setRecentTrades(List.of())
			.setLastTrade(null)
			.setLastTradeSequence(0)
			.setSourceCommandSequence(commandSequence)
			.setGeneratedAt(Instant.now().truncatedTo(ChronoUnit.MICROS))
			.build();
	}

	static MarketSnapshot snapshot(UUID instrument, long commandSequence, int levels) {
		List<BookLevel> bids = new ArrayList<>();
		for (int i = 0; i < levels; i++) {
			bids.add(new BookLevel(10_000L - i, 10L + i, 1));
		}
		PublicTrade trade = new PublicTrade(UUID.randomUUID(), commandSequence, 10_000L, 5L, AggressorSide.BUY,
				Instant.now().truncatedTo(ChronoUnit.MICROS));
		return MarketSnapshot.newBuilder()
			.setEventId(UUID.randomUUID())
			.setSimulationId(SIMULATION)
			.setInstrumentId(instrument)
			.setSymbol("NOVA")
			.setVenueState(VenueState.OPEN)
			.setRules(new MarketRules(1, 10_000L, 1L, 1L, 1L, 10_000L, 100_000_000L, 20, 1_000))
			.setBids(bids)
			.setAsks(List.of(new BookLevel(10_100L, 3L, 1)))
			.setRecentTrades(List.of(trade))
			.setLastTrade(trade)
			.setLastTradeSequence(commandSequence)
			.setSourceCommandSequence(commandSequence)
			.setGeneratedAt(Instant.now().truncatedTo(ChronoUnit.MICROS))
			.build();
	}

}
