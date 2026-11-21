package com.mia_princz.mse.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mia_princz.mse.contracts.events.AggressorSide;
import com.mia_princz.mse.contracts.events.BookLevel;
import com.mia_princz.mse.contracts.events.MarketRules;
import com.mia_princz.mse.contracts.events.MarketSnapshot;
import com.mia_princz.mse.contracts.events.PublicTrade;
import com.mia_princz.mse.contracts.events.TradeExecuted;
import com.mia_princz.mse.contracts.events.VenueState;
import com.mia_princz.mse.contracts.events.WireEvents;
import com.mia_princz.mse.marketdata.projection.SnapshotProjection;
import com.mia_princz.mse.marketdata.projection.TradeProjection;

/**
 * A poll applied at once must leave the projection exactly as applying its records one by one would. Seeded streams
 * of snapshots and trades, with repeats of an event, the same trade or sequence under a new event id, snapshots older
 * than one already seen, display names set and cleared, and trades out of order across minutes, are applied once
 * record by record and once in polls of random size, each run on its own instruments and ids. Every projected row
 * ends the same. The feeds are off.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "mse.market-feed.enabled=false", "mse.trade-feed.enabled=false",
		"mse.candle-feed.enabled=false" })
class BatchedProjectionIT {

	private static final Instant START = Instant.parse("2026-11-18T09:00:00Z");

	private static final int INSTRUMENTS = 4;

	@Autowired
	SnapshotProjection snapshots;

	@Autowired
	TradeProjection trades;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Test
	void snapshotsAppliedAtOnceEndAsSnapshotsAppliedOneByOne() {
		List<MarketSnapshot> stream = snapshotStream(new Random(1_118L), 900);
		Run oneByOne = new Run();
		Run atOnce = new Run();

		int[] changedOneByOne = { 0 };
		inTransaction(() -> stream.stream().map(oneByOne::snapshot).forEach((snapshot) -> {
			if (snapshots.apply(snapshot)) {
				changedOneByOne[0]++;
			}
		}));
		Random polls = new Random(7L);
		int changedAtOnce = 0;
		List<MarketSnapshot> mapped = stream.stream().map(atOnce::snapshot).toList();
		for (int from = 0; from < mapped.size();) {
			List<MarketSnapshot> poll = mapped.subList(from, Math.min(mapped.size(), from + 1 + polls.nextInt(40)));
			int[] changed = { 0 };
			inTransaction(() -> changed[0] = snapshots.applyAll(poll, (instrument) -> {
			}).orElseThrow());
			changedAtOnce += changed[0];
			from += poll.size();
		}

		assertThat(changedAtOnce).as("snapshots counted as changing a market")
			.isEqualTo(changedOneByOne[0])
			.isGreaterThan(300);
		assertThat(catalogue(atOnce)).isEqualTo(catalogue(oneByOne)).hasSize(INSTRUMENTS);
		assertThat(markets(atOnce)).isEqualTo(markets(oneByOne)).hasSize(INSTRUMENTS);
		assertThat(spreads(atOnce)).isEqualTo(spreads(oneByOne)).hasSizeGreaterThan(INSTRUMENTS * 20);
		assertThat(seen(atOnce)).isEqualTo(seen(oneByOne));
	}

	@Test
	void tradesAppliedAtOnceEndAsTradesAppliedOneByOne() {
		List<TradeExecuted> stream = tradeStream(new Random(2_118L), 900);
		Run oneByOne = new Run();
		Run atOnce = new Run();

		int[] newOneByOne = { 0 };
		inTransaction(() -> stream.stream().map(oneByOne::trade).forEach((trade) -> {
			if (trades.apply(trade)) {
				newOneByOne[0]++;
			}
		}));
		Random polls = new Random(9L);
		int newAtOnce = 0;
		List<TradeExecuted> mapped = stream.stream().map(atOnce::trade).toList();
		for (int from = 0; from < mapped.size();) {
			List<TradeExecuted> poll = mapped.subList(from, Math.min(mapped.size(), from + 1 + polls.nextInt(40)));
			int[] added = { 0 };
			inTransaction(() -> added[0] = trades.applyAll(poll, (instrument) -> {
			}));
			newAtOnce += added[0];
			from += poll.size();
		}

		assertThat(newAtOnce).as("new trades counted").isEqualTo(newOneByOne[0]).isGreaterThan(300);
		assertThat(tape(atOnce)).isEqualTo(tape(oneByOne));
		assertThat(sessions(atOnce)).isEqualTo(sessions(oneByOne)).hasSize(INSTRUMENTS);
		assertThat(minutes(atOnce)).isEqualTo(minutes(oneByOne)).hasSizeGreaterThan(INSTRUMENTS * 5);
		assertThat(seen(atOnce)).isEqualTo(seen(oneByOne));
	}

	/**
	 * A poll applied at once names each instrument it changed, once, for the live frames sent after the commit: an
	 * instrument whose snapshot was older than the one stored is not named, nor one whose trade was already on the tape.
	 * It counts what one by one would: both rising snapshots of the first instrument.
	 */
	@Test
	void aPollAppliedAtOnceNamesTheInstrumentsItChanged() {
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		UUID stale = UUID.randomUUID();
		inTransaction(() -> snapshots.apply(Snapshots.snapshot(stale, 9, 1)));
		List<UUID> changed = new ArrayList<>();

		inTransaction(() -> assertThat(snapshots.applyAll(List.of(Snapshots.snapshot(first, 1, 1),
				Snapshots.snapshot(first, 2, 1), Snapshots.snapshot(second, 1, 1), Snapshots.snapshot(stale, 3, 1)),
				changed::add)).hasValue(3));

		assertThat(changed).containsExactlyInAnyOrder(first, second);

		TradeExecuted known = Trades.trade(first, 1);
		inTransaction(() -> trades.apply(known));
		List<UUID> added = new ArrayList<>();
		inTransaction(() -> assertThat(trades.applyAll(List.of(Trades.trade(second, 1), Trades.trade(second, 2),
				TradeExecuted.newBuilder(known).setEventId(UUID.randomUUID()).build()), added::add)).isEqualTo(2));

		assertThat(added).containsExactly(second);
	}

	/**
	 * One by one, PostgreSQL checks every new snapshot's rows against the tables' constraints, even one an older
	 * sequence keeps from changing anything, and the feed pauses at a refused one. So a poll applied at once fails
	 * wherever the refused snapshot sits, and the feed falls back to one by one to find it: before the newest of its
	 * instrument, older than it, or tied with it.
	 */
	@Test
	void aSnapshotTheTablesRefuseFailsThePollWhereverItSits() {
		UUID instrument = UUID.randomUUID();
		MarketSnapshot lowercase = Snapshots.snapshot(instrument, 1, 1);
		lowercase.setSymbol("nova");
		MarketSnapshot negative = Snapshots.snapshot(instrument, 3, 1);
		negative.setLastTradeSequence(-1L);
		MarketSnapshot tied = Snapshots.snapshot(instrument, 5, 1);
		tied.getRules().setTickCents(0L);

		for (List<MarketSnapshot> poll : List.of(List.of(lowercase, Snapshots.snapshot(instrument, 2, 1)),
				List.of(Snapshots.snapshot(instrument, 5, 1), negative),
				List.of(Snapshots.snapshot(instrument, 5, 1), tied))) {
			assertThatThrownBy(() -> inTransaction(() -> snapshots.applyAll(poll, (changed) -> {
			}))).isInstanceOf(DataIntegrityViolationException.class);
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM instrument_projection WHERE instrument_id = ?", Long.class,
				instrument)).as("every poll rolled back").isZero();
	}

	/**
	 * Records that disagree inside a poll, as only a faulty producer would send them, end as one by one leaves them: a
	 * second event under a used id is a repeat even when it carries another trade, and a new row takes the first
	 * record's simulation.
	 */
	@Test
	void disagreeingRecordsInOnePollEndAsOneByOne() {
		UUID otherSimulation = UUID.randomUUID();
		Run oneByOne = new Run();
		Run atOnce = new Run();
		TradeExecuted first = Trades.trade(new UUID(2, 0), 1);
		List<TradeExecuted> tradePoll = List.of(first,
				TradeExecuted.newBuilder(Trades.trade(new UUID(2, 0), 2)).setEventId(first.getEventId()).build(),
				TradeExecuted.newBuilder(Trades.trade(new UUID(2, 1), 1)).setSimulationId(otherSimulation).build(),
				Trades.trade(new UUID(2, 1), 2));
		MarketSnapshot moved = Snapshots.snapshot(new UUID(2, 2), 1, 1);
		moved.setSimulationId(otherSimulation);
		List<MarketSnapshot> snapshotPoll = List.of(moved, Snapshots.snapshot(new UUID(2, 2), 2, 1));

		int[] counts = { 0, 0 };
		inTransaction(() -> {
			tradePoll.stream().map(oneByOne::trade).forEach(trades::apply);
			snapshotPoll.stream().map(oneByOne::snapshot).forEach(snapshots::apply);
		});
		inTransaction(() -> {
			counts[0] = trades.applyAll(tradePoll.stream().map(atOnce::trade).toList(), (instrument) -> {
			});
			counts[1] = snapshots.applyAll(snapshotPoll.stream().map(atOnce::snapshot).toList(), (instrument) -> {
			}).orElseThrow();
		});

		assertThat(counts).containsExactly(3, 2);
		assertThat(tape(atOnce)).isEqualTo(tape(oneByOne)).hasSize(3);
		assertThat(sessions(atOnce)).isEqualTo(sessions(oneByOne));
		assertThat(simulations(atOnce)).isEqualTo(simulations(oneByOne)).hasSize(4);
	}

	/** A poll whose new events all repeat trades already on the tape adds nothing, without falling back. */
	@Test
	void aPollOfTradesAlreadyOnTheTapeAddsNothing() {
		UUID instrument = UUID.randomUUID();
		TradeExecuted first = Trades.trade(instrument, 1);
		TradeExecuted second = Trades.trade(instrument, 2);
		inTransaction(() -> {
			trades.apply(first);
			trades.apply(second);
		});
		List<UUID> added = new ArrayList<>();
		int[] count = { -1 };

		inTransaction(() -> count[0] = trades.applyAll(
				List.of(TradeExecuted.newBuilder(first).setEventId(UUID.randomUUID()).build(),
						TradeExecuted.newBuilder(second).setEventId(UUID.randomUUID()).build()),
				added::add));

		assertThat(count[0]).isZero();
		assertThat(added).isEmpty();
		assertThat(jdbc.queryForObject("SELECT trade_count FROM instrument_session WHERE instrument_id = ?", Long.class,
				instrument)).isEqualTo(2);
	}

	/**
	 * Snapshots per instrument mostly rising, with repeats, older ones, and one sequence under a new event id. Every
	 * column moves with the sequence, so a value written from the wrong snapshot or into the wrong column shows.
	 */
	private static List<MarketSnapshot> snapshotStream(Random random, int count) {
		long[] sequence = new long[INSTRUMENTS];
		List<MarketSnapshot> stream = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			int roll = random.nextInt(100);
			if (roll < 10 && !stream.isEmpty()) {
				// The same event again, as a redelivery.
				stream.add(stream.get(random.nextInt(stream.size())));
				continue;
			}
			int instrument = random.nextInt(INSTRUMENTS);
			long at = roll < 20 ? Math.max(1, sequence[instrument] - random.nextInt(5)) : ++sequence[instrument];
			String name = switch (random.nextInt(3)) {
				case 0 -> null;
				case 1 -> "Nova";
				default -> "Nova Dynamics " + random.nextInt(9);
			};
			List<BookLevel> bids = new ArrayList<>();
			for (int level = 0; level < random.nextInt(4); level++) {
				bids.add(new BookLevel(10_000L - level, 1L + random.nextInt(50), 1 + random.nextInt(3)));
			}
			List<BookLevel> asks = new ArrayList<>();
			for (int level = 0; level < random.nextInt(4); level++) {
				asks.add(new BookLevel(10_100L + level, 1L + random.nextInt(50), 1 + random.nextInt(3)));
			}
			long minimum = 1 + random.nextInt(9);
			MarketRules rules = new MarketRules(1 + (int) (at / 10), 10_000L + random.nextInt(500), 1L + random.nextInt(5),
					1L + random.nextInt(7), minimum, minimum + random.nextInt(10_000), 100_000_000L + random.nextInt(1_000),
					20, 1_000);
			PublicTrade last = new PublicTrade(new UUID(0, i + 1), at, 9_000L + random.nextInt(2_000), 1L, AggressorSide.BUY,
					START.plusSeconds(at));
			stream.add(MarketSnapshot.newBuilder()
				.setEventId(new UUID(1, i + 1))
				.setSimulationId(Snapshots.SIMULATION)
				.setInstrumentId(new UUID(2, instrument))
				.setSymbol(random.nextBoolean() ? "NOVA" : "NOVB")
				.setDisplayName(name)
				.setVenueState(random.nextBoolean() ? VenueState.OPEN : VenueState.HALTED)
				.setRules(rules)
				.setBids(bids)
				.setAsks(asks)
				.setRecentTrades(List.of(last))
				.setLastTrade(random.nextInt(5) == 0 ? null : last)
				.setLastTradeSequence(at)
				.setSourceCommandSequence(at)
				// Seven seconds a sequence, so each instrument's snapshots span many minutes, several to a minute.
				.setGeneratedAt(START.plusSeconds(at * 7))
				.build());
		}
		return stream;
	}

	/** Trades per instrument, delivered somewhat out of order, with repeats and the same trade under a new event id. */
	private static List<TradeExecuted> tradeStream(Random random, int count) {
		long[] sequence = new long[INSTRUMENTS];
		List<TradeExecuted> stream = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			int roll = random.nextInt(100);
			if (roll < 8 && !stream.isEmpty()) {
				stream.add(stream.get(random.nextInt(stream.size())));
				continue;
			}
			if (roll < 15 && !stream.isEmpty()) {
				TradeExecuted again = stream.get(random.nextInt(stream.size()));
				stream.add(TradeExecuted.newBuilder(again).setEventId(new UUID(3, i + 1)).build());
				continue;
			}
			int instrument = random.nextInt(INSTRUMENTS);
			long at = ++sequence[instrument];
			stream.add(TradeExecuted.newBuilder()
				.setEventId(new UUID(3, i + 1))
				.setSimulationId(Snapshots.SIMULATION)
				.setInstrumentId(new UUID(2, instrument))
				.setSymbol("NOVA")
				.setTradeId(new UUID(4, i + 1))
				.setTradeSequence(at)
				.setCommandSequence(at)
				.setExecutedAt(START.plus(at * 7 + random.nextInt(30), ChronoUnit.SECONDS))
				.setPriceCents(9_000L + random.nextInt(2_000))
				.setQuantity(1 + random.nextInt(20))
				.build());
		}
		// Neighbours swapped now and then, as partitions and retries deliver them.
		for (int i = 1; i < stream.size(); i++) {
			if (random.nextInt(10) == 0) {
				stream.set(i - 1, stream.set(i, stream.get(i - 1)));
			}
		}
		return stream;
	}

	/** One run's own instruments and ids: each id in the stream is replaced by a new one, the same each time. */
	private static final class Run {

		private final Map<UUID, UUID> ids = new HashMap<>();

		private final List<UUID> instruments = new ArrayList<>();

		private UUID id(UUID original) {
			return ids.computeIfAbsent(original, (ignored) -> UUID.randomUUID());
		}

		UUID instrument(int index) {
			while (instruments.size() <= index) {
				instruments.add(id(new UUID(2, instruments.size())));
			}
			return instruments.get(index);
		}

		/** A copy through the wire format, as the feed reads it, with this run's ids. */
		MarketSnapshot snapshot(MarketSnapshot original) {
			instrument(INSTRUMENTS - 1);
			MarketSnapshot copy;
			try {
				copy = WireEvents.decodeSnapshot(WireEvents.encode(original));
			}
			catch (java.io.IOException ex) {
				throw new java.io.UncheckedIOException(ex);
			}
			copy.setEventId(id(original.getEventId()));
			copy.setInstrumentId(id(original.getInstrumentId()));
			return copy;
		}

		TradeExecuted trade(TradeExecuted original) {
			instrument(INSTRUMENTS - 1);
			return TradeExecuted.newBuilder(original)
				.setEventId(id(original.getEventId()))
				.setInstrumentId(id(original.getInstrumentId()))
				.setTradeId(id(original.getTradeId()))
				.build();
		}

		/** The run's ids in a query, as instrument numbers. */
		String in() {
			return String.join(", ", instruments.stream().map((id) -> "'" + id + "'").toList());
		}

		UnaryOperator<String> name() {
			return (id) -> "instrument " + instruments.indexOf(UUID.fromString(id));
		}

	}

	private List<String> catalogue(Run run) {
		return rows(run, """
				SELECT instrument_id::text, simulation_id || ' ' || symbol || ' ' || coalesce(display_name, '-') || ' '
				    || rule_version || ' ' || reference_price_cents || ' ' || tick_cents || ' ' || lot_size || ' '
				    || min_quantity || ' ' || max_quantity || ' ' || max_price_cents || ' ' || source_command_sequence
				FROM instrument_projection WHERE instrument_id IN (%s)""");
	}

	private List<String> markets(Run run) {
		return rows(run, """
				SELECT instrument_id::text, simulation_id || ' ' || venue_state || ' ' || bids::text || ' ' || asks::text || ' '
				    || recent_trades::text || ' ' || coalesce(last_price_cents::text, '-') || ' ' || last_trade_sequence
				    || ' ' || source_command_sequence || ' ' || generated_at
				FROM market_snapshot WHERE instrument_id IN (%s)""");
	}

	private List<String> tape(Run run) {
		return rows(run, """
				SELECT instrument_id::text, trade_sequence || ' ' || command_sequence || ' ' || price_cents || ' '
				    || quantity || ' ' || executed_at
				FROM public_trade WHERE instrument_id IN (%s)""");
	}

	private List<String> sessions(Run run) {
		return rows(run, """
				SELECT instrument_id::text, trade_count || ' ' || volume || ' ' || notional_cents || ' ' || high_cents
				    || ' ' || low_cents || ' ' || last_price_cents || ' ' || last_trade_sequence
				FROM instrument_session WHERE instrument_id IN (%s)""");
	}

	private List<String> minutes(Run run) {
		return rows(run, """
				SELECT instrument_id::text, minute_start || ' ' || last_price_cents || ' ' || last_trade_sequence
				FROM session_minute WHERE instrument_id IN (%s)""");
	}

	private List<String> spreads(Run run) {
		return rows(run, """
				SELECT instrument_id::text, minute_start || ' ' || first_sequence || ' ' || last_sequence || ' '
				    || coalesce(best_bid_cents::text, '-') || ' ' || coalesce(best_ask_cents::text, '-') || ' '
				    || coalesce(last_price_cents::text, '-')
				FROM spread_minute WHERE instrument_id IN (%s)""");
	}

	/** Each row's simulation, by table. */
	private List<String> simulations(Run run) {
		return rows(run, """
				SELECT instrument_id::text, 'catalogue ' || simulation_id FROM instrument_projection WHERE instrument_id IN (%1$s)
				UNION ALL SELECT instrument_id::text, 'market ' || simulation_id FROM market_snapshot WHERE instrument_id IN (%1$s)
				UNION ALL SELECT instrument_id::text, 'session ' || simulation_id FROM instrument_session WHERE instrument_id IN (%1$s)""");
	}

	/** The event ids the run marked seen, as the stream's own. */
	private long seen(Run run) {
		return jdbc.queryForObject("SELECT count(*) FROM processed_event WHERE event_id IN (%s)"
			.formatted(String.join(", ", run.ids.values().stream().map((id) -> "'" + id + "'").toList())), Long.class);
	}

	/** Rows of {instrument id, values}, each instrument named by its number in the run, sorted. */
	private List<String> rows(Run run, String sql) {
		UnaryOperator<String> name = run.name();
		List<String> rows = new ArrayList<>(jdbc.query(sql.formatted(run.in()),
				(row, index) -> name.apply(row.getString(1)) + ": " + row.getString(2)));
		rows.sort(null);
		return rows;
	}

	private void inTransaction(Runnable work) {
		new TransactionTemplate(transactionManager).executeWithoutResult((status) -> work.run());
	}

}
