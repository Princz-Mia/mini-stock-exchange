package com.mia_princz.mse.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.mia_princz.mse.contracts.events.MarketSnapshot;
import com.mia_princz.mse.contracts.events.WireEvents;
import com.mia_princz.mse.marketdata.projection.Checkpoints;
import com.mia_princz.mse.marketdata.projection.MarketFeedConsumer;
import com.mia_princz.mse.marketdata.projection.MarketFeedConsumer.State;
import com.mia_princz.mse.marketdata.projection.SnapshotProjection;

/**
 * The feed against real Kafka and PostgreSQL. The application's own feed is off; each test drives feeds of its own
 * on a topic of its own with three partitions.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "mse.test.database=market-feed", "mse.market-feed.enabled=false" })
class MarketFeedConsumerIT {

	private static final AtomicInteger TOPICS = new AtomicInteger();

	@Autowired
	KafkaConnectionDetails kafka;

	@Autowired
	PostgreSQLContainer postgres;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	SnapshotProjection projection;

	@Autowired
	Checkpoints checkpoints;

	private final List<MarketFeedConsumer> feeds = new ArrayList<>();

	private String topic;

	@BeforeEach
	void topic() throws Exception {
		StringBuilder name = new StringBuilder("mse.feed");
		for (int n = TOPICS.incrementAndGet(); n > 0; n /= 26) {
			name.append((char) ('a' + n % 26));
		}
		topic = name.append(".v1").toString();
		try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, brokers()))) {
			admin.createTopics(List.of(new NewTopic(topic, 3, (short) 1))).all().get();
		}
	}

	@AfterEach
	void closeFeeds() throws InterruptedException {
		for (MarketFeedConsumer feed : feeds) {
			feed.close();
		}
	}

	/** Never live while behind: the moment the feed first says LIVE, all 30 snapshots have been applied. */
	@Test
	void becomesLiveOnlyAfterCatchingUp() throws Exception {
		List<UUID> instruments = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
		Map<UUID, Long> newest = new HashMap<>();
		for (long sequence = 1; sequence <= 30; sequence++) {
			UUID instrument = instruments.get((int) (sequence % 3));
			publish(Snapshots.snapshot(instrument, sequence, 2));
			newest.put(instrument, sequence);
		}
		// One record per poll, so catching up takes many polls and an early LIVE would be seen.
		MarketFeedConsumer feed = feed(Map.of(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1));

		feed.start();

		long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
		while (feed.state() != State.LIVE && System.nanoTime() < deadline) {
			Thread.onSpinWait();
		}
		long appliedWhenLive = feed.appliedSnapshots();
		assertThat(feed.state()).isEqualTo(State.LIVE);
		assertThat(appliedWhenLive).as("applied when first live").isEqualTo(30);
		for (UUID instrument : instruments) {
			assertThat(sequence(instrument)).isEqualTo(newest.get(instrument));
		}
		assertThat(feed.health().getStatus().getCode()).isEqualTo("UP");
	}

	@Test
	void restartResumesFromTheCheckpoints() throws Exception {
		UUID instrument = UUID.randomUUID();
		publish(Snapshots.snapshot(instrument, 1, 1));
		publish(Snapshots.snapshot(instrument, 2, 1));
		MarketFeedConsumer first = feed(Map.of());
		first.start();
		waitUntil(() -> first.state() == State.LIVE);
		first.close();

		publish(Snapshots.snapshot(instrument, 3, 1));
		MarketFeedConsumer second = feed(Map.of());
		second.start();
		waitUntil(() -> sequenceOrZero(instrument) == 3);

		waitUntil(() -> second.state() == State.LIVE);
		assertThat(second.appliedSnapshots()).as("only the new snapshot was read").isEqualTo(1);
		assertThat(checkpoints.load(topic).values().stream().mapToLong(Long::longValue).sum()).isEqualTo(3);
	}

	@Test
	void emptyDatabaseRebuildsFromTheRetainedTopic() throws Exception {
		UUID instrument = UUID.randomUUID();
		for (long sequence = 1; sequence <= 5; sequence++) {
			publish(Snapshots.snapshot(instrument, sequence, (int) sequence));
		}
		MarketFeedConsumer first = feed(Map.of());
		first.start();
		waitUntil(() -> first.state() == State.LIVE);
		first.close();
		Map<String, Object> before = market(instrument);

		try (Connection owner = DriverManager.getConnection(TestcontainersConfiguration.jdbcUrl(postgres, "market_data_db"),
				"market_data_owner", TestcontainersConfiguration.MARKET_DATA_OWNER_PASSWORD);
				Statement wipe = owner.createStatement()) {
			wipe.execute("TRUNCATE market_snapshot, instrument_projection, processed_event, projection_checkpoint");
		}
		assertThat(sequenceOrZero(instrument)).isZero();

		MarketFeedConsumer rebuilt = feed(Map.of());
		rebuilt.start();
		waitUntil(() -> rebuilt.state() == State.LIVE);

		assertThat(market(instrument)).isEqualTo(before);
	}

	@Test
	void unreadableRecordPausesTheFeedAtItsOffset() throws Exception {
		UUID instrument = UUID.randomUUID();
		String key = Snapshots.SIMULATION + "|" + instrument;
		publish(Snapshots.snapshot(instrument, 1, 1));
		int partition = send(key, new byte[] { 1, 2, 3 });
		publish(Snapshots.snapshot(instrument, 2, 1));
		MarketFeedConsumer feed = feed(Map.of());

		feed.start();

		waitUntil(() -> feed.state() == State.PAUSED);
		assertThat(sequence(instrument)).as("nothing after the bad record").isEqualTo(1);
		assertThat(checkpoints.load(topic).get(partition)).as("stopped at the bad offset").isEqualTo(1);
		assertThat(feed.health().getDetails()).containsEntry("pausedAt", topic + "/" + partition + "@1");
		assertThat(feed.health().getStatus().getCode()).isEqualTo("OUT_OF_SERVICE");
	}

	@Test
	void withoutKafkaTheFeedIsNeverLive() throws Exception {
		Map<String, Object> config = consumerConfig(Map.of());
		config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1");
		MarketFeedConsumer feed = new MarketFeedConsumer(
				() -> new KafkaConsumer<>(config, new StringDeserializer(), new ByteArrayDeserializer()),
				new TransactionTemplate(transactionManager), projection, checkpoints, settings(), Clock.systemUTC());
		feeds.add(feed);

		feed.start();

		waitUntil(() -> feed.health().getDetails().containsKey("error"));
		assertThat(feed.state()).isEqualTo(State.STARTING);
		assertThat(feed.health().getStatus().getCode()).isEqualTo("OUT_OF_SERVICE");
	}

	private MarketFeedConsumer feed(Map<String, Object> overrides) {
		Map<String, Object> config = consumerConfig(overrides);
		MarketFeedConsumer feed = new MarketFeedConsumer(
				() -> new KafkaConsumer<>(config, new StringDeserializer(), new ByteArrayDeserializer()),
				new TransactionTemplate(transactionManager), projection, checkpoints, settings(), Clock.systemUTC());
		feeds.add(feed);
		return feed;
	}

	private MarketFeedConsumer.Settings settings() {
		return new MarketFeedConsumer.Settings(topic, Duration.ofMillis(100), Duration.ofMillis(200),
				Duration.ofSeconds(1), Duration.ofMillis(200));
	}

	private Map<String, Object> consumerConfig(Map<String, Object> overrides) {
		Map<String, Object> config = new HashMap<>();
		config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers());
		config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
		config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		config.putAll(overrides);
		return config;
	}

	private String brokers() {
		return String.join(",", kafka.getBootstrapServers());
	}

	private void publish(MarketSnapshot snapshot) throws Exception {
		send(snapshot.getSimulationId() + "|" + snapshot.getInstrumentId(), WireEvents.encode(snapshot));
	}

	private int send(String key, byte[] value) throws Exception {
		try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(
				Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers(), ProducerConfig.ACKS_CONFIG, "all"),
				new StringSerializer(), new ByteArraySerializer())) {
			return producer.send(new ProducerRecord<>(topic, key, value)).get().partition();
		}
	}

	private long sequence(UUID instrument) {
		return jdbc.queryForObject("SELECT source_command_sequence FROM market_snapshot WHERE instrument_id = ?",
				Long.class, instrument);
	}

	private long sequenceOrZero(UUID instrument) {
		return jdbc.queryForObject(
				"SELECT coalesce(max(source_command_sequence), 0) FROM market_snapshot WHERE instrument_id = ?",
				Long.class, instrument);
	}

	private Map<String, Object> market(UUID instrument) {
		return jdbc.queryForMap("""
				SELECT venue_state, bids::text, asks::text, recent_trades::text, last_price_cents, last_trade_sequence,
				    source_command_sequence, generated_at FROM market_snapshot WHERE instrument_id = ?""", instrument);
	}

	private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
		while (!condition.getAsBoolean()) {
			assertThat(System.nanoTime()).as("condition not met in time").isLessThan(deadline);
			Thread.sleep(50);
		}
	}

}
