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

	/** What Execution writes when it deletes an instrument during setup: an empty market that says it was removed. */
	static MarketSnapshot removed(UUID instrument, long commandSequence) {
		MarketSnapshot removal = untraded(instrument, commandSequence);
		removal.setVenueState(VenueState.SETUP);
		removal.setRemoved(true);
		return removal;
	}

	/**
	 * The book at a writer-clock time: a best bid and a best ask, each with a level behind it, null for an empty side,
	 * and the last trade's price, null before the first. Bids are at least 2, so the level behind stays positive.
	 */
	static MarketSnapshot book(UUID instrument, long commandSequence, Instant at, Long bid, Long ask, Long lastPrice) {
		MarketSnapshot book = untraded(instrument, commandSequence);
		book.setGeneratedAt(at.truncatedTo(ChronoUnit.MICROS));
		book.setBids(bid == null ? List.of() : List.of(new BookLevel(bid, 10L, 1), new BookLevel(bid - 1, 5L, 2)));
		book.setAsks(ask == null ? List.of() : List.of(new BookLevel(ask, 10L, 1), new BookLevel(ask + 1, 5L, 2)));
		if (lastPrice != null) {
			PublicTrade trade = new PublicTrade(UUID.randomUUID(), commandSequence, lastPrice, 1L, AggressorSide.BUY,
					at.truncatedTo(ChronoUnit.MICROS));
			book.setRecentTrades(List.of(trade));
			book.setLastTrade(trade);
			book.setLastTradeSequence(commandSequence);
		}
		return book;
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
