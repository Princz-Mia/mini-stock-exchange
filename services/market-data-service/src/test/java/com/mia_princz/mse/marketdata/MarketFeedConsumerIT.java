package com.mia_princz.mse.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.RecordsToDelete;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
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

import com.mia_princz.mse.contracts.events.CandleUpdated;
import com.mia_princz.mse.contracts.events.MarketSnapshot;
import com.mia_princz.mse.contracts.events.TradeExecuted;
import com.mia_princz.mse.contracts.events.WireEvents;
import com.mia_princz.mse.marketdata.projection.Checkpoints;
import com.mia_princz.mse.marketdata.projection.MarketFeedConsumer;
import com.mia_princz.mse.marketdata.projection.MarketFeedConsumer.State;
import com.mia_princz.mse.marketdata.projection.SnapshotProjection;
import com.mia_princz.mse.marketdata.projection.CandleProjection;
import com.mia_princz.mse.marketdata.projection.TradeProjection;

/**
 * The feed against real Kafka and PostgreSQL. The application's own feed is off; each test drives feeds of its own
 * on a topic of its own with three partitions.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "mse.test.database=market-feed", "mse.market-feed.enabled=false",
		"mse.trade-feed.enabled=false" })
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
	TradeProjection tradeProjection;

	@Autowired
	CandleProjection candleProjection;

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
		long appliedWhenLive = feed.appliedEvents();
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
		assertThat(second.appliedEvents()).as("only the new snapshot was read").isEqualTo(1);
		assertThat(second.health().getDetails()).as("nothing was deleted").doesNotContainKey("historyGap");
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
		List<Map<String, Object>> minutesBefore = minutes(instrument);

		try (Connection owner = DriverManager.getConnection(TestcontainersConfiguration.jdbcUrl(postgres, "market_data_db"),
				"market_data_owner", TestcontainersConfiguration.MARKET_DATA_OWNER_PASSWORD);
				Statement wipe = owner.createStatement()) {
			wipe.execute("TRUNCATE market_snapshot, instrument_projection, processed_event, projection_checkpoint, spread_minute");
		}
		assertThat(sequenceOrZero(instrument)).isZero();

		MarketFeedConsumer rebuilt = feed(Map.of());
		rebuilt.start();
		waitUntil(() -> rebuilt.state() == State.LIVE);

		assertThat(market(instrument)).isEqualTo(before);
		// Nothing compacted yet, so every snapshot is read again, and the book's minutes come back as they were.
		assertThat(minutes(instrument)).isEqualTo(minutesBefore).isNotEmpty();
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

	/**
	 * One poll of rising snapshots with a repeat and an older one at the end, applied at once: the newest stays, the
	 * poll moves the checkpoint, and the feed counts the three snapshots that one by one would have applied.
	 */
	@Test
	void anOlderSnapshotAfterANewerInOnePollChangesNothing() throws Exception {
		UUID instrument = UUID.randomUUID();
		MarketSnapshot second = Snapshots.snapshot(instrument, 2, 2);
		publish(Snapshots.snapshot(instrument, 1, 1));
		publish(second);
		publish(second);
		publish(Snapshots.snapshot(instrument, 4, 1));
		publish(Snapshots.snapshot(instrument, 3, 1));
		List<Integer> attempts = new CopyOnWriteArrayList<>();
		MarketFeedConsumer feed = feed(Map.of(), recording(projection.handler((changed) -> {
		}), attempts));

		feed.start();

		waitUntil(() -> feed.state() == State.LIVE);
		assertThat(attempts).as("the five snapshots applied at once").contains(5);
		assertThat(sequence(instrument)).isEqualTo(4);
		assertThat(feed.appliedEvents()).as("snapshots 1, 2 and 4 each changed the market").isEqualTo(3);
		assertThat(checkpoints.load(topic).values().stream().mapToLong(Long::longValue).sum()).isEqualTo(5);
	}

	/**
	 * A snapshot the tables refuse inside a poll, though a newer one follows: the attempt at once fails, and one by
	 * one keeps the snapshot before it and pauses at it.
	 */
	@Test
	void aSnapshotTheTablesRefuseInsideABatchPausesTheFeed() throws Exception {
		UUID instrument = UUID.randomUUID();
		publish(Snapshots.snapshot(instrument, 1, 1));
		MarketSnapshot refused = Snapshots.snapshot(instrument, 2, 1);
		refused.getRules().setTickCents(0L);
		int partition = send(refused.getSimulationId() + "|" + instrument, WireEvents.encode(refused));
		publish(Snapshots.snapshot(instrument, 3, 1));
		List<Integer> attempts = new CopyOnWriteArrayList<>();
		MarketFeedConsumer feed = feed(Map.of(), recording(projection.handler((changed) -> {
		}), attempts));

		feed.start();

		waitUntil(() -> feed.state() == State.PAUSED);
		assertThat(attempts).as("the three snapshots tried at once first").contains(3);
		assertThat(sequence(instrument)).isEqualTo(1);
		assertThat(feed.health().getDetails()).containsEntry("pausedAt", topic + "/" + partition + "@1");
		assertThat(checkpoints.load(topic).get(partition)).isEqualTo(1);
	}

	/**
	 * A crossed book can't be a minute's sample. Inside a batch it fails the poll, which is then applied one by one and
	 * stops at it, as for any snapshot the tables refuse: the snapshot before it stays, the one after waits.
	 */
	@Test
	void aCrossedBookInsideABatchPausesTheFeed() throws Exception {
		UUID instrument = UUID.randomUUID();
		Instant at = Instant.parse("2026-11-20T10:00:00Z");
		publish(Snapshots.book(instrument, 1, at, 10_000L, 10_100L, null));
		MarketSnapshot crossed = Snapshots.book(instrument, 2, at, 10_100L, 10_100L, null);
		int partition = send(crossed.getSimulationId() + "|" + instrument, WireEvents.encode(crossed));
		publish(Snapshots.book(instrument, 3, at, 10_000L, 10_100L, null));
		List<Integer> attempts = new CopyOnWriteArrayList<>();
		MarketFeedConsumer feed = feed(Map.of(), recording(projection.handler((changed) -> {
		}), attempts));

		feed.start();

		waitUntil(() -> feed.state() == State.PAUSED);
		assertThat(attempts).as("the three snapshots tried at once first").contains(3);
		assertThat(sequence(instrument)).isEqualTo(1);
		assertThat(minutes(instrument)).extracting((minute) -> minute.get("last_sequence")).containsExactly(1L);
		assertThat(feed.health().getDetails()).containsEntry("pausedAt", topic + "/" + partition + "@1");
	}

	/**
	 * A removal's place in the order matters, so a poll holding one is applied one by one: an instrument added,
	 * removed and added again in one poll is there, and one added and then removed is not.
	 */
	@Test
	void aPollWithARemovalIsAppliedInOrder() throws Exception {
		UUID readded = UUID.randomUUID();
		UUID removed = UUID.randomUUID();
		publish(Snapshots.snapshot(readded, 1, 1));
		publish(Snapshots.removed(readded, 2));
		publish(Snapshots.snapshot(readded, 3, 1));
		publish(Snapshots.snapshot(removed, 1, 1));
		publish(Snapshots.removed(removed, 2));
		MarketFeedConsumer feed = feed(Map.of());

		feed.start();

		waitUntil(() -> feed.state() == State.LIVE);
		assertThat(sequence(readded)).isEqualTo(3);
		assertThat(sequenceOrZero(removed)).as("removed").isZero();
	}

	/** A record that cannot be decoded inside a poll: the records before it are kept, and the feed pauses at it. */
	@Test
	void aBadRecordInsideABatchKeepsTheRecordsBeforeIt() throws Exception {
		UUID instrument = UUID.randomUUID();
		String key = Snapshots.SIMULATION + "|" + instrument;
		publish(Snapshots.snapshot(instrument, 1, 1));
		publish(Snapshots.snapshot(instrument, 2, 1));
		int partition = send(key, new byte[] { 9, 9 });
		publish(Snapshots.snapshot(instrument, 3, 1));
		MarketFeedConsumer feed = feed(Map.of());

		feed.start();

		waitUntil(() -> feed.state() == State.PAUSED);
		assertThat(sequence(instrument)).isEqualTo(2);
		assertThat(checkpoints.load(topic).get(partition)).as("at the bad record, not past it").isEqualTo(2);
	}

	/** The same trade redelivered, and again under a new event id: one tape row. */
	@Test
	void tradesAreProjectedOncePerTradeId() throws Exception {
		UUID instrument = UUID.randomUUID();
		TradeExecuted trade = Trades.trade(instrument, 1);
		publishTrade(trade);
		publishTrade(trade);
		publishTrade(TradeExecuted.newBuilder(trade).setEventId(UUID.randomUUID()).build());
		publishTrade(Trades.trade(instrument, 2));
		MarketFeedConsumer feed = feed(Map.of(), tradeHandler());

		feed.start();

		waitUntil(() -> feed.state() == State.LIVE);
		assertThat(jdbc.queryForList("SELECT trade_sequence FROM public_trade WHERE instrument_id = ? ORDER BY 1",
				Long.class, instrument)).containsExactly(1L, 2L);
		assertThat(feed.appliedEvents()).isEqualTo(2);
	}

	/** A different trade under a sequence already projected is a contradiction: the feed pauses there. */
	@Test
	void contradictoryTradePausesTheFeed() throws Exception {
		UUID instrument = UUID.randomUUID();
		publishTrade(Trades.trade(instrument, 1));
		TradeExecuted contradiction = Trades.trade(instrument, 1);
		publishTrade(contradiction);
		publishTrade(Trades.trade(instrument, 2));
		MarketFeedConsumer feed = feed(Map.of(), tradeHandler());

		feed.start();

		waitUntil(() -> feed.state() == State.PAUSED);
		assertThat(feed.health().getDetails().get("error").toString()).contains("another trade already has");
		assertThat(jdbc.queryForList("SELECT trade_sequence FROM public_trade WHERE instrument_id = ?", Long.class,
				instrument)).as("nothing after the contradiction").containsExactly(1L);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ?", Long.class,
				contradiction.getEventId()))
			.as("not marked as seen, so it is read again after a fix instead of skipped")
			.isZero();
	}

	/**
	 * A contradiction found after a poll's writes, applied at once: the whole attempt is rolled back, so the poll goes
	 * one by one from a clean state, keeps the trades before the contradiction, and pauses at it.
	 */
	@Test
	void aContradictionInsideABatchUndoesTheBatchsWrites() throws Exception {
		UUID instrument = UUID.randomUUID();
		publishTrade(Trades.trade(instrument, 1));
		publishTrade(Trades.trade(instrument, 2));
		TradeExecuted contradiction = Trades.trade(instrument, 1);
		int partition = send(contradiction.getSimulationId() + "|" + contradiction.getInstrumentId(),
				WireEvents.encode(contradiction));
		TradeExecuted after = Trades.trade(instrument, 3);
		publishTrade(after);
		List<Integer> attempts = new CopyOnWriteArrayList<>();
		MarketFeedConsumer feed = feed(Map.of(), recording(tradeProjection.handler((changed) -> {
		}), attempts));

		feed.start();

		waitUntil(() -> feed.state() == State.PAUSED);
		assertThat(attempts).as("the four trades tried at once first").contains(4);
		assertThat(jdbc.queryForList("SELECT trade_sequence FROM public_trade WHERE instrument_id = ? ORDER BY 1",
				Long.class, instrument)).containsExactly(1L, 2L);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_event WHERE event_id IN (?, ?)", Long.class,
				contradiction.getEventId(), after.getEventId())).as("nothing from the attempt kept").isZero();
		assertThat(checkpoints.load(topic).get(partition)).as("at the contradiction").isEqualTo(2);
	}

	/**
	 * Two trades whose notionals each fit in a long but whose sum does not: the attempt at once, which adds them up in
	 * Java, fails, and one by one, which adds them in the database's NUMERIC, applies both. The feed goes live instead of
	 * retrying the poll.
	 */
	@Test
	void aSumOnlyTheBatchAddsUpFallsBackInsteadOfRetrying() throws Exception {
		UUID instrument = UUID.randomUUID();
		for (int sequence = 1; sequence <= 2; sequence++) {
			publishTrade(TradeExecuted.newBuilder(Trades.trade(instrument, sequence))
				.setPriceCents(1_000_000_000L)
				.setQuantity(5_000_000_000L)
				.build());
		}
		List<Integer> attempts = new CopyOnWriteArrayList<>();
		MarketFeedConsumer feed = feed(Map.of(), recording(tradeProjection.handler((changed) -> {
		}), attempts));

		feed.start();

		waitUntil(() -> feed.state() == State.LIVE);
		assertThat(attempts).as("both trades tried at once first").contains(2);
		assertThat(jdbc.queryForObject("SELECT notional_cents::text FROM instrument_session WHERE instrument_id = ?",
				String.class, instrument)).isEqualTo("10000000000000000000");
	}

	/**
	 * A delay is reported for each record committed while the feed is live, once: not for the history read while
	 * catching up, not for an attempt at once that was rolled back, and not for the record the feed paused at.
	 */
	@Test
	void delaysAreReportedOnceForRecordsCommittedWhileLive() throws Exception {
		UUID instrument = UUID.randomUUID();
		publishTrade(Trades.trade(instrument, 1));
		publishTrade(Trades.trade(instrument, 2));
		List<Long> delays = new CopyOnWriteArrayList<>();
		List<Integer> attempts = new CopyOnWriteArrayList<>();
		MarketFeedConsumer feed = feed(Map.of(), recording(tradeProjection.handler((changed) -> {
		}), attempts), delays::add);

		feed.start();

		waitUntil(() -> feed.state() == State.LIVE);
		assertThat(delays).as("history read while catching up").isEmpty();
		// Sent together, so the live feed polls them at once: the attempt fails at the contradiction and rolls back,
		// and one by one keeps trades 3 and 4 and pauses at it.
		publishTogether(List.of(Trades.trade(instrument, 3), Trades.trade(instrument, 4), Trades.trade(instrument, 1),
				Trades.trade(instrument, 5)));
		waitUntil(() -> feed.state() == State.PAUSED);
		assertThat(attempts).as("the live trades tried at once first").contains(4);
		assertThat(delays).as("trades 3 and 4, each once").hasSize(2).allSatisfy((millis) -> assertThat(millis)
			.isBetween(0L, 30_000L));
	}

	/** A candle the database refuses is unusable too: the feed pauses at it instead of retrying it forever. */
	@Test
	void aCandleTheDatabaseRefusesPausesTheFeed() throws Exception {
		UUID instrument = UUID.randomUUID();
		CandleUpdated good = Candles.candle(1, instrument, Candles.MINUTE, 1);
		CandleUpdated impossible = CandleUpdated.newBuilder(Candles.candle(1, instrument, Candles.MINUTE.plusSeconds(60), 2))
			.setLowCents(20_000)
			.build();
		int partition = send(Snapshots.SIMULATION + "|" + instrument, WireEvents.encode(good));
		send(Snapshots.SIMULATION + "|" + instrument, WireEvents.encode(impossible));
		MarketFeedConsumer feed = feed(Map.of(), (payload) -> candleProjection.apply(WireEvents.decodeCandle(payload)));

		feed.start();

		waitUntil(() -> feed.state() == State.PAUSED);
		assertThat(feed.health().getDetails().get("pausedAt").toString()).startsWith(topic + "/" + partition + "@");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM candle_1m WHERE instrument_id = ?", Long.class,
				instrument)).as("the good candle before it committed").isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ?", Long.class,
				impossible.getEventId())).isZero();
	}

	/** Retention deleted records past the checkpoint: the feed says which ones, then carries on from what is left. */
	@Test
	void recordsDeletedBeforeProjectionAreReportedAsAGap() throws Exception {
		UUID instrument = UUID.randomUUID();
		int partition = send(Snapshots.SIMULATION + "|" + instrument,
				WireEvents.encode(Snapshots.snapshot(instrument, 1, 1)));
		MarketFeedConsumer first = feed(Map.of());
		first.start();
		waitUntil(() -> first.state() == State.LIVE);
		first.close();
		// Deleted exactly up to the checkpoint: nothing unread was lost.
		deleteBefore(partition, 1);
		MarketFeedConsumer second = feed(Map.of());
		second.start();
		waitUntil(() -> second.state() == State.LIVE);
		second.close();
		assertThat(second.health().getDetails()).as("nothing unread deleted").doesNotContainKey("historyGap");
		for (long sequence = 2; sequence <= 4; sequence++) {
			publish(Snapshots.snapshot(instrument, sequence, 1));
		}
		deleteBefore(partition, 3);
		MarketFeedConsumer third = feed(Map.of());

		third.start();

		waitUntil(() -> third.state() == State.LIVE);
		assertThat(third.health().getDetails()).containsEntry("historyGap",
				topic + "-" + partition + " offsets 1 to 2");
		assertThat(sequence(instrument)).as("continued from the oldest retained record").isEqualTo(4);
	}

	@Test
	void withoutKafkaTheFeedIsNeverLive() throws Exception {
		Map<String, Object> config = consumerConfig(Map.of());
		config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1");
		MarketFeedConsumer feed = new MarketFeedConsumer(
				() -> new KafkaConsumer<>(config, new StringDeserializer(), new ByteArrayDeserializer()),
				new TransactionTemplate(transactionManager), snapshotHandler(), checkpoints, settings(),
				Clock.systemUTC());
		feeds.add(feed);

		feed.start();

		waitUntil(() -> feed.health().getDetails().containsKey("error"));
		assertThat(feed.state()).isEqualTo(State.STARTING);
		assertThat(feed.health().getStatus().getCode()).isEqualTo("OUT_OF_SERVICE");
	}

	private void deleteBefore(int partition, long offset) throws Exception {
		try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, brokers()))) {
			admin.deleteRecords(Map.of(new TopicPartition(topic, partition), RecordsToDelete.beforeOffset(offset)))
				.all()
				.get();
		}
	}

	private MarketFeedConsumer feed(Map<String, Object> overrides) {
		return feed(overrides, snapshotHandler());
	}

	private MarketFeedConsumer feed(Map<String, Object> overrides, MarketFeedConsumer.Handler handler) {
		return feed(overrides, handler, (millis) -> {
		});
	}

	private MarketFeedConsumer feed(Map<String, Object> overrides, MarketFeedConsumer.Handler handler,
			LongConsumer delays) {
		Map<String, Object> config = consumerConfig(overrides);
		MarketFeedConsumer feed = new MarketFeedConsumer(
				() -> new KafkaConsumer<>(config, new StringDeserializer(), new ByteArrayDeserializer()),
				new TransactionTemplate(transactionManager), handler, checkpoints, settings(), Clock.systemUTC(),
				delays);
		feeds.add(feed);
		return feed;
	}

	/** The service's handler, with the size of each poll it was asked to apply at once added to {@code attempts}. */
	private static MarketFeedConsumer.BatchHandler recording(MarketFeedConsumer.BatchHandler service,
			List<Integer> attempts) {
		return new MarketFeedConsumer.BatchHandler() {

			@Override
			public boolean apply(byte[] payload) throws IOException {
				return service.apply(payload);
			}

			@Override
			public OptionalInt applyAll(List<byte[]> payloads) throws IOException {
				attempts.add(payloads.size());
				return service.applyAll(payloads);
			}

		};
	}

	/** The handlers the service uses: a poll of more than one record is applied at once, falling back one by one. */
	private MarketFeedConsumer.Handler snapshotHandler() {
		return projection.handler((instrument) -> {
		});
	}

	private MarketFeedConsumer.Handler tradeHandler() {
		return tradeProjection.handler((instrument) -> {
		});
	}

	private void publishTrade(TradeExecuted trade) throws Exception {
		send(trade.getSimulationId() + "|" + trade.getInstrumentId(), WireEvents.encode(trade));
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

	/** One producer batch, so the records reach the feed in one poll. */
	private void publishTogether(List<TradeExecuted> trades) throws Exception {
		try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(
				Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers(), ProducerConfig.ACKS_CONFIG, "all",
						ProducerConfig.LINGER_MS_CONFIG, 200),
				new StringSerializer(), new ByteArraySerializer())) {
			List<Future<RecordMetadata>> sent = new ArrayList<>();
			for (TradeExecuted trade : trades) {
				sent.add(producer.send(new ProducerRecord<>(topic, trade.getSimulationId() + "|" + trade.getInstrumentId(),
						WireEvents.encode(trade))));
			}
			for (Future<RecordMetadata> each : sent) {
				each.get();
			}
		}
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

	private List<Map<String, Object>> minutes(UUID instrument) {
		return jdbc.queryForList("""
				SELECT minute_start, first_sequence, last_sequence, best_bid_cents, best_ask_cents, last_price_cents
				FROM spread_minute WHERE instrument_id = ? ORDER BY minute_start""", instrument);
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
