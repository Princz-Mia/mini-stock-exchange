package com.mia_princz.mse.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mia_princz.mse.contracts.events.MarketSnapshot;
import com.mia_princz.mse.contracts.events.VenueState;
import com.mia_princz.mse.marketdata.projection.SnapshotProjection;

/** The projection rules on their own: newer replaces, everything else is a no-op. The feed is off. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "mse.market-feed.enabled=false", "mse.trade-feed.enabled=false" })
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

	/** A state the API does not have yet is shown as UNKNOWN, not refused by the table's CHECK. */
	@Test
	void aClosedVenueIsShownAsUnknownUntilTheApiHasIt() {
		UUID instrument = UUID.randomUUID();
		MarketSnapshot closed = Snapshots.snapshot(instrument, 3, 1);
		closed.setVenueState(VenueState.CLOSED);

		assertThat(projection.apply(closed)).isTrue();

		assertThat(jdbc.queryForObject("SELECT venue_state FROM market_snapshot WHERE instrument_id = ?", String.class,
				instrument)).isEqualTo("UNKNOWN");
	}

	/** An operator's edit during setup reaches the instrument; a late copy of the old price does not undo it. */
	@Test
	void aNewerReferencePriceReplacesTheStoredOne() {
		UUID instrument = UUID.randomUUID();
		MarketSnapshot added = Snapshots.snapshot(instrument, 4, 0);
		MarketSnapshot edited = Snapshots.snapshot(instrument, 7, 0);
		edited.getRules().setReferencePriceCents(12_050);
		projection.apply(added);

		assertThat(projection.apply(edited)).isTrue();
		assertThat(projection.apply(Snapshots.snapshot(instrument, 5, 0))).isFalse();

		assertThat(jdbc.queryForObject("SELECT reference_price_cents FROM instrument_projection WHERE instrument_id = ?",
				Long.class, instrument)).isEqualTo(12_050);
	}

	/** The fields added in the event's second version change nothing in the projection yet. */
	@Test
	void theAddedFieldsAreAcceptedAndChangeNothingYet() {
		UUID instrument = UUID.randomUUID();
		MarketSnapshot named = Snapshots.snapshot(instrument, 3, 1);
		named.setDisplayName("Nova Dynamics");

		assertThat(projection.apply(named)).isTrue();

		assertThat(jdbc.queryForObject("SELECT symbol FROM instrument_projection WHERE instrument_id = ?",
				String.class, instrument)).isEqualTo("NOVA");
		assertThat(sequence(instrument)).isEqualTo(3);
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
