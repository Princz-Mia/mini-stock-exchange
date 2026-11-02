package com.mia_princz.mse.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

import com.mia_princz.mse.contracts.events.CandleUpdated;
import com.mia_princz.mse.contracts.events.WireEvents;

/**
 * Compatibility gate, second half: reads the Avro candles Flink produced from the trades of {@code TradePublishSmoke},
 * with Boot's Kafka client on JDK 25. Selected explicitly by the gate; a missing property fails rather than skips.
 */
class CandleConsumeSmoke {

	private static final Duration TIMEOUT = Duration.ofSeconds(120);

	@Test
	void readsTheCandleFlinkComputed() throws Exception {
		assertThat(Runtime.version().feature()).isEqualTo(25);
		String bootstrapServers = System.getProperty("smoke.kafka");
		UUID simulationId = UUID.fromString(System.getProperty("smoke.simulationId"));

		Map<String, Object> config = Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
				ConsumerConfig.GROUP_ID_CONFIG, "compat-gate-" + simulationId, ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
				"earliest", ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
		var factory = new DefaultKafkaConsumerFactory<>(config, new StringDeserializer(), new ByteArrayDeserializer());

		// Delivery is at-least-once, so distinct stable event IDs are what must equal one aggregate per unique trade.
		Map<UUID, CandleUpdated> candles = new HashMap<>();
		CandleUpdated last = null;
		Instant deadline = Instant.now().plus(TIMEOUT);
		try (Consumer<String, byte[]> consumer = factory.createConsumer()) {
			consumer.subscribe(List.of("mse.candles.v1"));
			while (last == null && Instant.now().isBefore(deadline)) {
				for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofSeconds(1))) {
					assertThat(record.value()[0]).as("single-object marker").isEqualTo((byte) 0xC3);
					CandleUpdated candle = WireEvents.decodeCandle(record.value());
					if (!candle.getSimulationId().equals(simulationId)) {
						continue;
					}
					assertThat(record.key()).startsWith(simulationId + "|");
					candles.put(candle.getEventId(), candle);
					if (candle.getSourceMaxTradeSequence() == 3) {
						last = candle;
					}
				}
			}
		}

		assertThat(last).as("candle for simulation %s within %s", simulationId, TIMEOUT).isNotNull();
		assertThat(candles).as("the redelivered trade produced no extra aggregate").hasSize(3);
		assertThat(last.getSymbol()).isEqualTo("NOVA");
		assertThat(last.getOpenCents()).isEqualTo(10_000);
		assertThat(last.getHighCents()).isEqualTo(10_100);
		assertThat(last.getLowCents()).isEqualTo(10_000);
		assertThat(last.getCloseCents()).isEqualTo(10_100);
		assertThat(last.getVolume()).isEqualTo(10);
		assertThat(last.getTradeCount()).isEqualTo(3);
		assertThat(last.getNotionalCents()).isEqualByComparingTo(new BigDecimal(100_300));
	}

}
