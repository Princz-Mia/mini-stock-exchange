package com.mia_princz.mse.marketdata.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** The hub on its own: ticks are driven by hand, time by a clock the test moves. */
class MarketStreamTest {

	private static final MarketStream.Settings SETTINGS = new MarketStream.Settings(Duration.ofMillis(250),
			Duration.ofSeconds(10), Duration.ofSeconds(15), 3);

	private final MutableClock clock = new MutableClock();

	private final Map<UUID, AtomicInteger> versions = new ConcurrentHashMap<>();

	private final AtomicInteger frameReads = new AtomicInteger();

	private final List<RecordingEmitter> emitters = new CopyOnWriteArrayList<>();

	private volatile CountDownLatch nextSendBlocks;

	private final MarketStream stream = new MarketStream(new MarketStream.Frames() {

		@Override
		public Optional<Object> frame(UUID instrumentId) {
			frameReads.incrementAndGet();
			return Optional.of("frame-" + versions.computeIfAbsent(instrumentId, (id) -> new AtomicInteger()).get());
		}

		@Override
		public Object heartbeat() {
			return "beat";
		}

	}, () -> {
		RecordingEmitter emitter = new RecordingEmitter(nextSendBlocks);
		nextSendBlocks = null;
		emitters.add(emitter);
		return emitter;
	}, SETTINGS, clock);

	@AfterEach
	void close() {
		stream.close();
	}

	@Test
	void firstFrameIsTheCurrentState() throws Exception {
		RecordingEmitter emitter = subscribe(UUID.randomUUID(), "frame-0");

		waitUntil(() -> emitter.events().equals(List.of("market:frame-0")));
	}

	@Test
	void manyChangesBetweenTicksBecomeOneFrame() throws Exception {
		UUID instrument = UUID.randomUUID();
		RecordingEmitter emitter = subscribe(instrument, "frame-0");
		waitUntil(() -> emitter.events().size() == 1);
		int readsBefore = frameReads.get();

		for (int i = 1; i <= 10; i++) {
			versions.computeIfAbsent(instrument, (id) -> new AtomicInteger()).set(i);
			stream.changed(instrument);
		}
		stream.tick();

		waitUntil(() -> emitter.events().size() == 2);
		assertThat(emitter.events()).containsExactly("market:frame-0", "market:frame-10");
		assertThat(frameReads.get() - readsBefore).as("one read for ten changes").isEqualTo(1);
		stream.tick();
		assertThat(frameReads.get() - readsBefore).as("nothing new, nothing read").isEqualTo(1);
	}

	@Test
	void onlyWatchersOfTheInstrumentGetItsFrames() throws Exception {
		UUID watched = UUID.randomUUID();
		RecordingEmitter watcher = subscribe(watched, "frame-0");
		RecordingEmitter other = subscribe(UUID.randomUUID(), "frame-0");
		waitUntil(() -> watcher.events().size() == 1 && other.events().size() == 1);

		stream.changed(watched);
		stream.tick();

		waitUntil(() -> watcher.events().size() == 2);
		assertThat(other.events()).hasSize(1);
	}

	@Test
	void idleStreamsGetHeartbeatsAndBusyOnesDoNot() throws Exception {
		UUID instrument = UUID.randomUUID();
		RecordingEmitter emitter = subscribe(instrument, "frame-0");
		waitUntil(() -> emitter.events().size() == 1);

		clock.advance(Duration.ofSeconds(9));
		stream.tick();
		assertThat(emitter.events()).as("not idle long enough").hasSize(1);

		clock.advance(Duration.ofSeconds(1));
		stream.tick();
		waitUntil(() -> emitter.events().size() == 2);
		assertThat(emitter.events().getLast()).isEqualTo("heartbeat:beat");

		clock.advance(Duration.ofSeconds(9));
		stream.changed(instrument);
		stream.tick();
		clock.advance(Duration.ofSeconds(2));
		stream.tick();
		waitUntil(() -> emitter.events().size() == 3);
		assertThat(emitter.events().getLast()).as("a frame resets the idle time").startsWith("market:");
	}

	@Test
	void slowClientGetsOnlyTheLatestFrame() throws Exception {
		UUID instrument = UUID.randomUUID();
		CountDownLatch release = new CountDownLatch(1);
		nextSendBlocks = release;
		RecordingEmitter slow = subscribe(instrument, "frame-0");
		waitUntil(() -> slow.sending);

		for (int i = 1; i <= 5; i++) {
			versions.computeIfAbsent(instrument, (id) -> new AtomicInteger()).set(i);
			stream.changed(instrument);
			stream.tick();
		}
		release.countDown();

		waitUntil(() -> slow.events().size() == 2);
		assertThat(slow.events()).containsExactly("market:frame-0", "market:frame-5");
	}

	@Test
	void stuckClientIsDisconnectedAndOthersCarryOn() throws Exception {
		UUID instrument = UUID.randomUUID();
		nextSendBlocks = new CountDownLatch(1);
		RecordingEmitter stuck = subscribe(instrument, "frame-0");
		RecordingEmitter healthy = subscribe(instrument, "frame-0");
		waitUntil(() -> stuck.sending && healthy.events().size() == 1);

		clock.advance(Duration.ofSeconds(15));
		stream.tick();
		assertThat(stuck.completed).as("exactly at the limit is not yet stuck").isFalse();

		clock.advance(Duration.ofMillis(1));
		stream.tick();

		assertThat(stuck.completed).isTrue();
		assertThat(stream.openStreams()).isEqualTo(1);
		stream.changed(instrument);
		stream.tick();
		waitUntil(() -> healthy.events().size() >= 2);
	}

	@Test
	void streamsBeyondTheLimitAreRefused() {
		for (int i = 0; i < 3; i++) {
			stream.subscribe(UUID.randomUUID(), "frame-0");
		}

		assertThatThrownBy(() -> stream.subscribe(UUID.randomUUID(), "frame-0"))
			.isInstanceOf(MarketStream.TooManySubscribersException.class);
		assertThat(stream.openStreams()).isEqualTo(3);
	}

	private RecordingEmitter subscribe(UUID instrument, Object current) {
		stream.subscribe(instrument, current);
		return emitters.getLast();
	}

	private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while (!condition.getAsBoolean()) {
			assertThat(System.nanoTime()).as("condition not met in time").isLessThan(deadline);
			Thread.sleep(5);
		}
	}

	/** Records each event as {@code name:data}; the first send can be made to block, like a client that stopped reading. */
	private static final class RecordingEmitter extends SseEmitter {

		private final List<String> events = new CopyOnWriteArrayList<>();

		private final CountDownLatch blockFirst;

		private volatile boolean sending;

		private volatile boolean completed;

		RecordingEmitter(CountDownLatch blockFirst) {
			this.blockFirst = blockFirst;
		}

		@Override
		public void send(SseEventBuilder builder) throws IOException {
			sending = true;
			if (blockFirst != null && events.isEmpty()) {
				try {
					blockFirst.await();
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new IOException("interrupted", ex);
				}
			}
			String name = null;
			Object data = null;
			// Spring sends the framing as plain text, such as "event:market\ndata:", and the payload as JSON.
			for (ResponseBodyEmitter.DataWithMediaType part : builder.build()) {
				if (MediaType.APPLICATION_JSON.equals(part.getMediaType())) {
					data = part.getData();
				}
				else if (part.getData() instanceof String text && text.startsWith("event:")) {
					name = text.substring("event:".length(), text.indexOf('\n'));
				}
			}
			events.add(name + ":" + data);
			sending = false;
		}

		@Override
		public void complete() {
			completed = true;
		}

		List<String> events() {
			return List.copyOf(events);
		}

	}

	private static final class MutableClock extends Clock {

		private volatile Instant now = Instant.parse("2026-11-04T12:00:00Z");

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

	}

}
