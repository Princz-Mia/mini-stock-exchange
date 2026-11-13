package com.mia_princz.mse.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.mia_princz.mse.contracts.events.TradeExecuted;
import com.mia_princz.mse.marketdata.projection.TradeProjection;

/**
 * Session figures against a plain oracle: seeded random tapes, applied in a shuffled order with redeliveries, and
 * applied again in another order after the instrument's rows are wiped, as a rebuild from Kafka would. The feeds are off.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "mse.market-feed.enabled=false", "mse.trade-feed.enabled=false",
		"mse.candle-feed.enabled=false" })
class SessionFiguresIT {

	private static final long SEED = 20261112L;

	private static final int RUNS = 25;

	private static final Instant START = Instant.parse("2026-11-12T09:00:00Z");

	@Autowired
	TradeProjection projection;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PostgreSQLContainer postgres;

	/** What the figures must be, worked out directly from the trades. */
	record Expected(long count, long volume, BigDecimal notional, long high, long low, long last,
			List<Map.Entry<Instant, Long>> trend) {

		static Expected of(List<TradeExecuted> trades) {
			long volume = 0;
			BigDecimal notional = BigDecimal.ZERO;
			long high = Long.MIN_VALUE;
			long low = Long.MAX_VALUE;
			TradeExecuted last = trades.get(0);
			TreeMap<Instant, TradeExecuted> minutes = new TreeMap<>();
			for (TradeExecuted trade : trades) {
				volume += trade.getQuantity();
				notional = notional.add(BigDecimal.valueOf(trade.getPriceCents()).multiply(BigDecimal.valueOf(trade.getQuantity())));
				high = Math.max(high, trade.getPriceCents());
				low = Math.min(low, trade.getPriceCents());
				if (trade.getTradeSequence() > last.getTradeSequence()) {
					last = trade;
				}
				Instant minute = trade.getExecutedAt().truncatedTo(ChronoUnit.MINUTES);
				TradeExecuted inMinute = minutes.get(minute);
				if (inMinute == null || trade.getTradeSequence() > inMinute.getTradeSequence()) {
					minutes.put(minute, trade);
				}
			}
			List<Map.Entry<Instant, Long>> trend = new ArrayList<>();
			minutes.forEach((minute, trade) -> trend.add(Map.entry(minute, trade.getPriceCents())));
			return new Expected(trades.size(), volume, notional, high, low, last.getPriceCents(), trend);
		}

	}

	@Test
	void randomTapesInAnyOrderAndAgainAfterARebuildGiveTheOraclesFigures() throws Exception {
		Random random = new Random(SEED);
		for (int run = 0; run < RUNS; run++) {
			UUID instrument = UUID.randomUUID();
			List<TradeExecuted> trades = tape(random, instrument, 1 + random.nextInt(run < 3 ? 3 : 90));
			Expected expected = Expected.of(trades);

			apply(random, trades);
			assertFigures(run, instrument, expected);

			wipe(instrument, trades);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM instrument_session WHERE instrument_id = ?", Long.class,
					instrument)).isZero();
			apply(random, trades);
			assertFigures(run, instrument, expected);
		}
	}

	@Test
	void aTradeCountsOnlyForItsOwnInstrument() {
		UUID traded = UUID.randomUUID();
		UUID quiet = UUID.randomUUID();
		projection.apply(Trades.trade(traded, 1));
		String count = "SELECT count(*) FROM instrument_session WHERE instrument_id = ?";
		assertThat(jdbc.queryForObject(count, Long.class, traded)).isOne();
		assertThat(jdbc.queryForObject(count, Long.class, quiet)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM session_minute WHERE instrument_id = ?", Long.class, quiet))
			.isZero();
	}

	/** Prices across the full range, quantities up to the largest order, times over 90 minutes so the trend is cut. */
	private static List<TradeExecuted> tape(Random random, UUID instrument, int count) {
		List<TradeExecuted> trades = new ArrayList<>();
		long millis = 0;
		for (int sequence = 1; sequence <= count; sequence++) {
			millis += random.nextInt(4) == 0 ? random.nextInt(600_000) : random.nextInt(20_000);
			long price = random.nextInt(5) == 0 ? 1 + random.nextInt(100_000_000) : 9_000 + random.nextInt(2_000);
			trades.add(TradeExecuted.newBuilder(Trades.trade(instrument, sequence))
				.setPriceCents(price)
				.setQuantity(1 + random.nextInt(10_000))
				// Trades in one minute may be stamped out of sequence order: the last price is by sequence, not time.
				.setExecutedAt(START.plusMillis(millis + random.nextInt(3_000)))
				.build());
		}
		return trades;
	}

	/** Every trade once, shuffled, with some delivered again under the same event id and some under a new one. */
	private void apply(Random random, List<TradeExecuted> trades) {
		List<TradeExecuted> deliveries = new ArrayList<>(trades);
		for (TradeExecuted trade : trades) {
			int again = random.nextInt(10);
			if (again == 0) {
				deliveries.add(trade);
			}
			else if (again == 1) {
				deliveries.add(TradeExecuted.newBuilder(trade).setEventId(UUID.randomUUID()).build());
			}
		}
		Collections.shuffle(deliveries, random);
		deliveries.forEach(projection::apply);
	}

	private void assertFigures(int run, UUID instrument, Expected expected) {
		Map<String, Object> row = jdbc.queryForMap("""
				SELECT trade_count, volume, notional_cents, high_cents, low_cents, last_price_cents, last_trade_sequence
				FROM instrument_session WHERE instrument_id = ?""", instrument);
		assertThat(row).as("run %d", run)
			.containsEntry("trade_count", expected.count())
			.containsEntry("volume", expected.volume())
			.containsEntry("high_cents", expected.high())
			.containsEntry("low_cents", expected.low())
			.containsEntry("last_price_cents", expected.last())
			.containsEntry("last_trade_sequence", expected.count());
		assertThat((BigDecimal) row.get("notional_cents")).as("run %d notional", run).isEqualByComparingTo(expected.notional());
		List<Map.Entry<Instant, Long>> trend = jdbc.query("""
				SELECT minute_start, last_price_cents FROM session_minute WHERE instrument_id = ? ORDER BY minute_start""",
				(result, index) -> Map.entry(result.getTimestamp("minute_start").toInstant(),
						result.getLong("last_price_cents")),
				instrument);
		assertThat(trend).as("run %d minutes", run).isEqualTo(expected.trend());
	}

	/** What a rebuild starts from: none of the instrument's trades, events or figures. Only the owner may delete. */
	private void wipe(UUID instrument, List<TradeExecuted> trades) throws Exception {
		try (Connection owner = DriverManager.getConnection(TestcontainersConfiguration.jdbcUrl(postgres, "market_data_db"),
				"market_data_owner", TestcontainersConfiguration.MARKET_DATA_OWNER_PASSWORD)) {
			for (String table : List.of("public_trade", "instrument_session", "session_minute")) {
				try (PreparedStatement delete = owner.prepareStatement("DELETE FROM " + table + " WHERE instrument_id = ?")) {
					delete.setObject(1, instrument);
					delete.executeUpdate();
				}
			}
			try (PreparedStatement delete = owner.prepareStatement("DELETE FROM processed_event WHERE event_id = ANY(?)")) {
				delete.setArray(1, owner.createArrayOf("uuid", trades.stream().map(TradeExecuted::getEventId).toArray()));
				delete.executeUpdate();
			}
		}
	}

}
