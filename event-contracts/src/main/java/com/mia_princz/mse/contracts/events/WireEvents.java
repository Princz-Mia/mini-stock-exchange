package com.mia_princz.mse.contracts.events;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.apache.avro.util.ClassSecurityValidator;

/**
 * Single-object encoding entry point for every JVM that handles wire events. Avro 1.12.2 refuses to instantiate
 * generated classes it does not trust, so this class extends the global predicate with exactly these contracts.
 * New event types must be added here; packages are deliberately not trusted wholesale.
 */
public final class WireEvents {

	static {
		ClassSecurityValidator.setGlobal(ClassSecurityValidator.composite(ClassSecurityValidator.getGlobal(),
				ClassSecurityValidator.builder()
					.add(TradeExecuted.class.getName())
					.add(CandleUpdated.class.getName())
					.build()));
	}

	private WireEvents() {
	}

	public static TradeExecuted decodeTrade(byte[] payload) throws IOException {
		return TradeExecuted.fromByteBuffer(ByteBuffer.wrap(payload));
	}

	public static CandleUpdated decodeCandle(byte[] payload) throws IOException {
		return CandleUpdated.fromByteBuffer(ByteBuffer.wrap(payload));
	}

	public static byte[] encode(TradeExecuted event) throws IOException {
		return toArray(event.toByteBuffer());
	}

	public static byte[] encode(CandleUpdated event) throws IOException {
		return toArray(event.toByteBuffer());
	}

	private static byte[] toArray(ByteBuffer buffer) {
		byte[] bytes = new byte[buffer.remaining()];
		buffer.get(bytes);
		return bytes;
	}
}
