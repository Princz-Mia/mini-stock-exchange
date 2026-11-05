package com.mia_princz.mse.contracts.events;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.avro.Schema;
import org.apache.avro.SchemaNormalization;

/**
 * The catalogue of every writer schema ever published, by event type: {@code writers/catalogue.txt} beside this class,
 * with one {@code <type>-v<version>.avsc} file per entry. Payloads carry their writer's fingerprint, so a reader that
 * knows every retained writer can decode anything still on a topic, however old.
 */
public final class RetainedWriters {

	static final String DIRECTORY = "writers/";

	static final String CATALOGUE = DIRECTORY + "catalogue.txt";

	/** The current schema of each event type, keyed by the type's simple name as the catalogue uses it. */
	static final Map<String, Schema> CURRENT = Map.of("TradeExecuted", TradeExecuted.getClassSchema(),
			"CandleUpdated", CandleUpdated.getClassSchema(), "MarketSnapshot", MarketSnapshot.getClassSchema());

	private static final List<Writer> ALL = load();

	private RetainedWriters() {
	}

	/** One published writer schema. */
	public record Writer(String type, int version, long fingerprint, Schema schema) {

		public Writer {
			Objects.requireNonNull(type, "type");
			Objects.requireNonNull(schema, "schema");
		}

		String fileName() {
			return type + "-v" + version + ".avsc";
		}

	}

	public static List<Writer> all() {
		return ALL;
	}

	public static List<Writer> of(String type) {
		return ALL.stream().filter((writer) -> writer.type().equals(type)).toList();
	}

	public static long fingerprint(Schema schema) {
		return SchemaNormalization.parsingFingerprint64(schema);
	}

	/**
	 * Encodes a generated sample with every retained writer and decodes it with {@link WireEvents}, then checks that
	 * each field both schemas share came through. Run by every module that decodes events, so each checks its own
	 * runtime and Avro version. Returns how many writers were checked.
	 */
	public static int verifyEveryWriterDecodes() {
		int checked = 0;
		for (Writer writer : ALL) {
			try {
				WriterSamples.check(writer);
			}
			catch (IOException | RuntimeException ex) {
				throw new IllegalStateException(writer.fileName() + " no longer decodes: " + ex.getMessage(), ex);
			}
			checked++;
		}
		return checked;
	}

	private static List<Writer> load() {
		List<Writer> writers = new ArrayList<>();
		try (BufferedReader catalogue = reader(CATALOGUE)) {
			String line;
			while ((line = catalogue.readLine()) != null) {
				line = line.strip();
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}
				String[] parts = line.split("\\s+");
				if (parts.length != 3) {
					throw new IllegalStateException("Malformed catalogue line: " + line);
				}
				String type = parts[0];
				int version = Integer.parseInt(parts[1]);
				try (InputStream file = stream(DIRECTORY + type + "-v" + version + ".avsc")) {
					writers.add(parse(type, version, Long.parseUnsignedLong(parts[2], 16), file));
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return List.copyOf(writers);
	}

	/** A retained writer, refused if its file no longer matches the fingerprint it was published with. */
	static Writer parse(String type, int version, long expected, InputStream file) throws IOException {
		Schema schema = new Schema.Parser().parse(file);
		long actual = fingerprint(schema);
		if (actual != expected) {
			throw new IllegalStateException(String.format("%s-v%d.avsc has fingerprint %016x, but the catalogue says %016x;"
					+ " a published writer must never change", type, version, actual, expected));
		}
		return new Writer(type, version, expected, schema);
	}

	static BufferedReader reader(String resource) {
		return new BufferedReader(new InputStreamReader(stream(resource), StandardCharsets.UTF_8));
	}

	private static InputStream stream(String resource) {
		InputStream stream = RetainedWriters.class.getResourceAsStream(resource);
		if (stream == null) {
			throw new IllegalStateException("Missing " + resource + " beside " + RetainedWriters.class.getName());
		}
		return stream;
	}

}
