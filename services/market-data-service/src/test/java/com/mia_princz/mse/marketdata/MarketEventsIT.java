package com.mia_princz.mse.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.mia_princz.mse.contracts.events.MarketSnapshot;
import com.mia_princz.mse.contracts.events.WireEvents;
import com.mia_princz.mse.marketdata.projection.SnapshotProjected;
import com.mia_princz.mse.marketdata.projection.SnapshotProjection;

/**
 * The live stream end to end: a snapshot on Kafka reaches an SSE client through the application's own feed. Short
 * ticks and heartbeats keep the test quick.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=market-events", "mse.trade-feed.enabled=false",
				"mse.market-events.tick=50ms", "mse.market-events.heartbeat=400ms" })
class MarketEventsIT {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private record Event(String name, JsonNode data) {
	}

	@LocalServerPort
	int port;

	@Autowired
	KafkaConnectionDetails kafka;

	@Autowired
	SnapshotProjection projection;

	@Autowired
	ApplicationEventPublisher events;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Test
	void snapshotOnKafkaReachesTheStream() throws Exception {
		UUID instrument = UUID.randomUUID();
		publish(Snapshots.snapshot(instrument, 1, 2));
		waitForMarket(instrument);

		try (Stream<String> lines = open(instrument)) {
			BlockingQueue<Event> received = read(lines);
			Event first = next(received, "market");
			assertThat(first.data().get("sourceCommandSequence").asString()).isEqualTo("1");
			assertThat(first.data().get("bids").size()).isEqualTo(2);
			assertThat(first.data().get("bids").get(0).get("price").asString()).isEqualTo("100.00");
			assertThat(first.data().get("recentTrades").get(0).get("aggressorSide").asString()).isEqualTo("BUY");
			assertThat(first.data().get("bootId").asString()).isNotBlank();

			publish(Snapshots.snapshot(instrument, 2, 5));

			Event second = marketAt(received, 1, 2);
			assertThat(second.data().get("bids").size()).as("the whole new book").isEqualTo(5);
			assertThat(second.data().get("bootId").asString()).isEqualTo(first.data().get("bootId").asString());
		}
	}

	@Test
	void quietStreamCarriesHeartbeatsWithTheFeedState() throws Exception {
		UUID instrument = UUID.randomUUID();
		commit(Snapshots.snapshot(instrument, 1, 1));

		try (Stream<String> lines = open(instrument)) {
			BlockingQueue<Event> received = read(lines);
			next(received, "market");
			Event heartbeat = next(received, "heartbeat");
			assertThat(heartbeat.data().get("feed").get("state").asString()).isNotBlank();
			assertThat(heartbeat.data().get("feed").has("lag")).isTrue();
		}
	}

	/** A projection change that rolls back is never streamed; the next one that commits is. */
	@Test
	void rolledBackChangeIsNeverStreamed() throws Exception {
		UUID instrument = UUID.randomUUID();
		commit(Snapshots.snapshot(instrument, 1, 1));

		try (Stream<String> lines = open(instrument)) {
			BlockingQueue<Event> received = read(lines);
			next(received, "market");

			new TransactionTemplate(transactionManager).executeWithoutResult((status) -> {
				projection.apply(Snapshots.snapshot(instrument, 2, 9));
				events.publishEvent(new SnapshotProjected(instrument));
				status.setRollbackOnly();
			});
			commit(Snapshots.snapshot(instrument, 3, 3));

			Event third = marketAt(received, 1, 3);
			assertThat(third.data().get("bids").size()).as("revision 3's book, not 2's").isEqualTo(3);
		}
	}

	/**
	 * A commit that takes longer than a tick still reaches the stream. Marking the change before commit would let a
	 * tick read the old state and clear the mark, and the committed state would never be sent.
	 */
	@Test
	void slowCommitIsNotLost() throws Exception {
		UUID instrument = UUID.randomUUID();
		commit(Snapshots.snapshot(instrument, 1, 1));

		try (Stream<String> lines = open(instrument)) {
			BlockingQueue<Event> received = read(lines);
			next(received, "market");

			new TransactionTemplate(transactionManager).executeWithoutResult((status) -> {
				projection.apply(Snapshots.snapshot(instrument, 2, 4));
				events.publishEvent(new SnapshotProjected(instrument));
				try {
					Thread.sleep(300); // six ticks while the change is not yet committed
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			});

			assertThat(marketAt(received, 1, 2).data().get("bids").size()).isEqualTo(4);
		}
	}

	@Test
	void requestsWithoutAMarketAreRefused() throws Exception {
		assertThat(status("/api/v1/market-events?instrumentId=" + UUID.randomUUID())).isEqualTo(404);
		assertThat(status("/api/v1/market-events")).isEqualTo(400);
		assertThat(status("/api/v1/market-events?instrumentId=nope")).isEqualTo(400);
	}

	private void commit(MarketSnapshot snapshot) {
		new TransactionTemplate(transactionManager).executeWithoutResult((status) -> {
			projection.apply(snapshot);
			events.publishEvent(new SnapshotProjected(snapshot.getInstrumentId()));
		});
	}

	private void publish(MarketSnapshot snapshot) throws Exception {
		try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(
				Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", kafka.getBootstrapServers())),
				new StringSerializer(), new ByteArraySerializer())) {
			String key = snapshot.getSimulationId() + "|" + snapshot.getInstrumentId();
			producer.send(new ProducerRecord<>("mse.market.v1", key, WireEvents.encode(snapshot))).get();
		}
	}

	private void waitForMarket(UUID instrument) throws Exception {
		long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
		while (status("/api/v1/instruments/" + instrument + "/book", "application/json") != 200) {
			assertThat(System.nanoTime()).as("snapshot not projected in time").isLessThan(deadline);
			Thread.sleep(100);
		}
	}

	private Stream<String> open(UUID instrument) throws Exception {
		HttpResponse<Stream<String>> response = HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/market-events?instrumentId="
					+ instrument))
				.header("Accept", "text/event-stream")
				.GET()
				.build(), HttpResponse.BodyHandlers.ofLines());
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
				(type) -> assertThat(type).startsWith("text/event-stream"));
		return response.body();
	}

	/** Parses {@code event:} and {@code data:} lines on a background thread into whole events. */
	private static BlockingQueue<Event> read(Stream<String> lines) {
		BlockingQueue<Event> received = new LinkedBlockingQueue<>();
		Thread.ofVirtual().start(() -> {
			String[] name = { null };
			StringBuilder data = new StringBuilder();
			try {
				lines.forEach((line) -> {
					if (line.startsWith("event:")) {
						name[0] = line.substring("event:".length()).trim();
					}
					else if (line.startsWith("data:")) {
						data.append(line.substring("data:".length()));
					}
					else if (line.isEmpty() && name[0] != null) {
						received.add(new Event(name[0], JSON.readTree(data.toString())));
						name[0] = null;
						data.setLength(0);
					}
				});
			}
			catch (RuntimeException ex) {
				// The test closed the stream.
			}
		});
		return received;
	}

	/**
	 * The frame at {@code revision}, after the client last saw {@code seen}. Repeats of {@code seen} may come first: a
	 * change committed just before subscribing is sent again on the next tick, replacing state with the same state.
	 * Any other revision fails the test.
	 */
	private static Event marketAt(BlockingQueue<Event> received, long seen, long revision) throws InterruptedException {
		while (true) {
			Event event = next(received, "market");
			long current = Long.parseLong(event.data().get("sourceCommandSequence").asString());
			if (current == revision) {
				return event;
			}
			assertThat(current).as("only repeats of revision " + seen + " before " + revision).isEqualTo(seen);
		}
	}

	/** The next event with this name, skipping others (heartbeats between frames, say). */
	private static Event next(BlockingQueue<Event> received, String name) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
		while (System.nanoTime() < deadline) {
			Event event = received.poll(100, TimeUnit.MILLISECONDS);
			if (event != null && event.name().equals(name)) {
				return event;
			}
		}
		throw new AssertionError("no " + name + " event in time");
	}

	/** As a browser's EventSource asks: it always sends this Accept header. */
	private int status(String path) throws Exception {
		return status(path, "text/event-stream");
	}

	private int status(String path, String accept) throws Exception {
		try (HttpClient client = HttpClient.newHttpClient()) {
			return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.header("Accept", accept)
				.GET()
				.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
		}
	}

}
