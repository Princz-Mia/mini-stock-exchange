package com.mia_princz.mse.contracts.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.message.BinaryMessageDecoder;
import org.apache.avro.message.MissingSchemaException;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.junit.jupiter.api.Test;

class MarketSnapshotContractTest {

	private static final Instant AT = Instant.parse("2026-11-03T10:15:30.123456Z").truncatedTo(ChronoUnit.MICROS);

	@Test
	void fullSnapshotRoundTrips() throws Exception {
		MarketSnapshot snapshot = snapshot(20, 50);

		assertThat(WireEvents.decodeSnapshot(WireEvents.encode(snapshot))).isEqualTo(snapshot);
	}

	@Test
	void snapshotBeforeTheFirstTradeHasNoLastTrade() throws Exception {
		MarketSnapshot empty = snapshot(0, 0);

		MarketSnapshot decoded = WireEvents.decodeSnapshot(WireEvents.encode(empty));

		assertThat(decoded.getLastTrade()).isNull();
		assertThat(decoded.getLastTradeSequence()).isZero();
		assertThat(decoded.getBids()).isEmpty();
	}

	/** A later writer adds a venue state; a reader built today must not fail on it. */
	@Test
	void venueStateAddedLaterReadsAsUnknown() throws Exception {
		String current = MarketSnapshot.getClassSchema().toString();
		Schema future = new Schema.Parser().parse(current.replace("\"UNKNOWN\",\"CLOSED\"]",
				"\"UNKNOWN\",\"CLOSED\",\"SUSPENDED\"]"));
		assertThat(future.toString()).as("the future schema adds a state").isNotEqualTo(current);
		GenericData.Record written = new GenericDatumReader<GenericData.Record>(MarketSnapshot.getClassSchema(), future)
			.read(null, DecoderFactory.get()
				.binaryDecoder(binary(MarketSnapshot.getClassSchema(), snapshot(1, 1)), null));
		written.put("venueState", new GenericData.EnumSymbol(future.getField("venueState").schema(), "SUSPENDED"));
		byte[] bytes = generic(future, written);

		MarketSnapshot read = new SpecificDatumReader<MarketSnapshot>(future, MarketSnapshot.getClassSchema())
			.read(null, DecoderFactory.get().binaryDecoder(bytes, null));

		assertThat(read.getVenueState()).isEqualTo(VenueState.UNKNOWN);
		assertThat(read.getSymbol()).isEqualTo("NOVA");
	}

	/**
	 * A service not yet upgraded knows only the first writer. Payloads name their writer, so it stops on a version 2
	 * snapshot instead of reading it: it never takes a removed instrument for a live one. Readers are therefore deployed
	 * before writers, and once a version 2 snapshot is on the compacted topic, a reader cannot go back past it.
	 */
	@Test
	void aServiceThatKnowsOnlyTheFirstWriterStopsOnAVersionTwoSnapshot() throws Exception {
		Schema first = RetainedWriters.of("MarketSnapshot").get(0).schema();
		MarketSnapshot removed = snapshot(0, 0);
		removed.setRemoved(true);
		byte[] payload = WireEvents.encode(removed);

		BinaryMessageDecoder<GenericData.Record> old = new BinaryMessageDecoder<>(GenericData.get(), first);

		assertThatThrownBy(() -> old.decode(payload)).isInstanceOf(MissingSchemaException.class);
	}

	/**
	 * Plain Avro resolution with the first schema as the reader, which is what a reader handed both schemas does:
	 * CLOSED comes through as UNKNOWN and the added fields are left out.
	 */
	@Test
	void resolutionWithTheFirstSchemaReadsClosedAsUnknown() throws Exception {
		Schema first = RetainedWriters.of("MarketSnapshot").get(0).schema();
		MarketSnapshot closed = snapshot(1, 1);
		closed.setVenueState(VenueState.CLOSED);
		closed.setRemoved(true);
		closed.setDisplayName("Nova Dynamics");
		// Today's reader, through the wire format (which also trusts the generated classes), keeps CLOSED.
		assertThat(WireEvents.decodeSnapshot(WireEvents.encode(closed)).getVenueState()).isEqualTo(VenueState.CLOSED);

		GenericData.Record read = new GenericDatumReader<GenericData.Record>(MarketSnapshot.getClassSchema(), first)
			.read(null, DecoderFactory.get().binaryDecoder(binary(MarketSnapshot.getClassSchema(), closed), null));

		assertThat(read.get("venueState")).hasToString("UNKNOWN");
		assertThat(read.get("symbol")).hasToString("NOVA");
		assertThat(read.get("sourceCommandSequence")).isEqualTo(42L);
	}

	@Test
	void theFieldsAddedInVersionTwoRoundTrip() throws Exception {
		MarketSnapshot snapshot = snapshot(0, 0);
		snapshot.setVenueState(VenueState.CLOSED);
		snapshot.setRemoved(true);
		snapshot.setDisplayName("Nova Dynamics");
		snapshot.setOpensAt(AT);
		snapshot.setClosesAt(AT.plusSeconds(3_600));

		MarketSnapshot decoded = WireEvents.decodeSnapshot(WireEvents.encode(snapshot));

		assertThat(decoded).isEqualTo(snapshot);
		assertThat(decoded.getClosesAt()).isEqualTo(AT.plusSeconds(3_600));
	}

	@Test
	void aSnapshotBuiltWithoutTheAddedFieldsTakesTheirDefaults() {
		MarketSnapshot snapshot = snapshot(0, 0);

		assertThat(snapshot.getRemoved()).isFalse();
		assertThat(snapshot.getDisplayName()).isNull();
		assertThat(snapshot.getOpensAt()).isNull();
		assertThat(snapshot.getClosesAt()).isNull();
	}

	@Test
	void fullSnapshotStaysFarBelowItsPayloadBudget() throws Exception {
		assertThat(WireEvents.encode(snapshot(20, 50)).length).isLessThan(128 * 1024);
	}

	private static byte[] generic(Schema schema, GenericData.Record record) throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(bytes, null);
		new GenericDatumWriter<GenericData.Record>(schema).write(record, encoder);
		encoder.flush();
		return bytes.toByteArray();
	}

	private static byte[] binary(Schema schema, MarketSnapshot snapshot) throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(bytes, null);
		new SpecificDatumWriter<MarketSnapshot>(schema).write(snapshot, encoder);
		encoder.flush();
		return bytes.toByteArray();
	}

	static MarketSnapshot snapshot(int levels, int trades) {
		List<BookLevel> bids = new ArrayList<>();
		List<BookLevel> asks = new ArrayList<>();
		for (int i = 0; i < levels; i++) {
			bids.add(new BookLevel(10_000L - i, 100L + i, 1 + i));
			asks.add(new BookLevel(10_001L + i, 100L + i, 1 + i));
		}
		List<PublicTrade> recent = new ArrayList<>();
		for (int i = trades; i > 0; i--) {
			recent.add(new PublicTrade(UUID.randomUUID(), (long) i, 10_000L, 5L,
					i % 2 == 0 ? AggressorSide.BUY : AggressorSide.SELL, AT.plusSeconds(i)));
		}
		return MarketSnapshot.newBuilder()
			.setEventId(UUID.randomUUID())
			.setSimulationId(UUID.randomUUID())
			.setInstrumentId(UUID.randomUUID())
			.setSymbol("NOVA")
			.setVenueState(VenueState.OPEN)
			.setRules(new MarketRules(1, 10_000L, 1L, 1L, 1L, 10_000L, 100_000_000L, 20, 1_000))
			.setBids(bids)
			.setAsks(asks)
			.setRecentTrades(recent)
			.setLastTrade(recent.isEmpty() ? null : recent.get(0))
			.setLastTradeSequence(trades)
			.setSourceCommandSequence(42)
			.setGeneratedAt(AT)
			.build();
	}

}
