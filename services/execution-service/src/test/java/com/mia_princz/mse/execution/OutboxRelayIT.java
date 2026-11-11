package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TimeoutException;
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
import org.springframework.jdbc.core.simple.JdbcClient;

import com.mia_princz.mse.execution.outbox.JdbcOutbox;
import com.mia_princz.mse.execution.outbox.Outbox;
import com.mia_princz.mse.execution.outbox.OutboxRelay;
import com.mia_princz.mse.execution.outbox.OutboxRelay.OutboxPublishException;

/**
 * The relay against real PostgreSQL and Kafka. The application's own relay is off, so each test drives a relay of
 * its own, one pass at a time. Each test uses its own topic, and leaves nothing pending for the next.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "mse.test.database=outbox-relay", "mse.outbox.relay.enabled=false" })
class OutboxRelayIT {

	private static final AtomicInteger TOPICS = new AtomicInteger();

	private static final OutboxRelay.Settings SETTINGS = new OutboxRelay.Settings(50, Duration.ofMillis(20),
			Duration.ofSeconds(20), Duration.ofMillis(200));

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	DataSource dataSource;

	@Autowired
	KafkaConnectionDetails kafka;

	private final List<OutboxRelay> relays = new ArrayList<>();

	private String topic;

	@BeforeEach
	void topic() {
		StringBuilder name = new StringBuilder("mse.relay");
		for (int n = TOPICS.incrementAndGet(); n > 0; n /= 26) {
			name.append((char) ('a' + n % 26));
		}
		topic = name.append(".v1").toString();
	}

	@AfterEach
	void closeRelays() throws InterruptedException {
		for (OutboxRelay relay : relays) {
			relay.close();
		}
	}

	@Test
	void publishesInIdOrderAndMarksEachEventPublished() throws Exception {
		List<Long> ids = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			ids.add(insert(i % 2 == 0 ? "even" : "odd", "event-" + i));
		}
		OutboxRelay relay = relay(producers(), new JdbcOutbox(JdbcClient.create(dataSource)));

		assertThat(relay.relayOnce()).isEqualTo(6);
		assertThat(relay.relayOnce()).isZero();

		List<ConsumerRecord<String, byte[]>> records = consume(6);
		assertThat(values(records, "even")).containsExactly("event-0", "event-2", "event-4");
		assertThat(values(records, "odd")).containsExactly("event-1", "event-3", "event-5");
		assertThat(unpublished(ids)).isEmpty();
		assertThat(relay.publishedCount()).isEqualTo(6);
	}

	@Test
	void uncommittedEventsNeverReachKafkaAndLateCommitsAreNotSkipped() throws Exception {
		Outbox jdbcOutbox = new JdbcOutbox(JdbcClient.create(dataSource));
		try (Connection rolledBack = dataSource.getConnection(); Connection committedLate = dataSource.getConnection()) {
			rolledBack.setAutoCommit(false);
			committedLate.setAutoCommit(false);
			// Commits after the relay read its batch and before it marks it, the narrowest window there is.
			Outbox commitsBeforeMarking = new Outbox() {

				@Override
				public List<PendingEvent> pending(int limit) {
					return jdbcOutbox.pending(limit);
				}

				@Override
				public void markPublished(List<Long> ids) {
					try {
						if (!committedLate.getAutoCommit()) {
							committedLate.commit();
							committedLate.setAutoCommit(true);
						}
					}
					catch (SQLException ex) {
						throw new IllegalStateException(ex);
					}
					jdbcOutbox.markPublished(ids);
				}

				@Override
				public Backlog backlog() {
					return jdbcOutbox.backlog();
				}

			};
			OutboxRelay relay = relay(producers(), commitsBeforeMarking);
			insert("k", "before");
			insert(rolledBack, "never");
			long late = insert(committedLate, "late");
			long committed = insert("k", "committed");
			assertThat(committed).as("ids are taken at insert, not at commit").isGreaterThan(late);

			// Committed rows on both sides of the late one: marking the range between them would mark it unsent.
			assertThat(relay.relayOnce()).isEqualTo(2);
			rolledBack.rollback();
			assertThat(relay.relayOnce()).as("a lower id that commits later is still found").isEqualTo(1);
		}

		assertThat(consume(3)).extracting(OutboxRelayIT::value).containsExactly("before", "committed", "late");
		assertThat(consumeAll()).extracting(OutboxRelayIT::value).doesNotContain("never");
	}

	@Test
	void eventAcknowledgedButNotMarkedIsSentAgainUnchanged() throws Exception {
		long first = insert("k", "first");
		long second = insert("k", "second");
		AtomicBoolean crashed = new AtomicBoolean();
		Outbox jdbcOutbox = new JdbcOutbox(JdbcClient.create(dataSource));
		Outbox crashingOnce = new Outbox() {

			@Override
			public List<PendingEvent> pending(int limit) {
				return jdbcOutbox.pending(limit);
			}

			@Override
			public void markPublished(List<Long> ids) {
				if (crashed.compareAndSet(false, true)) {
					throw new IllegalStateException("crash after the acknowledgement, before marking");
				}
				jdbcOutbox.markPublished(ids);
			}

			@Override
			public Backlog backlog() {
				return jdbcOutbox.backlog();
			}

		};
		OutboxRelay relay = relay(producers(), crashingOnce);

		assertThatThrownBy(relay::relayOnce).hasMessageContaining("crash after the acknowledgement");
		assertThat(unpublished(List.of(first, second))).containsExactly(first, second);
		assertThat(relay.relayOnce()).isEqualTo(2);

		List<ConsumerRecord<String, byte[]>> records = consume(4);
		assertThat(records).extracting(OutboxRelayIT::value).containsExactly("first", "second", "first", "second");
		assertThat(records.get(2).value()).as("same bytes, so the same event id").isEqualTo(records.get(0).value());
		assertThat(unpublished(List.of(first, second))).isEmpty();
	}

	@Test
	void failedEventStopsTheRelayEvenWhenLaterEventsWereAcknowledged() throws Exception {
		long first = insert("k", "first");
		long failing = insert("k", "failing");
		long other = insert("j", "other");
		long fourth = insert("k", "fourth");
		List<Long> ids = List.of(first, failing, other, fourth);
		AtomicBoolean failed = new AtomicBoolean();
		OutboxRelay relay = relay(failingOnce("failing", failed), new JdbcOutbox(JdbcClient.create(dataSource)));

		// "other" is on another key, so it goes out with "first" and is acknowledged; "fourth" waits for "failing".
		assertThatThrownBy(relay::relayOnce).isInstanceOf(OutboxPublishException.class)
			.hasMessageContaining("outbox event " + failing + " ");
		assertThat(unpublished(ids)).containsExactly(failing, other, fourth);

		assertThat(relay.relayOnce()).isEqualTo(3);
		assertThat(unpublished(ids)).isEmpty();
		List<ConsumerRecord<String, byte[]>> records = consume(5);
		assertThat(values(records, "k")).as("never reordered").containsExactly("first", "failing", "fourth");
		assertThat(values(records, "j")).as("acknowledged but not marked, so sent again").containsExactly("other", "other");
	}

	@Test
	void onlyTheWriterOwnerPublishes() throws Exception {
		long id = insert("k", "waiting");
		OutboxRelay notOwner = new OutboxRelay(new JdbcOutbox(JdbcClient.create(dataSource)), producers(), () -> false,
				SETTINGS, Clock.systemUTC());
		relays.add(notOwner);

		assertThat(notOwner.relayOnce()).isZero();
		assertThat(unpublished(List.of(id))).containsExactly(id);

		relay(producers(), new JdbcOutbox(JdbcClient.create(dataSource))).relayOnce();
	}

	@Test
	void runningRelayRecoversFromAFailureByItself() throws Exception {
		long first = insert("k", "first");
		long second = insert("k", "second");
		AtomicBoolean failed = new AtomicBoolean();
		OutboxRelay relay = relay(failingOnce("first", failed), new JdbcOutbox(JdbcClient.create(dataSource)));

		relay.start();

		waitUntil(() -> unpublished(List.of(first, second)).isEmpty());
		assertThat(failed).isTrue();
		assertThat(relay.failureCount()).isEqualTo(1);
		assertThat(consume(2)).extracting(OutboxRelayIT::value).containsExactly("first", "second");
		waitUntil(() -> relay.health().getDetails().get("pendingEvents").equals(0L)
				&& !relay.health().getDetails().containsKey("lastError"));
		assertThat(relay.health().getStatus().getCode()).isEqualTo("UP");
	}

	private OutboxRelay relay(Supplier<Producer<String, byte[]>> producers, Outbox outbox) {
		OutboxRelay relay = new OutboxRelay(outbox, producers, () -> true, SETTINGS, Clock.systemUTC());
		relays.add(relay);
		return relay;
	}

	private Supplier<Producer<String, byte[]>> producers() {
		return () -> new KafkaProducer<>(producerConfig(), new StringSerializer(), new ByteArraySerializer());
	}

	/** A real producer, except that the first send of one value fails without reaching Kafka. */
	@SuppressWarnings("unchecked")
	private Supplier<Producer<String, byte[]>> failingOnce(String value, AtomicBoolean failed) {
		return () -> {
			Producer<String, byte[]> real = new KafkaProducer<>(producerConfig(), new StringSerializer(),
					new ByteArraySerializer());
			return (Producer<String, byte[]>) Proxy.newProxyInstance(Producer.class.getClassLoader(),
					new Class<?>[] { Producer.class }, (proxy, method, args) -> {
						if (method.getName().equals("send") && args.length == 1
								&& value.equals(new String(((ProducerRecord<String, byte[]>) args[0]).value(),
										StandardCharsets.UTF_8))
								&& failed.compareAndSet(false, true)) {
							return CompletableFuture.failedFuture(new TimeoutException("simulated lost acknowledgement"));
						}
						try {
							return method.invoke(real, args);
						}
						catch (InvocationTargetException ex) {
							throw ex.getCause();
						}
					});
		};
	}

	private Map<String, Object> producerConfig() {
		return Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", kafka.getBootstrapServers()),
				ProducerConfig.ACKS_CONFIG, "all", ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
	}

	private long insert(String key, String value) {
		try (Connection connection = dataSource.getConnection()) {
			return insert(connection, key, value);
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private long insert(Connection connection, String value) throws Exception {
		return insert(connection, "k", value);
	}

	private long insert(Connection connection, String key, String value) throws Exception {
		try (PreparedStatement insert = connection.prepareStatement("""
				INSERT INTO outbox (event_id, topic, event_key, schema_fingerprint, payload, command_sequence)
				VALUES (?, ?, ?, 1, ?, 1) RETURNING id""")) {
			insert.setObject(1, UUID.randomUUID());
			insert.setString(2, topic);
			insert.setString(3, key);
			insert.setBytes(4, value.getBytes(StandardCharsets.UTF_8));
			try (var result = insert.executeQuery()) {
				result.next();
				return result.getLong(1);
			}
		}
	}

	private List<Long> unpublished(List<Long> ids) {
		return jdbc.queryForList("SELECT id FROM outbox WHERE published_at IS NULL AND id = ANY(?) ORDER BY id",
				Long.class, (Object) ids.toArray(Long[]::new));
	}

	/** Every record on this test's topic, waiting until at least {@code expected} have arrived. */
	private List<ConsumerRecord<String, byte[]>> consume(int expected) {
		return poll((records) -> records.size() >= expected, Duration.ofSeconds(20));
	}

	/** Every record that arrives within a short quiet period. */
	private List<ConsumerRecord<String, byte[]>> consumeAll() {
		return poll((records) -> false, Duration.ofSeconds(2));
	}

	private List<ConsumerRecord<String, byte[]>> poll(Predicate<List<ConsumerRecord<String, byte[]>>> done,
			Duration timeout) {
		Map<String, Object> config = Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
				String.join(",", kafka.getBootstrapServers()), ConsumerConfig.GROUP_ID_CONFIG,
				"relay-test-" + UUID.randomUUID(), ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
		try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(config, new StringDeserializer(),
				new ByteArrayDeserializer())) {
			consumer.subscribe(List.of(topic));
			long deadline = System.nanoTime() + timeout.toNanos();
			while (!done.test(records) && System.nanoTime() < deadline) {
				consumer.poll(Duration.ofMillis(200)).forEach(records::add);
			}
		}
		records.sort((a, b) -> a.partition() != b.partition() ? Integer.compare(a.partition(), b.partition())
				: Long.compare(a.offset(), b.offset()));
		return records;
	}

	private static List<String> values(List<ConsumerRecord<String, byte[]>> records, String key) {
		return records.stream().filter((record) -> record.key().equals(key)).map(OutboxRelayIT::value).toList();
	}

	private static String value(ConsumerRecord<String, byte[]> record) {
		return new String(record.value(), StandardCharsets.UTF_8);
	}

	private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
		while (!condition.getAsBoolean()) {
			assertThat(System.nanoTime()).as("condition not met in time").isLessThan(deadline);
			Thread.sleep(50);
		}
	}

}
