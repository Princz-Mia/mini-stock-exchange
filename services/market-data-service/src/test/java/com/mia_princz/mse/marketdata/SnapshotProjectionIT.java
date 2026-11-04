package com.mia_princz.mse.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mia_princz.mse.contracts.events.MarketSnapshot;
import com.mia_princz.mse.marketdata.projection.SnapshotProjection;

/** The projection rules on their own: newer replaces, everything else is a no-op. The feed is off. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "mse.market-feed.enabled=false")
class SnapshotProjectionIT {

	@Autowired
	SnapshotProjection projection;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void newerSnapshotReplacesTheWholeMarket() {
		UUID instrument = UUID.randomUUID();
		assertThat(projection.apply(Snapshots.snapshot(instrument, 5, 3))).isTrue();

		assertThat(projection.apply(Snapshots.snapshot(instrument, 6, 1))).isTrue();

		assertThat(bidLevels(instrument)).as("depth replaced, not merged").isEqualTo(1);
		assertThat(sequence(instrument)).isEqualTo(6);
		assertThat(jdbc.queryForObject("SELECT last_price_cents FROM market_snapshot WHERE instrument_id = ?",
				Long.class, instrument)).isEqualTo(10_000);
		assertThat(jdbc.queryForObject("SELECT symbol FROM instrument_projection WHERE instrument_id = ?",
				String.class, instrument)).isEqualTo("NOVA");
	}

	@Test
	void olderSnapshotChangesNothing() {
		UUID instrument = UUID.randomUUID();
		projection.apply(Snapshots.snapshot(instrument, 9, 2));

		assertThat(projection.apply(Snapshots.snapshot(instrument, 8, 5))).isFalse();

		assertThat(sequence(instrument)).isEqualTo(9);
		assertThat(bidLevels(instrument)).isEqualTo(2);
	}

	@Test
	void redeliveredEventIsIgnoredAndRecordedOnce() {
		UUID instrument = UUID.randomUUID();
		MarketSnapshot snapshot = Snapshots.snapshot(instrument, 3, 2);
		projection.apply(snapshot);

		assertThat(projection.apply(snapshot)).isFalse();

		assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ?", Long.class,
				snapshot.getEventId())).isEqualTo(1);
	}

	@Test
	void sameSequenceUnderANewEventIdIsNotAppliedTwice() {
		UUID instrument = UUID.randomUUID();
		projection.apply(Snapshots.snapshot(instrument, 4, 2));

		assertThat(projection.apply(Snapshots.snapshot(instrument, 4, 7))).isFalse();

		assertThat(bidLevels(instrument)).isEqualTo(2);
	}

	private long sequence(UUID instrument) {
		return jdbc.queryForObject("SELECT source_command_sequence FROM market_snapshot WHERE instrument_id = ?",
				Long.class, instrument);
	}

	private int bidLevels(UUID instrument) {
		return jdbc.queryForObject("SELECT jsonb_array_length(bids) FROM market_snapshot WHERE instrument_id = ?",
				Integer.class, instrument);
	}

}
