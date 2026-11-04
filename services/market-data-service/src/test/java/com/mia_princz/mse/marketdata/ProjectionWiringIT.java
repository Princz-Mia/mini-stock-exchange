package com.mia_princz.mse.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mia_princz.mse.contracts.events.MarketSnapshot;
import com.mia_princz.mse.contracts.events.TradeExecuted;
import com.mia_princz.mse.contracts.events.WireEvents;
import com.mia_princz.mse.marketdata.projection.MarketFeedConsumer;

/** The application's own feeds, as configured: each real topic reaches its own projection. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "mse.test.database=projection-wiring", "mse.feed.lag-check-interval=200ms" })
class ProjectionWiringIT {

	@Autowired
	KafkaConnectionDetails kafka;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	@Qualifier("marketFeed")
	MarketFeedConsumer marketFeed;

	@Autowired
	@Qualifier("tradeFeed")
	MarketFeedConsumer tradeFeed;

	@Test
	void eachTopicReachesItsProjection() throws Exception {
		UUID instrument = UUID.randomUUID();
		MarketSnapshot snapshot = Snapshots.snapshot(instrument, 3, 2);
		TradeExecuted trade = Trades.trade(instrument, 3);
		String key = Snapshots.SIMULATION + "|" + instrument;
		try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(
				Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", kafka.getBootstrapServers())),
				new StringSerializer(), new ByteArraySerializer())) {
			producer.send(new ProducerRecord<>("mse.market.v1", key, WireEvents.encode(snapshot))).get();
			producer.send(new ProducerRecord<>("mse.trades.v1", key, WireEvents.encode(trade))).get();
		}

		long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
		while ((count("market_snapshot", instrument) == 0 || count("public_trade", instrument) == 0)
				&& System.nanoTime() < deadline) {
			Thread.sleep(100);
		}

		assertThat(count("market_snapshot", instrument)).isEqualTo(1);
		assertThat(count("public_trade", instrument)).isEqualTo(1);
		assertThat(marketFeed.health().getDetails()).doesNotContainKey("pausedAt");
		assertThat(tradeFeed.health().getDetails()).doesNotContainKey("pausedAt");
	}

	private long count(String table, UUID instrument) {
		return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE instrument_id = ?", Long.class,
				instrument);
	}

}
