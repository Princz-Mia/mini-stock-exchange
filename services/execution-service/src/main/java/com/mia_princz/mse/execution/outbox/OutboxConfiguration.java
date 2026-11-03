package com.mia_princz.mse.execution.outbox;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.mia_princz.mse.execution.trading.adapter.WriterOwnership;

@Configuration(proxyBeanMethods = false)
class OutboxConfiguration {

	/** Independent of the relay, so admission holds even while the relay is down or switched off. */
	@Bean
	OutboxAdmission outboxAdmission(JdbcClient jdbcClient, Clock clock, MeterRegistry meters,
			@Value("${mse.outbox.admission.max-events:50000}") long maxEvents,
			@Value("${mse.outbox.admission.max-bytes:268435456}") long maxBytes,
			@Value("${mse.outbox.admission.refresh:1s}") Duration refresh) {
		OutboxAdmission admission = new OutboxAdmission(new JdbcOutbox(jdbcClient),
				new OutboxAdmission.Limits(maxEvents, maxBytes, refresh), clock);
		Gauge.builder("mse.outbox.admission.open", admission, (a) -> a.isOpen() ? 1 : 0)
			.description("1 while new orders are admitted, 0 while the publication backlog stops them")
			.register(meters);
		return admission;
	}

	@Bean(initMethod = "start", destroyMethod = "close")
	@ConditionalOnProperty(name = "mse.outbox.relay.enabled", matchIfMissing = true)
	OutboxRelay outbox(JdbcClient jdbcClient, KafkaProperties kafka, KafkaConnectionDetails connection,
			WriterOwnership ownership, Clock clock, MeterRegistry meters,
			@Value("${mse.outbox.batch-size:200}") int batchSize,
			@Value("${mse.outbox.poll-interval:50ms}") Duration pollInterval,
			@Value("${mse.outbox.ack-timeout:35s}") Duration ackTimeout,
			@Value("${mse.outbox.max-backoff:5s}") Duration maxBackoff) {
		Map<String, Object> producerConfig = new HashMap<>(kafka.buildProducerProperties());
		// The brokers come from the connection details, as for Boot's own producers, not from the properties alone.
		producerConfig.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, connection.getProducer().getBootstrapServers());
		// Delivery safety is not left to configuration: every event is acknowledged by all replicas, once.
		producerConfig.put(ProducerConfig.ACKS_CONFIG, "all");
		producerConfig.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
		OutboxRelay relay = new OutboxRelay(new JdbcOutbox(jdbcClient),
				() -> new KafkaProducer<>(producerConfig, new StringSerializer(), new ByteArraySerializer()),
				ownership::isHeld, new OutboxRelay.Settings(batchSize, pollInterval, ackTimeout, maxBackoff), clock);
		Gauge.builder("mse.outbox.pending", relay, OutboxRelay::pendingEvents)
			.description("Committed events not yet acknowledged by Kafka")
			.register(meters);
		Gauge.builder("mse.outbox.oldest.pending.age", relay, (r) -> r.oldestPendingAge().toMillis() / 1000.0)
			.description("Age of the oldest unpublished event")
			.baseUnit("seconds")
			.register(meters);
		FunctionCounter.builder("mse.outbox.published", relay, OutboxRelay::publishedCount)
			.description("Events acknowledged by Kafka and marked published")
			.register(meters);
		FunctionCounter.builder("mse.outbox.failures", relay, OutboxRelay::failureCount)
			.description("Relay passes that stopped at an unacknowledged event")
			.register(meters);
		return relay;
	}

}
