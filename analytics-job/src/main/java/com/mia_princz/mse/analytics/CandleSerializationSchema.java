package com.mia_princz.mse.analytics;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.kafka.clients.producer.ProducerRecord;

import com.mia_princz.mse.contracts.events.CandleUpdated;
import com.mia_princz.mse.contracts.events.WireEvents;

public class CandleSerializationSchema implements KafkaRecordSerializationSchema<CandleRecord> {

	private static final long serialVersionUID = 1L;

	private final String topic;
	private final long computationEpoch;

	public CandleSerializationSchema(String topic, long computationEpoch) {
		this.topic = topic;
		this.computationEpoch = computationEpoch;
	}

	@Override
	public ProducerRecord<byte[], byte[]> serialize(CandleRecord candle, KafkaSinkContext context, Long timestamp) {
		String key = candle.simulationId + '|' + candle.instrumentId + '|' + candle.bucketStartMillis;
		try {
			byte[] value = WireEvents.encode(toEvent(candle, Instant.now()));
			return new ProducerRecord<>(topic, key.getBytes(StandardCharsets.UTF_8), value);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	CandleUpdated toEvent(CandleRecord candle, Instant generatedAt) {
		String identity = computationEpoch + "|" + candle.simulationId + '|' + candle.instrumentId + '|'
				+ candle.bucketStartMillis + '|' + candle.sourceMaxTradeSequence;
		return CandleUpdated.newBuilder()
			.setEventId(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)))
			.setComputationEpoch(computationEpoch)
			.setSimulationId(UUID.fromString(candle.simulationId))
			.setInstrumentId(UUID.fromString(candle.instrumentId))
			.setSymbol(candle.symbol)
			.setBucketStart(Instant.ofEpochMilli(candle.bucketStartMillis))
			.setOpenCents(candle.openCents)
			.setHighCents(candle.highCents)
			.setLowCents(candle.lowCents)
			.setCloseCents(candle.closeCents)
			.setVolume(candle.volume)
			.setTradeCount(candle.tradeCount)
			.setNotionalCents(new BigDecimal(candle.notionalCents))
			.setSourceMaxTradeSequence(candle.sourceMaxTradeSequence)
			.setGeneratedAt(generatedAt)
			.build();
	}
}
