package com.mia_princz.mse.contracts.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.apache.avro.Schema;
import org.apache.avro.SchemaCompatibility;
import org.apache.avro.SchemaCompatibility.SchemaCompatibilityType;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.message.BinaryMessageEncoder;
import org.apache.avro.message.MissingSchemaException;
import org.junit.jupiter.api.Test;

/** The catalogue's rules. Each one fails the build before an incompatible or unrecorded schema can be published. */
class RetainedWritersTest {

	private static final Path DIRECTORY = Path
		.of("src/main/resources/com/mia_princz/mse/contracts/events/" + RetainedWriters.DIRECTORY);

	@Test
	void everyCurrentSchemaIsTheNewestRetainedWriterOfItsType() {
		RetainedWriters.CURRENT.forEach((type, current) -> {
			List<RetainedWriters.Writer> writers = RetainedWriters.of(type);
			assertThat(writers).as("%s has retained writers", type).isNotEmpty();
			assertThat(writers.get(writers.size() - 1).fingerprint())
				.as("%s changed: add it to the catalogue as version %d with fingerprint %016x", type, writers.size() + 1,
						RetainedWriters.fingerprint(current))
				.isEqualTo(RetainedWriters.fingerprint(current));
		});
		assertThat(RetainedWriters.all()).extracting(RetainedWriters.Writer::type)
			.allSatisfy((type) -> assertThat(RetainedWriters.CURRENT).containsKey(type));
	}

	@Test
	void versionsRunFromOneWithoutGapsAndEveryFileIsListed() throws IOException {
		List<String> listed = new ArrayList<>();
		RetainedWriters.CURRENT.keySet().forEach((type) -> {
			List<RetainedWriters.Writer> writers = RetainedWriters.of(type);
			for (int i = 0; i < writers.size(); i++) {
				assertThat(writers.get(i).version()).as("%s version order", type).isEqualTo(i + 1);
			}
			writers.forEach((writer) -> listed.add(writer.fileName()));
		});
		try (Stream<Path> files = Files.list(DIRECTORY)) {
			assertThat(files.map((file) -> file.getFileName().toString()).filter((name) -> name.endsWith(".avsc")))
				.as("every retained schema file is in the catalogue, and the other way round")
				.containsExactlyInAnyOrderElementsOf(listed);
		}
	}

	/** Backward-transitive: the current reader must read everything any retained writer ever wrote. */
	@Test
	void theCurrentReaderReadsEveryRetainedWriter() {
		for (RetainedWriters.Writer writer : RetainedWriters.all()) {
			assertThat(compatible(RetainedWriters.CURRENT.get(writer.type()), writer.schema()))
				.as("%s reads %s", writer.type(), writer.fileName())
				.isTrue();
		}
	}

	@Test
	void everyRetainedWriterDecodesIntoTheCurrentClass() {
		assertThat(RetainedWriters.verifyEveryWriterDecodes()).isEqualTo(RetainedWriters.all().size()).isEqualTo(3);
	}

	/**
	 * How an older writer is read: until a schema actually changes, the stand-in is today's trade schema with its fields
	 * in reverse order. Avro resolves fields by name, so it is compatible, but its fingerprint differs: a decoder that
	 * knew only the current schema would refuse it.
	 */
	@Test
	void aPayloadFromAnOlderWriterDecodesIntoTheCurrentClass() throws IOException {
		Schema current = TradeExecuted.getClassSchema();
		List<Schema.Field> reversed = new ArrayList<>();
		for (Schema.Field field : current.getFields()) {
			reversed.add(0, new Schema.Field(field, field.schema()));
		}
		Schema older = Schema.createRecord(current.getName(), current.getDoc(), current.getNamespace(), false, reversed);
		assertThat(RetainedWriters.fingerprint(older)).isNotEqualTo(RetainedWriters.fingerprint(current));
		assertThat(compatible(current, older)).isTrue();

		GenericRecord sample = (GenericRecord) WriterSamples.sample(older, new int[] { 1 });
		ByteBuffer encoded = new BinaryMessageEncoder<GenericRecord>(WriterSamples.model(), older).encode(sample);
		TradeExecuted decoded = TradeExecuted.createDecoder(WireEvents.store(List.of(older))).decode(encoded.duplicate());
		assertThat(decoded.getSymbol()).isEqualTo(sample.get("symbol"));
		assertThat(decoded.getTradeSequence()).isEqualTo(sample.get("tradeSequence"));
		assertThat(decoded.getExecutedAt()).isEqualTo(sample.get("executedAt"));
		assertThat(decoded.getInstrumentId()).isEqualTo(sample.get("instrumentId"));
		assertThatThrownBy(() -> TradeExecuted.getDecoder().decode(encoded.duplicate()))
			.as("without the older writer")
			.isInstanceOf(MissingSchemaException.class);
	}

	/** A retained file that no longer matches its published fingerprint is refused when the catalogue loads. */
	@Test
	void aChangedRetainedFileIsRefused() throws IOException {
		RetainedWriters.Writer published = RetainedWriters.of("TradeExecuted").get(0);
		String changed = published.schema().toString().replace("\"quantity\"", "\"shares\"");
		assertThatThrownBy(() -> RetainedWriters.parse("TradeExecuted", 1, published.fingerprint(),
				new ByteArrayInputStream(changed.getBytes(StandardCharsets.UTF_8))))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("must never change");
		assertThat(RetainedWriters.parse("TradeExecuted", 1, published.fingerprint(),
				new ByteArrayInputStream(published.schema().toString().getBytes(StandardCharsets.UTF_8))))
			.isEqualTo(published);
	}

	/** The decode check notices a field that did not come through, at any depth. */
	@Test
	void theDecodeCheckNoticesALostValue() {
		Schema schema = MarketSnapshot.getClassSchema();
		GenericRecord written = (GenericRecord) WriterSamples.sample(schema, new int[] { 1 });
		GenericRecord same = (GenericRecord) WriterSamples.sample(schema, new int[] { 1 });
		WriterSamples.compare("snapshot", same, written);
		GenericRecord rules = (GenericRecord) same.get("rules");
		rules.put("tickCents", (Long) rules.get("tickCents") + 1);
		assertThatThrownBy(() -> WriterSamples.compare("snapshot", same, written))
			.hasMessageContaining("snapshot.rules.tickCents");
	}

	/** The compatibility check itself: a type change without a default is caught, an added defaulted field is not. */
	@Test
	void theCompatibilityCheckTellsSafeChangesFromBreakingOnes() {
		Schema writer = parse("""
				{"type":"record","name":"Probe","fields":[{"name":"a","type":"long"}]}""");
		Schema addsDefaulted = parse("""
				{"type":"record","name":"Probe","fields":[{"name":"a","type":"long"},
				{"name":"b","type":"string","default":""}]}""");
		Schema addsRequired = parse("""
				{"type":"record","name":"Probe","fields":[{"name":"a","type":"long"},{"name":"b","type":"string"}]}""");
		Schema changesType = parse("""
				{"type":"record","name":"Probe","fields":[{"name":"a","type":"string"}]}""");
		assertThat(compatible(addsDefaulted, writer)).isTrue();
		assertThat(compatible(addsRequired, writer)).isFalse();
		assertThat(compatible(changesType, writer)).isFalse();
	}

	/** A writer that is not in the catalogue still stops the consumer instead of being skipped. */
	@Test
	void aWriterOutsideTheCatalogueIsRefused() throws IOException {
		Schema unknown = parse("""
				{"type":"record","name":"TradeExecuted","namespace":"com.mia_princz.mse.contracts.events",
				"fields":[{"name":"symbol","type":"string"}]}""");
		GenericRecord record = new GenericData.Record(unknown);
		record.put("symbol", "NOVA");
		ByteBuffer encoded = new BinaryMessageEncoder<GenericRecord>(GenericData.get(), unknown).encode(record);
		byte[] payload = new byte[encoded.remaining()];
		encoded.get(payload);
		assertThatThrownBy(() -> WireEvents.decodeTrade(payload)).isInstanceOf(MissingSchemaException.class);
	}

	private static boolean compatible(Schema reader, Schema writer) {
		return SchemaCompatibility.checkReaderWriterCompatibility(reader, writer)
			.getType() == SchemaCompatibilityType.COMPATIBLE;
	}

	private static Schema parse(String json) {
		return new Schema.Parser().parse(json);
	}

}
