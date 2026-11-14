package com.mia_princz.mse.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mia_princz.mse.contracts.events.MarketSnapshot;
import com.mia_princz.mse.contracts.events.VenueState;
import com.mia_princz.mse.marketdata.projection.SnapshotProjection;

/** The projection rules on their own: newer replaces or removes, everything else is a no-op. The feed is off. */
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

	/** The display name, added in the event's second version, changes nothing in the projection yet. */
	@Test
	void theDisplayNameIsAcceptedAndChangesNothingYet() {
		UUID instrument = UUID.randomUUID();
		MarketSnapshot named = Snapshots.snapshot(instrument, 3, 1);
		named.setDisplayName("Nova Dynamics");

		assertThat(projection.apply(named)).isTrue();

		assertThat(jdbc.queryForObject("SELECT symbol FROM instrument_projection WHERE instrument_id = ?",
				String.class, instrument)).isEqualTo("NOVA");
		assertThat(sequence(instrument)).isEqualTo(3);
	}

	/** An instrument deleted during setup leaves both projections, its market first. */
	@Test
	void aRemovalDeletesTheInstrumentAndItsMarket() {
		UUID instrument = UUID.randomUUID();
		projection.apply(Snapshots.untraded(instrument, 4));

		assertThat(projection.apply(Snapshots.removed(instrument, 6))).isTrue();

		assertThat(rows("market_snapshot", instrument)).isZero();
		assertThat(rows("instrument_projection", instrument)).isZero();
	}

	/** On a rebuild from the compacted topic, a removal may be the only record left for its instrument. */
	@Test
	void aRemovalOfAnInstrumentNeverSeenChangesNothing() {
		UUID instrument = UUID.randomUUID();
		MarketSnapshot removal = Snapshots.removed(instrument, 6);

		assertThat(projection.apply(removal)).isFalse();

		assertThat(rows("market_snapshot", instrument)).isZero();
		assertThat(rows("instrument_projection", instrument)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ?", Long.class,
				removal.getEventId())).isEqualTo(1);
	}

	/** A redelivered add after the removal is a duplicate, so the instrument does not come back. */
	@Test
	void aRedeliveredAddDoesNotBringARemovedInstrumentBack() {
		UUID instrument = UUID.randomUUID();
		MarketSnapshot added = Snapshots.untraded(instrument, 4);
		projection.apply(added);
		projection.apply(Snapshots.removed(instrument, 6));

		assertThat(projection.apply(added)).isFalse();

		assertThat(rows("market_snapshot", instrument)).isZero();
		assertThat(rows("instrument_projection", instrument)).isZero();
	}

	/**
	 * A rebuild after compaction dropped the first add but not the relay's resent copy: the removal, the copy under an
	 * event id the emptied table has not seen, then the removal's own copy. The copy of the removal deletes the add
	 * again, although its event id was seen.
	 */
	@Test
	void aResentRemovalDeletesAResentAddAfterARebuild() {
		UUID instrument = UUID.randomUUID();
		MarketSnapshot removal = Snapshots.removed(instrument, 6);
		projection.apply(removal);
		projection.apply(Snapshots.untraded(instrument, 4));
		assertThat(rows("instrument_projection", instrument)).isOne();

		assertThat(projection.apply(removal)).isTrue();

		assertThat(rows("market_snapshot", instrument)).isZero();
		assertThat(rows("instrument_projection", instrument)).isZero();
		assertThat(projection.apply(removal)).as("nothing left to delete").isFalse();
	}

	/** A removal no newer than the stored state is late, like any older snapshot. */
	@Test
	void aRemovalNoNewerThanTheStoredStateLeavesTheInstrument() {
		UUID instrument = UUID.randomUUID();
		projection.apply(Snapshots.untraded(instrument, 9));

		assertThat(projection.apply(Snapshots.removed(instrument, 7))).isFalse();
		assertThat(projection.apply(Snapshots.removed(instrument, 9))).isFalse();

		assertThat(rows("market_snapshot", instrument)).isOne();
		assertThat(rows("instrument_projection", instrument)).isOne();
		assertThat(sequence(instrument)).isEqualTo(9);
	}

	/** The symbol may be added again under a new id; removing the old id leaves the new one. */
	@Test
	void aRemovalLeavesOtherInstrumentsWithTheSameSymbol() {
		UUID old = UUID.randomUUID();
		UUID readded = UUID.randomUUID();
		projection.apply(Snapshots.untraded(old, 4));
		projection.apply(Snapshots.untraded(readded, 5));

		assertThat(projection.apply(Snapshots.removed(old, 6))).isTrue();

		assertThat(rows("instrument_projection", old)).isZero();
		assertThat(rows("market_snapshot", readded)).isOne();
		assertThat(rows("instrument_projection", readded)).isOne();
	}

	/** The runtime role may delete an instrument and its market, and nothing else a removal could reach. */
	@Test
	void theRuntimeRoleDeletesOnlyInstrumentsAndTheirMarkets() {
		// The privilege is checked before any row is read, so WHERE false deletes nothing even if it were granted.
		for (String table : List.of("processed_event", "projection_checkpoint", "public_trade", "instrument_session",
				"session_minute")) {
			assertThatThrownBy(() -> jdbc.update("DELETE FROM " + table + " WHERE false"))
				.as(table)
				.satisfies((thrown) -> assertThat(
						((SQLException) NestedExceptionUtils.getMostSpecificCause(thrown)).getSQLState())
					.isEqualTo("42501"));
		}
	}

	private long rows(String table, UUID instrument) {
		return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE instrument_id = ?", Long.class,
				instrument);
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
