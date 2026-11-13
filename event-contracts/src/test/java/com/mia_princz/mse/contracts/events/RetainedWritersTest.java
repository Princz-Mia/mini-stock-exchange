package com.mia_princz.mse.contracts.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.avro.Schema;
import org.apache.avro.SchemaCompatibility;
import org.apache.avro.SchemaCompatibility.SchemaCompatibilityType;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.message.BinaryMessageEncoder;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
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
		assertThat(RetainedWriters.verifyEveryWriterDecodes()).isEqualTo(RetainedWriters.all().size()).isEqualTo(4);
	}

	/**
	 * A snapshot written by version 1, still on the compacted topic, decodes into today's class: what it had comes
	 * through, and what version 2 added takes its default, so it is not removed and has no display name or times. A
	 * decoder that knew only the current schema would refuse it.
	 */
	@Test
	void aSnapshotFromTheFirstWriterDecodesIntoTheCurrentClass() throws IOException {
		Schema older = RetainedWriters.of("MarketSnapshot").get(0).schema();
		assertThat(RetainedWriters.fingerprint(older)).isNotEqualTo(RetainedWriters.fingerprint(MarketSnapshot.getClassSchema()));

		GenericRecord sample = (GenericRecord) WriterSamples.sample(older, new int[] { 1 });
		ByteBuffer encoded = new BinaryMessageEncoder<GenericRecord>(WriterSamples.model(), older).encode(sample);
		MarketSnapshot decoded = MarketSnapshot.createDecoder(WireEvents.store(List.of(older))).decode(encoded.duplicate());
		assertThat(decoded.getSymbol()).isEqualTo(sample.get("symbol").toString());
		assertThat(decoded.getInstrumentId()).isEqualTo(sample.get("instrumentId"));
		assertThat(decoded.getSourceCommandSequence()).isEqualTo(sample.get("sourceCommandSequence"));
		assertThat(decoded.getRemoved()).isFalse();
		assertThat(decoded.getDisplayName()).isNull();
		assertThat(decoded.getOpensAt()).isNull();
		assertThat(decoded.getClosesAt()).isNull();
		assertThatThrownBy(() -> MarketSnapshot.getDecoder().decode(encoded.duplicate()))
			.as("without the first writer")
			.isInstanceOf(MissingSchemaException.class);
	}

	/**
	 * The decode check notices a field added later whose default says something: with removed defaulting to true, a
	 * version 1 snapshot really decodes as removed, and the check refuses it.
	 */
	@Test
	void theDecodeCheckNoticesADefaultThatSaysSomething() throws IOException {
		Schema older = RetainedWriters.of("MarketSnapshot").get(0).schema();
		Schema reader = withDefault(MarketSnapshot.getClassSchema(), "removed", true);
		GenericRecord written = (GenericRecord) WriterSamples.sample(older, new int[] { 1 });
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(bytes, null);
		new GenericDatumWriter<GenericRecord>(older, WriterSamples.model()).write(written, encoder);
		encoder.flush();
		GenericRecord decoded = new GenericDatumReader<GenericRecord>(older, reader, WriterSamples.model())
			.read(null, DecoderFactory.get().binaryDecoder(bytes.toByteArray(), null));

		assertThat(decoded.get("removed")).isEqualTo(true);
		assertThatThrownBy(() -> WriterSamples.compare("snapshot", decoded, written))
			.hasMessageContaining("snapshot.removed (added later)");
	}

	/**
	 * Every field a later writer added defaults to nothing, so a payload from before it never says more than its writer
	 * did: null, false, zero or empty. Enums and records are judged on their own.
	 */
	@Test
	void everyFieldAddedLaterDefaultsToNothing() {
		int checked = 0;
		for (String type : RetainedWriters.CURRENT.keySet()) {
			List<RetainedWriters.Writer> writers = RetainedWriters.of(type);
			for (int i = 1; i < writers.size(); i++) {
				Schema before = writers.get(i - 1).schema();
				for (Schema.Field field : writers.get(i).schema().getFields()) {
					Object nothing = WriterSamples.nothing(field.schema());
					if (before.getField(field.name()) == null && nothing != WriterSamples.NO_NEUTRAL) {
						WriterSamples.compare(writers.get(i).fileName() + " " + field.name(),
								WriterSamples.model().getDefaultValue(field), nothing);
						checked++;
					}
				}
			}
		}
		assertThat(checked).as("fields added since the first writers").isEqualTo(4);
		assertThatThrownBy(() -> WriterSamples.compare("removed", true, WriterSamples.nothing(Schema.create(Schema.Type.BOOLEAN))))
			.hasMessageContaining("removed");
	}

	/** An enum keeps its symbols in order and only gains them at the end, so a symbol never changes its position. */
	@Test
	void enumSymbolsAreOnlyEverAppended() {
		for (String type : RetainedWriters.CURRENT.keySet()) {
			List<RetainedWriters.Writer> writers = RetainedWriters.of(type);
			for (int i = 1; i < writers.size(); i++) {
				Map<String, List<String>> before = enums(writers.get(i - 1).schema(), new HashMap<>());
				Map<String, List<String>> after = enums(writers.get(i).schema(), new HashMap<>());
				String file = writers.get(i).fileName();
				before.forEach((name, symbols) -> assertThat(after.get(name))
					.as("%s in %s", name, file)
					.startsWith(symbols.toArray(String[]::new)));
			}
		}
		assertThat(enums(MarketSnapshot.getClassSchema(), new HashMap<>()).get("com.mia_princz.mse.contracts.events.VenueState"))
			.containsExactly("SETUP", "OPEN", "HALTED", "UNKNOWN", "CLOSED");
	}

	private static Map<String, List<String>> enums(Schema schema, Map<String, List<String>> found) {
		switch (schema.getType()) {
			case ENUM -> found.put(schema.getFullName(), schema.getEnumSymbols());
			case RECORD -> {
				if (found.putIfAbsent(schema.getFullName(), List.of()) == null) {
					schema.getFields().forEach((field) -> enums(field.schema(), found));
				}
			}
			case UNION -> schema.getTypes().forEach((branch) -> enums(branch, found));
			case ARRAY -> enums(schema.getElementType(), found);
			case MAP -> enums(schema.getValueType(), found);
			default -> {
			}
		}
		found.values().removeIf(List::isEmpty);
		return found;
	}

	private static Schema withDefault(Schema schema, String name, Object value) {
		List<Schema.Field> fields = new ArrayList<>();
		for (Schema.Field field : schema.getFields()) {
			fields.add(field.name().equals(name) ? new Schema.Field(field.name(), field.schema(), field.doc(), value)
					: new Schema.Field(field, field.schema()));
		}
		return Schema.createRecord(schema.getName(), schema.getDoc(), schema.getNamespace(), false, fields);
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
