package com.mia_princz.mse.marketdata.projection;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.mia_princz.mse.contracts.events.TradeExecuted;
import com.mia_princz.mse.contracts.events.WireEvents;

/**
 * Adds executions to the public tape, and each new one to its instrument's session figures. A trade id is inserted
 * once, so a redelivery, even under a new event id, changes nothing. A different trade under a sequence the instrument
 * already has is a contradiction, not a duplicate, and pauses the feed. Callers run each batch in one transaction, so
 * the tape and the figures never disagree.
 */
public final class TradeProjection {

	static final String TOPIC = "mse.trades.v1";

	private final JdbcClient jdbc;

	public TradeProjection(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * The feed's handler: one trade at a time, or a whole poll at once. {@code added} hears each instrument a new trade
	 * joined, inside the transaction.
	 */
	public MarketFeedConsumer.BatchHandler handler(Consumer<UUID> added) {
		return new MarketFeedConsumer.BatchHandler() {

			@Override
			public boolean apply(byte[] payload) throws IOException {
				TradeExecuted trade = WireEvents.decodeTrade(payload);
				boolean applied = TradeProjection.this.apply(trade);
				if (applied) {
					added.accept(trade.getInstrumentId());
				}
				return applied;
			}

			@Override
			public OptionalInt applyAll(List<byte[]> payloads) throws IOException {
				List<TradeExecuted> trades = new ArrayList<>(payloads.size());
				for (byte[] payload : payloads) {
					trades.add(WireEvents.decodeTrade(payload));
				}
				return OptionalInt.of(TradeProjection.this.applyAll(trades, added));
			}

		};
	}

	/**
	 * A poll of trades in a few statements: the new event ids marked at once, the new trades inserted at once, and the
	 * session figures they change summed per instrument and per minute, since every change to them is order-free. A
	 * new event whose trade was not inserted and is not on the tape is a contradiction and throws, as one by one would.
	 * Returns how many trades were new.
	 */
	public int applyAll(List<TradeExecuted> trades, Consumer<UUID> added) {
		List<Object> marks = new ArrayList<>(trades.size() * 2);
		for (TradeExecuted trade : trades) {
			marks.add(trade.getEventId());
			marks.add(TOPIC);
		}
		Set<UUID> firstSeen = new HashSet<>(jdbc.sql("""
				INSERT INTO processed_event (event_id, topic) VALUES %s ON CONFLICT DO NOTHING RETURNING event_id"""
			.formatted(SnapshotProjection.values(trades.size(), "(?, ?)")))
			.params(marks)
			.query(UUID.class)
			.list());
		// Each new event once, in poll order: a second copy in the same poll is a repeat, as one by one would see it.
		List<TradeExecuted> fresh = trades.stream().filter((trade) -> firstSeen.remove(trade.getEventId())).toList();
		if (fresh.isEmpty()) {
			return 0;
		}
		List<Object> rows = new ArrayList<>(fresh.size() * 9);
		for (TradeExecuted trade : fresh) {
			rows.addAll(List.of(trade.getTradeId(), trade.getSimulationId(), trade.getInstrumentId(), trade.getSymbol(),
					trade.getTradeSequence(), trade.getCommandSequence(), trade.getPriceCents(), trade.getQuantity(),
					Timestamp.from(trade.getExecutedAt())));
		}
		// Rows go in in order, so of two trades claiming one sequence the first is inserted, as one by one.
		Set<UUID> inserted = new HashSet<>(jdbc.sql("""
				INSERT INTO public_trade (trade_id, simulation_id, instrument_id, symbol, trade_sequence,
				    command_sequence, price_cents, quantity, executed_at)
				VALUES %s
				ON CONFLICT DO NOTHING
				RETURNING trade_id""".formatted(SnapshotProjection.values(fresh.size(), "(?, ?, ?, ?, ?, ?, ?, ?, ?)")))
			.params(rows)
			.query(UUID.class)
			.list());
		List<UUID> unknown = fresh.stream()
			.map(TradeExecuted::getTradeId)
			.filter((id) -> !inserted.contains(id))
			.distinct()
			.toList();
		if (!unknown.isEmpty()) {
			Set<UUID> known = new HashSet<>(jdbc.sql("SELECT trade_id FROM public_trade WHERE trade_id IN (%s)"
				.formatted(SnapshotProjection.values(unknown.size(), "?")))
				.params(new ArrayList<Object>(unknown))
				.query(UUID.class)
				.list());
			for (TradeExecuted trade : fresh) {
				if (!known.contains(trade.getTradeId()) && !inserted.contains(trade.getTradeId())) {
					throw new UnusableRecordException("Trade " + trade.getTradeId() + " claims sequence "
							+ trade.getTradeSequence() + " of instrument " + trade.getInstrumentId()
							+ ", which another trade already has");
				}
			}
		}
		// Each inserted trade once, in the order it came.
		Map<UUID, TradeExecuted> joined = new LinkedHashMap<>();
		for (TradeExecuted trade : fresh) {
			if (inserted.contains(trade.getTradeId())) {
				joined.putIfAbsent(trade.getTradeId(), trade);
			}
		}
		if (joined.isEmpty()) {
			// Every new event repeats a trade already on the tape: nothing to add.
			return 0;
		}
		addToSessions(joined.values());
		joined.values().stream().map(TradeExecuted::getInstrumentId).distinct().forEach(added);
		return joined.size();
	}

	/** The new trades' changes to the session figures, summed per instrument and per minute, in two statements. */
	private void addToSessions(java.util.Collection<TradeExecuted> trades) {
		Map<UUID, long[]> sessions = new LinkedHashMap<>();
		Map<UUID, UUID> simulations = new LinkedHashMap<>();
		Map<List<Object>, long[]> minutes = new LinkedHashMap<>();
		for (TradeExecuted trade : trades) {
			long notional = Math.multiplyExact(trade.getPriceCents(), trade.getQuantity());
			// A new session row takes the first trade's simulation, as one by one inserts it.
			simulations.putIfAbsent(trade.getInstrumentId(), trade.getSimulationId());
			// count, volume, notional, high, low, last price, last sequence
			sessions.merge(trade.getInstrumentId(),
					new long[] { 1, trade.getQuantity(), notional, trade.getPriceCents(), trade.getPriceCents(),
							trade.getPriceCents(), trade.getTradeSequence() },
					(sum, one) -> new long[] { sum[0] + 1, Math.addExact(sum[1], one[1]), Math.addExact(sum[2], one[2]),
							Math.max(sum[3], one[3]), Math.min(sum[4], one[4]), one[6] > sum[6] ? one[5] : sum[5],
							Math.max(sum[6], one[6]) });
			Instant minute = trade.getExecutedAt().truncatedTo(ChronoUnit.MINUTES);
			minutes.merge(List.of(trade.getInstrumentId(), minute),
					new long[] { trade.getPriceCents(), trade.getTradeSequence() },
					(kept, one) -> one[1] > kept[1] ? one : kept);
		}
		List<Object> sessionRows = new ArrayList<>();
		sessions.forEach((instrument, sum) -> {
			sessionRows.addAll(List.of(instrument, simulations.get(instrument)));
			for (long value : sum) {
				sessionRows.add(value);
			}
		});
		jdbc.sql("""
				INSERT INTO instrument_session AS s (instrument_id, simulation_id, trade_count, volume, notional_cents,
				    high_cents, low_cents, last_price_cents, last_trade_sequence)
				VALUES %s
				ON CONFLICT (instrument_id) DO UPDATE SET
				    trade_count = s.trade_count + EXCLUDED.trade_count,
				    volume = s.volume + EXCLUDED.volume,
				    notional_cents = s.notional_cents + EXCLUDED.notional_cents,
				    high_cents = GREATEST(s.high_cents, EXCLUDED.high_cents),
				    low_cents = LEAST(s.low_cents, EXCLUDED.low_cents),
				    last_price_cents = CASE WHEN EXCLUDED.last_trade_sequence > s.last_trade_sequence
				        THEN EXCLUDED.last_price_cents ELSE s.last_price_cents END,
				    last_trade_sequence = GREATEST(s.last_trade_sequence, EXCLUDED.last_trade_sequence),
				    updated_at = now()""".formatted(SnapshotProjection.values(sessions.size(), "(?, ?, ?, ?, ?, ?, ?, ?, ?)")))
			.params(sessionRows)
			.update();
		List<Object> minuteRows = new ArrayList<>();
		minutes.forEach((key, last) -> {
			minuteRows.add(key.get(0));
			minuteRows.add(Timestamp.from((Instant) key.get(1)));
			minuteRows.add(last[0]);
			minuteRows.add(last[1]);
		});
		jdbc.sql("""
				INSERT INTO session_minute AS m (instrument_id, minute_start, last_price_cents, last_trade_sequence)
				VALUES %s
				ON CONFLICT (instrument_id, minute_start) DO UPDATE SET
				    last_price_cents = CASE WHEN EXCLUDED.last_trade_sequence > m.last_trade_sequence
				        THEN EXCLUDED.last_price_cents ELSE m.last_price_cents END,
				    last_trade_sequence = GREATEST(m.last_trade_sequence, EXCLUDED.last_trade_sequence)"""
			.formatted(SnapshotProjection.values(minutes.size(), "(?, ?, ?, ?)")))
			.params(minuteRows)
			.update();
	}

	/** Returns whether the trade was new. */
	public boolean apply(TradeExecuted trade) {
		int firstSeen = jdbc.sql("INSERT INTO processed_event (event_id, topic) VALUES (?, ?) ON CONFLICT DO NOTHING")
			.params(trade.getEventId(), TOPIC)
			.update();
		if (firstSeen == 0) {
			return false;
		}
		// No conflict target: a clash on either key keeps the transaction usable; the check below tells which.
		int inserted = jdbc.sql("""
				INSERT INTO public_trade (trade_id, simulation_id, instrument_id, symbol, trade_sequence,
				    command_sequence, price_cents, quantity, executed_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT DO NOTHING""")
			.params(trade.getTradeId(), trade.getSimulationId(), trade.getInstrumentId(), trade.getSymbol(),
					trade.getTradeSequence(), trade.getCommandSequence(), trade.getPriceCents(), trade.getQuantity(),
					Timestamp.from(trade.getExecutedAt()))
			.update();
		if (inserted == 1) {
			addToSession(trade);
			return true;
		}
		boolean known = jdbc.sql("SELECT EXISTS (SELECT 1 FROM public_trade WHERE trade_id = ?)")
			.param(trade.getTradeId())
			.query(Boolean.class)
			.single();
		if (!known) {
			throw new UnusableRecordException("Trade " + trade.getTradeId() + " claims sequence "
					+ trade.getTradeSequence() + " of instrument " + trade.getInstrumentId()
					+ ", which another trade already has");
		}
		return false;
	}

	/**
	 * Every change is order-free: counts and sums add, the extremes take the greater or lesser price, and the last price
	 * is the one with the highest trade sequence. Trades may arrive in any order and still give the same figures.
	 */
	private void addToSession(TradeExecuted trade) {
		long notional = Math.multiplyExact(trade.getPriceCents(), trade.getQuantity());
		jdbc.sql("""
				INSERT INTO instrument_session AS s (instrument_id, simulation_id, trade_count, volume, notional_cents,
				    high_cents, low_cents, last_price_cents, last_trade_sequence)
				VALUES (:instrument, :simulation, 1, :quantity, :notional, :price, :price, :price, :sequence)
				ON CONFLICT (instrument_id) DO UPDATE SET
				    trade_count = s.trade_count + 1,
				    volume = s.volume + EXCLUDED.volume,
				    notional_cents = s.notional_cents + EXCLUDED.notional_cents,
				    high_cents = GREATEST(s.high_cents, EXCLUDED.high_cents),
				    low_cents = LEAST(s.low_cents, EXCLUDED.low_cents),
				    last_price_cents = CASE WHEN EXCLUDED.last_trade_sequence > s.last_trade_sequence
				        THEN EXCLUDED.last_price_cents ELSE s.last_price_cents END,
				    last_trade_sequence = GREATEST(s.last_trade_sequence, EXCLUDED.last_trade_sequence),
				    updated_at = now()""")
			.param("instrument", trade.getInstrumentId())
			.param("simulation", trade.getSimulationId())
			.param("quantity", trade.getQuantity())
			.param("notional", notional)
			.param("price", trade.getPriceCents())
			.param("sequence", trade.getTradeSequence())
			.update();
		jdbc.sql("""
				INSERT INTO session_minute AS m (instrument_id, minute_start, last_price_cents, last_trade_sequence)
				VALUES (:instrument, date_trunc('minute', CAST(:at AS TIMESTAMPTZ)), :price, :sequence)
				ON CONFLICT (instrument_id, minute_start) DO UPDATE SET
				    last_price_cents = CASE WHEN EXCLUDED.last_trade_sequence > m.last_trade_sequence
				        THEN EXCLUDED.last_price_cents ELSE m.last_price_cents END,
				    last_trade_sequence = GREATEST(m.last_trade_sequence, EXCLUDED.last_trade_sequence)""")
			.param("instrument", trade.getInstrumentId())
			.param("at", Timestamp.from(trade.getExecutedAt()))
			.param("price", trade.getPriceCents())
			.param("sequence", trade.getTradeSequence())
			.update();
	}

}
