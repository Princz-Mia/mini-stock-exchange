package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import com.mia_princz.mse.contracts.events.TradeExecuted;
import com.mia_princz.mse.contracts.events.WireEvents;

/**
 * Compatibility gate, first half: publishes real Avro trades with Boot's Kafka client on JDK 25. The name is outside
 * the Failsafe includes, so it runs only when the gate selects it with {@code -Dit.test=TradePublishSmoke} and a
 * running stack; a missing property fails rather than skips.
 */
class TradePublishSmoke {

	@Test
	void publishesGoldenFillsAndOneRedelivery() throws Exception {
		assertThat(Runtime.version().feature()).isEqualTo(25);
		String bootstrapServers = System.getProperty("smoke.kafka");
		UUID simulationId = UUID.fromString(System.getProperty("smoke.simulationId"));
		UUID instrumentId = UUID.randomUUID();
		Instant minute = Instant.now().truncatedTo(ChronoUnit.MINUTES);

		Map<String, Object> config = Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
				ProducerConfig.ACKS_CONFIG, "all", ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
		var factory = new DefaultKafkaProducerFactory<>(config, new StringSerializer(), new ByteArraySerializer());
		try {
			KafkaTemplate<String, byte[]> template = new KafkaTemplate<>(factory);
			String key = simulationId + "|" + instrumentId;
			long[][] fills = { { 1, 10_000, 3 }, { 2, 10_000, 4 }, { 2, 10_000, 4 }, { 3, 10_100, 3 } };
			byte[] previous = null;
			long previousSequence = 0;
			for (long[] fill : fills) {
				// A redelivery resends the same bytes, as the outbox relay does: same event id, same trade id.
				byte[] payload = fill[0] == previousSequence ? previous
						: WireEvents.encode(TradeExecuted.newBuilder()
							.setEventId(UUID.randomUUID())
							.setSimulationId(simulationId)
							.setInstrumentId(instrumentId)
							.setSymbol("NOVA")
							.setTradeId(UUID.randomUUID())
							.setTradeSequence(fill[0])
							.setCommandSequence(fill[0])
							.setExecutedAt(minute.plusSeconds(fill[0]))
							.setPriceCents(fill[1])
							.setQuantity(fill[2])
							.build());
				template.send("mse.trades.v1", key, payload).get();
				previous = payload;
				previousSequence = fill[0];
			}
		}
		finally {
			factory.destroy();
		}
	}

}
