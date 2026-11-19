package com.mia_princz.mse.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mia_princz.mse.contracts.events.AggressorSide;
import com.mia_princz.mse.contracts.events.BookLevel;
import com.mia_princz.mse.contracts.events.MarketRules;
import com.mia_princz.mse.contracts.events.MarketSnapshot;
import com.mia_princz.mse.contracts.events.PublicTrade;
import com.mia_princz.mse.contracts.events.TradeExecuted;
import com.mia_princz.mse.contracts.events.VenueState;
import com.mia_princz.mse.contracts.events.WireEvents;
import com.mia_princz.mse.marketdata.projection.Checkpoints;
import com.mia_princz.mse.marketdata.projection.MarketFeedConsumer;
import com.mia_princz.mse.marketdata.projection.MarketFeedConsumer.State;
import com.mia_princz.mse.marketdata.projection.SnapshotProjection;
import com.mia_princz.mse.marketdata.projection.TradeProjection;

/**
 * How fast a feed catches up: the same seeded snapshots and trades published to a topic, then a feed timed from its
 * start until it is live, once applying each poll at once and once one record at a time (the handler wrapped so it
 * cannot batch). Not part of the test suite; run it on its own:
 *
 * <pre>
 * mvnw -pl services/market-data-service verify -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=FeedCatchUpBench
 * </pre>
 *
 * The report goes to {@code target/feed-catch-up-<time>.txt}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "mse.test.database=feed-bench", "mse.market-feed.enabled=false",
		"mse.trade-feed.enabled=false", "mse.candle-feed.enabled=false" })
class FeedCatchUpBench {

	private static final int EVENTS = 6_000;

	private static final int INSTRUMENTS = 5;

	@Autowired
	KafkaConnectionDetails kafka;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	SnapshotProjection snapshots;

	@Autowired
	TradeProjection trades;

	@Autowired
	Checkpoints checkpoints;

	@Test
	void catchUp() throws Exception {
		List<String> lines = new ArrayList<>();
		for (String mode : List.of("one by one", "at once", "one by one", "at once")) {
			boolean batched = "at once".equals(mode);
			Map<UUID, UUID> instruments = new HashMap<>();
			Function<Integer, UUID> instrument = (index) -> instruments.computeIfAbsent(new UUID(0, index),
					(ignored) -> UUID.randomUUID());
			MarketFeedConsumer.BatchHandler snapshotHandler = snapshots.handler((id) -> {
			});
			MarketFeedConsumer.BatchHandler tradeHandler = trades.handler((id) -> {
			});
			double snapshotRate = time(snapshotEvents(new Random(11L), instrument),
					batched ? snapshotHandler : snapshotHandler::apply);
			double tradeRate = time(tradeEvents(new Random(12L), instrument), batched ? tradeHandler : tradeHandler::apply);
			lines.add("%-10s snapshots %,8.0f a second, trades %,8.0f a second".formatted(mode, snapshotRate,
					tradeRate));
		}
		String report = "Feed catch-up: " + EVENTS + " snapshots and " + EVENTS + " trades, " + INSTRUMENTS
				+ " instruments, three partitions\n  " + String.join("\n  ", lines) + "\n";
		System.out.println(report);
		Files.writeString(Path.of("target", "feed-catch-up-" + Instant.now().toString().replace(':', '-') + ".txt"),
				report);
	}

	/** Records a second from the feed's start until it is live, on a topic of its own holding {@code events}. */
	private double time(List<byte[][]> events, MarketFeedConsumer.Handler handler) throws Exception {
		String topic = "mse.bench." + UUID.randomUUID() + ".v1";
		try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, brokers()))) {
			admin.createTopics(List.of(new NewTopic(topic, 3, (short) 1))).all().get();
		}
		try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(
				Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers(), ProducerConfig.ACKS_CONFIG, "all",
						ProducerConfig.LINGER_MS_CONFIG, 5),
				new StringSerializer(), new ByteArraySerializer())) {
			for (byte[][] event : events) {
				producer.send(new ProducerRecord<>(topic, new String(event[0]), event[1]));
			}
			producer.flush();
		}
		Map<String, Object> config = new HashMap<>();
		config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers());
		config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
		config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		MarketFeedConsumer feed = new MarketFeedConsumer(
				() -> new KafkaConsumer<>(config, new StringDeserializer(), new ByteArrayDeserializer()),
				new TransactionTemplate(transactionManager), handler, checkpoints,
				new MarketFeedConsumer.Settings(topic, Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofSeconds(5),
						Duration.ofMillis(200)),
				Clock.systemUTC());
		long started = System.nanoTime();
		feed.start();
		try {
			while (feed.state() != State.LIVE) {
				assertThat(feed.state()).isNotEqualTo(State.PAUSED);
				Thread.sleep(5);
			}
			return events.size() / ((System.nanoTime() - started) / 1e9);
		}
		finally {
			feed.close();
		}
	}

	/** Rising snapshots per instrument, each with a small book and tape, keyed as Execution keys them. */
	private static List<byte[][]> snapshotEvents(Random random, Function<Integer, UUID> instrument) throws Exception {
		long[] sequence = new long[INSTRUMENTS];
		List<byte[][]> events = new ArrayList<>();
		for (int i = 0; i < EVENTS; i++) {
			int index = random.nextInt(INSTRUMENTS);
			long at = ++sequence[index];
			List<BookLevel> bids = new ArrayList<>();
			for (int level = 0; level < 5; level++) {
				bids.add(new BookLevel(10_000L - level, 1L + random.nextInt(50), 1 + random.nextInt(3)));
			}
			PublicTrade last = new PublicTrade(UUID.randomUUID(), at, 9_000L + random.nextInt(2_000), 1L, AggressorSide.BUY,
					Instant.now());
			MarketSnapshot snapshot = MarketSnapshot.newBuilder()
				.setEventId(UUID.randomUUID())
				.setSimulationId(Snapshots.SIMULATION)
				.setInstrumentId(instrument.apply(index))
				.setSymbol("NOVA")
				.setVenueState(VenueState.OPEN)
				.setRules(new MarketRules(1, 10_000L, 1L, 1L, 1L, 10_000L, 100_000_000L, 20, 1_000))
				.setBids(bids)
				.setAsks(List.of(new BookLevel(10_100L, 3L, 1)))
				.setRecentTrades(List.of(last))
				.setLastTrade(last)
				.setLastTradeSequence(at)
				.setSourceCommandSequence(at)
				.setGeneratedAt(Instant.now())
				.build();
			events.add(new byte[][] { (snapshot.getSimulationId() + "|" + snapshot.getInstrumentId()).getBytes(),
					WireEvents.encode(snapshot) });
		}
		return events;
	}

	private static List<byte[][]> tradeEvents(Random random, Function<Integer, UUID> instrument) throws Exception {
		long[] sequence = new long[INSTRUMENTS];
		List<byte[][]> events = new ArrayList<>();
		for (int i = 0; i < EVENTS; i++) {
			int index = random.nextInt(INSTRUMENTS);
			long at = ++sequence[index];
			TradeExecuted trade = TradeExecuted.newBuilder()
				.setEventId(UUID.randomUUID())
				.setSimulationId(Snapshots.SIMULATION)
				.setInstrumentId(instrument.apply(index))
				.setSymbol("NOVA")
				.setTradeId(UUID.randomUUID())
				.setTradeSequence(at)
				.setCommandSequence(at)
				.setExecutedAt(Instant.now())
				.setPriceCents(9_000L + random.nextInt(2_000))
				.setQuantity(1 + random.nextInt(20))
				.build();
			events.add(new byte[][] { (trade.getSimulationId() + "|" + trade.getInstrumentId()).getBytes(),
					WireEvents.encode(trade) });
		}
		return events;
	}

	private String brokers() {
		return String.join(",", kafka.getConsumer().getBootstrapServers());
	}

}
