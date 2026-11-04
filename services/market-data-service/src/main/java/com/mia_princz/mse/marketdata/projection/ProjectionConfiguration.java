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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

import com.mia_princz.mse.contracts.events.MarketSnapshot;
import com.mia_princz.mse.contracts.events.WireEvents;

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
	TradeProjection tradeProjection(JdbcClient jdbcClient) {
		return new TradeProjection(jdbcClient);
	}

	@Bean
	Checkpoints checkpoints(JdbcClient jdbcClient) {
		return new Checkpoints(jdbcClient);
	}

	@Bean
	FeedFactory feedFactory(KafkaProperties kafka, KafkaConnectionDetails connection,
			PlatformTransactionManager transactionManager, Checkpoints checkpoints, Clock clock, MeterRegistry meters,
			@Value("${mse.feed.poll-timeout:500ms}") Duration pollTimeout,
			@Value("${mse.feed.lag-check-interval:2s}") Duration lagCheckInterval,
			@Value("${mse.feed.stale-after:10s}") Duration staleAfter,
			@Value("${mse.feed.retry-backoff:2s}") Duration retryBackoff) {
		Map<String, Object> config = new HashMap<>(kafka.buildConsumerProperties());
		// The brokers come from the connection details, as for Boot's own clients, not from the properties alone.
		config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, connection.getConsumer().getBootstrapServers());
		// Positions live in projection_checkpoint, committed with the projection; Kafka keeps none.
		config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
		config.remove(ConsumerConfig.GROUP_ID_CONFIG);
		config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		return (topic, handler) -> {
			Map<String, Object> feedConfig = new HashMap<>(config);
			feedConfig.put(ConsumerConfig.CLIENT_ID_CONFIG, "market-data-" + topic);
			MarketFeedConsumer feed = new MarketFeedConsumer(
					() -> new KafkaConsumer<>(feedConfig, new StringDeserializer(), new ByteArrayDeserializer()),
					new TransactionTemplate(transactionManager), handler, checkpoints,
					new MarketFeedConsumer.Settings(topic, pollTimeout, lagCheckInterval, staleAfter, retryBackoff),
					clock);
			Gauge.builder("mse.feed.lag", feed, MarketFeedConsumer::lag)
				.description("Records on Kafka not yet projected; -1 before the first measurement")
				.tag("topic", topic)
				.register(meters);
			Gauge.builder("mse.feed.live", feed, (f) -> f.state() == MarketFeedConsumer.State.LIVE ? 1 : 0)
				.description("1 while the projection is caught up and Kafka answers")
				.tag("topic", topic)
				.register(meters);
			FunctionCounter.builder("mse.feed.applied", feed, MarketFeedConsumer::appliedEvents)
				.description("Records that changed the projection")
				.tag("topic", topic)
				.register(meters);
			return feed;
		};
	}

	@Bean(initMethod = "start", destroyMethod = "close")
	@ConditionalOnProperty(name = "mse.market-feed.enabled", matchIfMissing = true)
	MarketFeedConsumer marketFeed(FeedFactory feeds, SnapshotProjection projection,
			ApplicationEventPublisher events) {
		return feeds.create(SnapshotProjection.TOPIC, (payload) -> {
			MarketSnapshot snapshot = WireEvents.decodeSnapshot(payload);
			boolean changed = projection.apply(snapshot);
			if (changed) {
				// Inside the batch transaction; after-commit listeners see it only if the batch commits.
				events.publishEvent(new SnapshotProjected(snapshot.getInstrumentId()));
			}
			return changed;
		});
	}

	@Bean(initMethod = "start", destroyMethod = "close")
	@ConditionalOnProperty(name = "mse.trade-feed.enabled", matchIfMissing = true)
	MarketFeedConsumer tradeFeed(FeedFactory feeds, TradeProjection projection) {
		return feeds.create(TradeProjection.TOPIC, (payload) -> projection.apply(WireEvents.decodeTrade(payload)));
	}

	/** One feed per topic, each with its own thread, health component and tagged metrics. */
	@FunctionalInterface
	interface FeedFactory {

		MarketFeedConsumer create(String topic, MarketFeedConsumer.Handler handler);

	}

}
