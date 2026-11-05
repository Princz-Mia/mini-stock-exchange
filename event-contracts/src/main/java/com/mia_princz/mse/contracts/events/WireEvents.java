package com.mia_princz.mse.contracts.events;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

import org.apache.avro.Schema;
import org.apache.avro.message.BinaryMessageDecoder;
import org.apache.avro.message.SchemaStore;
import org.apache.avro.util.ClassSecurityValidator;

/**
 * Single-object encoding entry point for every JVM that handles wire events. Avro 1.12.2 refuses to instantiate
 * generated classes it does not trust, so this class extends the global predicate with exactly these contracts.
 * New event types, and the records and enums nested in them, must be added here; packages are deliberately not trusted wholesale.
 * <p>
 * Each decoder knows every writer schema in {@link RetainedWriters}, so a payload written by any published version
 * decodes into the current class. A fingerprint outside the catalogue still fails with {@code MissingSchemaException}:
 * the consumer stops on it rather than dropping a fact it cannot read.
 */
public final class WireEvents {

	static {
		ClassSecurityValidator.setGlobal(ClassSecurityValidator.composite(ClassSecurityValidator.getGlobal(),
				ClassSecurityValidator.builder()
					.add(TradeExecuted.class.getName())
					.add(CandleUpdated.class.getName())
					.add(MarketSnapshot.class.getName())
					.add(MarketRules.class.getName())
					.add(BookLevel.class.getName())
					.add(PublicTrade.class.getName())
					.add(VenueState.class.getName())
					.add(AggressorSide.class.getName())
					.build()));
	}

	private static final BinaryMessageDecoder<TradeExecuted> TRADES = TradeExecuted
		.createDecoder(retained("TradeExecuted"));

	private static final BinaryMessageDecoder<CandleUpdated> CANDLES = CandleUpdated
		.createDecoder(retained("CandleUpdated"));

	private static final BinaryMessageDecoder<MarketSnapshot> SNAPSHOTS = MarketSnapshot
		.createDecoder(retained("MarketSnapshot"));

	private WireEvents() {
	}

	public static TradeExecuted decodeTrade(byte[] payload) throws IOException {
		return TRADES.decode(ByteBuffer.wrap(payload));
	}

	public static CandleUpdated decodeCandle(byte[] payload) throws IOException {
		return CANDLES.decode(ByteBuffer.wrap(payload));
	}

	public static MarketSnapshot decodeSnapshot(byte[] payload) throws IOException {
		return SNAPSHOTS.decode(ByteBuffer.wrap(payload));
	}

	private static SchemaStore retained(String type) {
		return store(RetainedWriters.of(type).stream().map(RetainedWriters.Writer::schema).toList());
	}

	/** The writers a decoder can resolve, besides its own schema. */
	static SchemaStore store(List<Schema> writers) {
		SchemaStore.Cache store = new SchemaStore.Cache();
		writers.forEach(store::addSchema);
		return store;
	}

	public static byte[] encode(TradeExecuted event) throws IOException {
		return toArray(event.toByteBuffer());
	}

	public static byte[] encode(CandleUpdated event) throws IOException {
		return toArray(event.toByteBuffer());
	}

	public static byte[] encode(MarketSnapshot event) throws IOException {
		return toArray(event.toByteBuffer());
	}

	private static byte[] toArray(ByteBuffer buffer) {
		byte[] bytes = new byte[buffer.remaining()];
		buffer.get(bytes);
		return bytes;
	}
}
