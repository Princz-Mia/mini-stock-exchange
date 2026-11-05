package com.mia_princz.mse.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mia_princz.mse.contracts.events.CandleUpdated;
import com.mia_princz.mse.marketdata.projection.CandleProjection;
import com.mia_princz.mse.marketdata.projection.UnusableRecordException;

/** The candle rules on their own: the newest complete aggregate wins, per epoch. The feeds are off. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "mse.market-feed.enabled=false", "mse.trade-feed.enabled=false",
		"mse.candle-feed.enabled=false" })
class CandleProjectionIT {

	@Autowired
	CandleProjection projection;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void aNewMinuteIsStoredExactly() {
		UUID instrument = UUID.randomUUID();
		// The largest possible volume at one price: a notional far beyond a long.
		BigDecimal notional = BigDecimal.valueOf(10_001).multiply(BigDecimal.valueOf(Long.MAX_VALUE));
		CandleUpdated candle = CandleUpdated.newBuilder(Candles.candle(1, instrument, Candles.MINUTE, 3))
			.setHighCents(10_001)
			.setCloseCents(10_001)
			.setVolume(Long.MAX_VALUE)
			.setNotionalCents(notional)
			.build();

		assertThat(projection.apply(candle)).isTrue();

		Map<String, Object> row = row(1, instrument);
		assertThat(row).containsEntry("open_cents", 10_001L)
			.containsEntry("close_cents", 10_001L)
			.containsEntry("trade_count", 3L)
			.containsEntry("source_max_trade_sequence", 3L)
			.containsEntry("symbol", "NOVA");
		assertThat((BigDecimal) row.get("notional_cents")).isEqualByComparingTo(notional);
	}

	@Test
	void onlyAHigherSequenceReplacesTheMinute() {
		UUID instrument = UUID.randomUUID();
		CandleUpdated two = Candles.candle(1, instrument, Candles.MINUTE, 2);
		CandleUpdated three = Candles.candle(1, instrument, Candles.MINUTE, 3);

		assertThat(projection.apply(two)).isTrue();
		assertThat(projection.apply(three)).isTrue();
		assertThat(projection.apply(two)).as("redelivered").isFalse();
		assertThat(projection.apply(CandleUpdated.newBuilder(two).setEventId(UUID.randomUUID()).build()))
			.as("an older version, arriving late")
			.isFalse();
		assertThat(projection.apply(CandleUpdated.newBuilder(three).setEventId(UUID.randomUUID()).build()))
			.as("the same version again, under another event id")
			.isFalse();

		assertThat(row(1, instrument)).containsEntry("source_max_trade_sequence", 3L)
			.containsEntry("volume", 15L)
			.containsEntry("close_cents", 10_003L);
	}

	@Test
	void anotherEpochBuildsAlongsideAndNeverOverwrites() {
		UUID instrument = UUID.randomUUID();
		projection.apply(Candles.candle(1, instrument, Candles.MINUTE, 3));

		assertThat(projection.apply(Candles.candle(2, instrument, Candles.MINUTE, 1))).isTrue();

		assertThat(row(1, instrument)).containsEntry("trade_count", 3L);
		assertThat(row(2, instrument)).containsEntry("trade_count", 1L);
	}

	@Test
	void twoDifferentAggregatesForTheSameSequenceStopTheFeed() {
		UUID instrument = UUID.randomUUID();
		CandleUpdated candle = Candles.candle(1, instrument, Candles.MINUTE, 2);
		projection.apply(candle);

		assertThatThrownBy(() -> projection.apply(CandleUpdated.newBuilder(candle)
			.setEventId(UUID.randomUUID())
			.setCloseCents(candle.getLowCents())
			.build())).isInstanceOf(UnusableRecordException.class).hasMessageContaining("trade sequence 2");
	}

	@Test
	void theDatabaseRefusesImpossibleCandles() {
		UUID instrument = UUID.randomUUID();
		String insert = """
				INSERT INTO candle_1m (computation_epoch, simulation_id, instrument_id, bucket_start, symbol, open_cents,
				    high_cents, low_cents, close_cents, volume, trade_count, notional_cents, source_max_trade_sequence,
				    generated_at)
				VALUES (1, ?, ?, ?, 'NOVA', ?, ?, ?, ?, ?, ?, ?, 1, now())""";
		Timestamp minute = Timestamp.from(Candles.MINUTE);
		assertState("23514", () -> jdbc.update(insert, Snapshots.SIMULATION, instrument,
				Timestamp.from(Candles.MINUTE.plusSeconds(5)), 100, 100, 100, 100, 1, 1, 100), "mid-minute bucket");
		assertState("23514", () -> jdbc.update(insert, Snapshots.SIMULATION, instrument, minute, 100, 90, 80, 85, 1, 1,
				90), "open above high");
		assertState("23514", () -> jdbc.update(insert, Snapshots.SIMULATION, instrument, minute, 100, 110, 90, 100, 2,
				3, 200), "more trades than shares");
		assertState("23514", () -> jdbc.update(insert, Snapshots.SIMULATION, instrument, minute, 100, 110, 90, 100, 2,
				1, 500), "notional above high times volume");
		projection.apply(Candles.candle(1, instrument, Candles.MINUTE, 1));
		assertState("42501", () -> jdbc.update("DELETE FROM candle_1m WHERE instrument_id = ?", instrument),
				"the runtime role cannot delete candles");
	}

	private Map<String, Object> row(long epoch, UUID instrument) {
		return jdbc.queryForMap("""
				SELECT symbol, open_cents, close_cents, volume, trade_count, notional_cents, source_max_trade_sequence
				FROM candle_1m WHERE computation_epoch = ? AND instrument_id = ? AND bucket_start = ?""", epoch,
				instrument, Timestamp.from(Candles.MINUTE));
	}

	private static void assertState(String sqlState, Runnable statement, String why) {
		assertThatThrownBy(statement::run).as(why).satisfies((thrown) -> {
			Throwable cause = NestedExceptionUtils.getMostSpecificCause(thrown);
			assertThat(cause).isInstanceOf(SQLException.class);
			assertThat(((SQLException) cause).getSQLState()).as(cause.getMessage()).isEqualTo(sqlState);
		});
	}

}
