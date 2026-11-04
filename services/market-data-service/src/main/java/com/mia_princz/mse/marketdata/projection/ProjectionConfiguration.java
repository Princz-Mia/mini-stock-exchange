package com.mia_princz.mse.marketdata.projection;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
class ProjectionConfiguration {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

	/** Its own mapper, so stored projections never change shape with the web layer's JSON settings. */
	@Bean
	SnapshotProjection snapshotProjection(JdbcClient jdbcClient) {
		return new SnapshotProjection(jdbcClient, JsonMapper.builder().build());
	}

	@Bean
	Checkpoints checkpoints(JdbcClient jdbcClient) {
		return new Checkpoints(jdbcClient);
	}

	@Bean(initMethod = "start", destroyMethod = "close")
	@ConditionalOnProperty(name = "mse.market-feed.enabled", matchIfMissing = true)
	MarketFeedConsumer marketFeed(KafkaProperties kafka, KafkaConnectionDetails connection,
			PlatformTransactionManager transactionManager, SnapshotProjection projection, Checkpoints checkpoints,
			Clock clock, MeterRegistry meters, @Value("${mse.market-feed.poll-timeout:500ms}") Duration pollTimeout,
			@Value("${mse.market-feed.lag-check-interval:2s}") Duration lagCheckInterval,
			@Value("${mse.market-feed.stale-after:10s}") Duration staleAfter,
			@Value("${mse.market-feed.retry-backoff:2s}") Duration retryBackoff) {
		Map<String, Object> config = new HashMap<>(kafka.buildConsumerProperties());
		// The brokers come from the connection details, as for Boot's own clients, not from the properties alone.
		config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, connection.getConsumer().getBootstrapServers());
		// Positions live in projection_checkpoint, committed with the projection; Kafka keeps none.
		config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
		config.remove(ConsumerConfig.GROUP_ID_CONFIG);
		config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		MarketFeedConsumer feed = new MarketFeedConsumer(
				() -> new KafkaConsumer<>(config, new StringDeserializer(), new ByteArrayDeserializer()),
				new TransactionTemplate(transactionManager), projection, checkpoints,
				new MarketFeedConsumer.Settings(SnapshotProjection.TOPIC, pollTimeout, lagCheckInterval, staleAfter,
						retryBackoff),
				clock);
		Gauge.builder("mse.market.feed.lag", feed, MarketFeedConsumer::lag)
			.description("Snapshots on Kafka not yet projected; -1 before the first measurement")
			.register(meters);
		Gauge.builder("mse.market.feed.live", feed, (f) -> f.state() == MarketFeedConsumer.State.LIVE ? 1 : 0)
			.description("1 while the projection is caught up and Kafka answers")
			.register(meters);
		FunctionCounter.builder("mse.market.feed.applied", feed, MarketFeedConsumer::appliedSnapshots)
			.description("Snapshots that replaced an instrument's projection")
			.register(meters);
		return feed;
	}

}
