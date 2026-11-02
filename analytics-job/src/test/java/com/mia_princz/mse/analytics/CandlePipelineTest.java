package com.mia_princz.mse.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.PipelineOptions;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.api.Test;

import com.mia_princz.mse.contracts.events.CandleUpdated;
import com.mia_princz.mse.contracts.events.TradeExecuted;
import com.mia_princz.mse.contracts.events.WireEvents;

class CandlePipelineTest {

	private static final Instant MINUTE = Instant.parse("2026-11-02T10:15:00Z");

	@Test
	void runsOnJdk17() {
		assertThat(Runtime.version().feature()).isEqualTo(17);
	}

	/** The golden fills, plus a redelivered trade that must not count twice. */
	@Test
	void aggregatesGoldenFillsAndIgnoresRedelivery() throws Exception {
		UUID simulationId = UUID.randomUUID();
		UUID instrumentId = UUID.randomUUID();
		List<byte[]> payloads = List.of(trade(simulationId, instrumentId, 1, 10_000, 3), trade(simulationId, instrumentId, 2, 10_000, 4),
				trade(simulationId, instrumentId, 2, 10_000, 4), trade(simulationId, instrumentId, 3, 10_100, 3));

		Configuration configuration = new Configuration();
		configuration.set(PipelineOptions.GENERIC_TYPES, false);
		StreamExecutionEnvironment env = StreamExecutionEnvironment.createLocalEnvironment(1, configuration);

		TradeDeserializationSchema schema = new TradeDeserializationSchema();
		List<TradeRecord> trades = new ArrayList<>();
		for (byte[] payload : payloads) {
			trades.add(schema.deserialize(payload));
		}
		DataStream<CandleRecord> candles = env.fromData(trades)
			.assignTimestampsAndWatermarks(WatermarkStrategy.noWatermarks())
			.keyBy(TradeRecord::key)
			.process(new CandleAggregator());

		List<CandleRecord> emitted = new ArrayList<>();
		try (CloseableIterator<CandleRecord> results = candles.executeAndCollect()) {
			results.forEachRemaining(emitted::add);
		}

		assertThat(emitted).as("one complete aggregate per unique trade").hasSize(3);
		CandleRecord last = emitted.get(2);
		assertThat(last.bucketStartMillis).isEqualTo(MINUTE.toEpochMilli());
		assertThat(last.openCents).isEqualTo(10_000);
		assertThat(last.highCents).isEqualTo(10_100);
		assertThat(last.lowCents).isEqualTo(10_000);
		assertThat(last.closeCents).isEqualTo(10_100);
		assertThat(last.volume).isEqualTo(10);
		assertThat(last.tradeCount).isEqualTo(3);
		assertThat(last.notionalCents).isEqualTo(BigInteger.valueOf(100_300));
		assertThat(last.sourceMaxTradeSequence).isEqualTo(3);
	}

	@Test
	void candleEventIdIsStableAcrossReplays() {
		CandleSerializationSchema schema = new CandleSerializationSchema("mse.candles.v1", 7);
		TradeRecord trade = new TradeRecord();
		trade.simulationId = UUID.randomUUID().toString();
		trade.instrumentId = UUID.randomUUID().toString();
		trade.symbol = "NOVA";
		trade.tradeSequence = 1;
		trade.executedAtMicros = MINUTE.getEpochSecond() * 1_000_000L;
		trade.priceCents = 10_000;
		trade.quantity = 3;
		CandleRecord candle = CandleRecord.start(trade, MINUTE.toEpochMilli());
		candle.add(trade);

		CandleUpdated first = schema.toEvent(candle, Instant.parse("2026-11-02T10:16:00Z"));
		CandleUpdated replay = schema.toEvent(candle, Instant.parse("2026-11-02T11:00:00Z"));

		assertThat(replay.getEventId()).isEqualTo(first.getEventId());
		assertThat(first.getComputationEpoch()).isEqualTo(7);
	}

	private static byte[] trade(UUID simulationId, UUID instrumentId, long sequence, long priceCents, long quantity)
			throws Exception {
		return WireEvents.encode(TradeExecuted.newBuilder()
			.setEventId(UUID.randomUUID())
			.setSimulationId(simulationId)
			.setInstrumentId(instrumentId)
			.setSymbol("NOVA")
			.setTradeId(UUID.randomUUID())
			.setTradeSequence(sequence)
			.setCommandSequence(sequence)
			.setExecutedAt(MINUTE.plusSeconds(sequence))
			.setPriceCents(priceCents)
			.setQuantity(quantity)
			.build());
	}
}
