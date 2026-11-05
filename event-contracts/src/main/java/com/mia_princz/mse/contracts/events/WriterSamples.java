package com.mia_princz.mse.contracts.events;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.avro.Conversions;
import org.apache.avro.LogicalType;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.data.TimeConversions;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericEnumSymbol;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.IndexedRecord;
import org.apache.avro.message.BinaryMessageEncoder;

/**
 * A sample record for any writer schema, built from the schema alone: every field gets a distinct value of its type,
 * nested records, arrays and enums included. It is encoded exactly as a producer using that writer would encode it.
 */
final class WriterSamples {

	private static final GenericData MODEL = new GenericData();

	static {
		MODEL.addLogicalTypeConversion(new Conversions.UUIDConversion());
		MODEL.addLogicalTypeConversion(new Conversions.DecimalConversion());
		MODEL.addLogicalTypeConversion(new TimeConversions.TimestampMicrosConversion());
		MODEL.addLogicalTypeConversion(new TimeConversions.TimestampMillisConversion());
	}

	private static final Instant AT = Instant.parse("2026-11-05T10:15:30.123456Z");

	private WriterSamples() {
	}

	/** Generic data with the same logical-type conversions the generated classes use. */
	static GenericData model() {
		return MODEL;
	}

	/** Encodes a sample with the writer, decodes it as the current type, and compares every shared field. */
	static void check(RetainedWriters.Writer writer) throws IOException {
		GenericRecord sample = (GenericRecord) sample(writer.schema(), new int[] { 1 });
		ByteBuffer encoded = new BinaryMessageEncoder<GenericRecord>(MODEL, writer.schema()).encode(sample);
		byte[] payload = new byte[encoded.remaining()];
		encoded.get(payload);
		IndexedRecord decoded = switch (writer.type()) {
			case "TradeExecuted" -> WireEvents.decodeTrade(payload);
			case "CandleUpdated" -> WireEvents.decodeCandle(payload);
			case "MarketSnapshot" -> WireEvents.decodeSnapshot(payload);
			default -> throw new IllegalStateException("No decoder for " + writer.type());
		};
		compare(writer.type(), decoded, sample);
	}

	static Object sample(Schema schema, int[] counter) {
		int n = counter[0]++;
		LogicalType logical = schema.getLogicalType();
		return switch (schema.getType()) {
			case RECORD -> {
				GenericData.Record record = new GenericData.Record(schema);
				for (Schema.Field field : schema.getFields()) {
					record.put(field.pos(), sample(field.schema(), counter));
				}
				yield record;
			}
			case UNION -> sample(schema.getTypes()
				.stream()
				.filter((branch) -> branch.getType() != Schema.Type.NULL)
				.findFirst()
				.orElse(schema.getTypes().get(0)), counter);
			case ARRAY -> {
				List<Object> items = new ArrayList<>();
				items.add(sample(schema.getElementType(), counter));
				items.add(sample(schema.getElementType(), counter));
				yield items;
			}
			case MAP -> {
				Map<String, Object> map = new HashMap<>();
				map.put("k" + n, sample(schema.getValueType(), counter));
				yield map;
			}
			case ENUM -> new GenericData.EnumSymbol(schema, schema.getEnumSymbols().get(n % schema.getEnumSymbols().size()));
			case STRING -> logical instanceof LogicalTypes.Uuid ? new UUID(0x5EED, n) : "s" + n;
			case LONG -> {
				if (logical instanceof LogicalTypes.TimestampMicros) {
					yield AT.plus(n, ChronoUnit.SECONDS);
				}
				if (logical instanceof LogicalTypes.TimestampMillis) {
					yield AT.truncatedTo(ChronoUnit.MILLIS).plus(n, ChronoUnit.SECONDS);
				}
				yield 1_000L * n + 7;
			}
			case INT -> 10 * n + 3;
			case BYTES -> logical instanceof LogicalTypes.Decimal decimal
					? BigDecimal.valueOf(123_456_789L * n, decimal.getScale())
					: ByteBuffer.wrap(new byte[] { (byte) n });
			case BOOLEAN -> n % 2 == 0;
			case DOUBLE -> n + 0.5;
			case FLOAT -> n + 0.25f;
			case FIXED -> new GenericData.Fixed(schema, new byte[schema.getFixedSize()]);
			case NULL -> null;
		};
	}

	/** Every field the writer had and the reader still has must carry the writer's value. */
	static void compare(String path, Object decoded, Object written) {
		if (written instanceof IndexedRecord writtenRecord) {
			if (!(decoded instanceof IndexedRecord decodedRecord)) {
				throw new IllegalStateException(path + ": expected a record, got " + decoded);
			}
			for (Schema.Field field : writtenRecord.getSchema().getFields()) {
				Schema.Field readerField = decodedRecord.getSchema().getField(field.name());
				if (readerField != null) {
					compare(path + "." + field.name(), decodedRecord.get(readerField.pos()),
							writtenRecord.get(field.pos()));
				}
			}
			return;
		}
		if (written instanceof List<?> writtenList) {
			if (!(decoded instanceof List<?> decodedList) || decodedList.size() != writtenList.size()) {
				throw new IllegalStateException(path + ": expected " + writtenList.size() + " items, got " + decoded);
			}
			for (int i = 0; i < writtenList.size(); i++) {
				compare(path + "[" + i + "]", decodedList.get(i), writtenList.get(i));
			}
			return;
		}
		if (written instanceof GenericEnumSymbol<?> || written instanceof CharSequence) {
			if (decoded == null || !decoded.toString().equals(written.toString())) {
				throw new IllegalStateException(path + ": expected " + written + ", got " + decoded);
			}
			return;
		}
		if (written instanceof BigDecimal writtenDecimal) {
			if (!(decoded instanceof BigDecimal decodedDecimal) || writtenDecimal.compareTo(decodedDecimal) != 0) {
				throw new IllegalStateException(path + ": expected " + written + ", got " + decoded);
			}
			return;
		}
		if (written == null ? decoded != null : !written.equals(decoded)) {
			throw new IllegalStateException(path + ": expected " + written + ", got " + decoded);
		}
	}

}
