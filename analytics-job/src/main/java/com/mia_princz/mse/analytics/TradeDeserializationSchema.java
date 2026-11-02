package com.mia_princz.mse.analytics;

import java.io.IOException;

import org.apache.flink.api.common.serialization.AbstractDeserializationSchema;

import com.mia_princz.mse.contracts.events.WireEvents;

/** Unknown fingerprints and invalid records throw, which pauses the pipeline instead of dropping facts. */
public class TradeDeserializationSchema extends AbstractDeserializationSchema<TradeRecord> {

	private static final long serialVersionUID = 1L;

	@Override
	public TradeRecord deserialize(byte[] message) throws IOException {
		return TradeRecord.from(WireEvents.decodeTrade(message));
	}
}
