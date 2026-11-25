package com.mia_princz.mse.marketdata.api;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Fans committed market state out to server-sent-event subscribers.
 *
 * <ul>
 * <li>A projection change only marks its instrument as changed. A ticker, every 250 ms by default, turns each changed
 * instrument into one complete frame, so a subscriber gets at most four frames a second however busy the market.</li>
 * <li>Each subscriber has a one-frame slot and its own virtual thread to send it. A newer frame replaces one not yet
 * sent, so a slow client gets fewer frames and never makes the server hold more. A client whose send has been stuck
 * longer than the slow limit is disconnected.</li>
 * <li>A subscriber that has been sent nothing for the heartbeat interval gets a heartbeat carrying the feed's state
 * and lag: a live connection is not proof that the data is current.</li>
 * <li>Frames need database reads and heartbeats do not, so each has its own thread. A slow read delays only the
 * frames it is building; every subscriber's heartbeat, and the check for stuck clients, carries on.</li>
 * </ul>
 */
final class MarketStream implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(MarketStream.class);

	record Settings(Duration tick, Duration heartbeat, Duration slowLimit, int maxSubscribers) {
	}

	/** Builds what is sent: a complete frame for an instrument, or a heartbeat. */
	interface Frames {

		Optional<Object> frame(UUID instrumentId);

		Object heartbeat();

	}

	/** More subscribers than the limit; the request is refused instead of degrading everyone. */
	static final class TooManySubscribersException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		TooManySubscribersException() {
			super("Too many market streams are open");
		}

	}

	private record Event(String name, Object data) {
	}

	private final Frames frames;

	private final Supplier<SseEmitter> emitters;

	private final Settings settings;

	private final Clock clock;

	private final Map<UUID, Set<Subscriber>> subscribers = new ConcurrentHashMap<>();

	private final Set<UUID> changed = ConcurrentHashMap.newKeySet();

	private final AtomicInteger open = new AtomicInteger();

	private final ScheduledExecutorService ticker = Executors
		.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("market-stream").daemon().factory());

	private final ScheduledExecutorService heartbeats = Executors
		.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("market-stream-heartbeat").daemon().factory());

	MarketStream(Frames frames, Supplier<SseEmitter> emitters, Settings settings, Clock clock) {
		this.frames = frames;
		this.emitters = emitters;
		this.settings = settings;
		this.clock = clock;
	}

	void start() {
		long period = settings.tick().toMillis();
		ticker.scheduleWithFixedDelay(() -> safely("tick", this::tick), period, period, TimeUnit.MILLISECONDS);
		heartbeats.scheduleWithFixedDelay(() -> safely("heartbeat", this::beat), period, period,
				TimeUnit.MILLISECONDS);
	}

	/** Opens a stream for one instrument; its first frame is the current state. */
	SseEmitter subscribe(UUID instrumentId, Object currentFrame) {
		if (open.incrementAndGet() > settings.maxSubscribers()) {
			open.decrementAndGet();
			throw new TooManySubscribersException();
		}
		SseEmitter emitter = emitters.get();
		Subscriber subscriber = new Subscriber(instrumentId, emitter);
		subscribers.computeIfAbsent(instrumentId, (id) -> ConcurrentHashMap.newKeySet()).add(subscriber);
		emitter.onCompletion(subscriber::remove);
		emitter.onTimeout(subscriber::remove);
		emitter.onError((error) -> subscriber.remove());
		subscriber.offer(new Event("market", currentFrame));
		subscriber.start();
		return emitter;
	}

	/** The instrument's current frame, or empty when there is no market for it yet. */
	Optional<Object> current(UUID instrumentId) {
		return frames.frame(instrumentId);
	}

	/** Called after a projection change commits; the next tick sends the new state. */
	void changed(UUID instrumentId) {
		changed.add(instrumentId);
	}

	int openStreams() {
		return open.get();
	}

	private void safely(String round, Runnable work) {
		try {
			work.run();
		}
		catch (RuntimeException ex) {
			log.warn("Market stream {} failed", round, ex);
		}
	}

	/**
	 * One round of frames: each changed instrument that someone watches is read once and sent. A read that fails
	 * marks only its own instrument again, so it is retried on the next tick rather than waiting for the next trade,
	 * and the round carries on: an instrument whose frame keeps failing holds up no other instrument's frames.
	 */
	void tick() {
		List<UUID> due = new ArrayList<>(changed);
		changed.removeAll(due);
		RuntimeException firstFailure = null;
		int failures = 0;
		for (UUID instrumentId : due) {
			Set<Subscriber> watching = subscribers.get(instrumentId);
			if (watching == null || watching.isEmpty()) {
				continue;
			}
			try {
				frames.frame(instrumentId).ifPresent((frame) -> watching.forEach((s) -> s.offer(new Event("market", frame))));
			}
			catch (RuntimeException ex) {
				changed.add(instrumentId);
				failures++;
				firstFailure = firstFailure != null ? firstFailure : ex;
			}
		}
		if (firstFailure != null) {
			// One line a round, however many instruments failed in it.
			log.warn("Market stream frames failed for {} of {} instruments; each is tried again on the next tick",
					failures, due.size(), firstFailure);
		}
	}

	/** One round of upkeep: stuck subscribers dropped, idle ones sent a heartbeat. No database read happens here. */
	void beat() {
		Instant now = clock.instant();
		Object heartbeat = null;
		for (Set<Subscriber> watching : subscribers.values()) {
			for (Subscriber subscriber : watching) {
				if (subscriber.isStuck(now)) {
					log.info("Disconnecting a market stream that has not taken data for {}", settings.slowLimit());
					subscriber.disconnect();
				}
				else if (subscriber.isIdle(now)) {
					heartbeat = heartbeat != null ? heartbeat : frames.heartbeat();
					subscriber.offerHeartbeat(new Event("heartbeat", heartbeat), now);
				}
			}
		}
	}

	@Override
	public void close() {
		ticker.shutdownNow();
		heartbeats.shutdownNow();
		subscribers.values().forEach((watching) -> watching.forEach(Subscriber::disconnect));
	}

	private final class Subscriber {

		private final UUID instrumentId;

		private final SseEmitter emitter;

		/** The latest event not yet sent; a newer one replaces it. */
		private final BlockingQueue<Event> slot = new ArrayBlockingQueue<>(1);

		private final AtomicBoolean removed = new AtomicBoolean();

		private final Thread sender;

		private volatile Instant lastOffered;

		private volatile Instant sendingSince;

		Subscriber(UUID instrumentId, SseEmitter emitter) {
			this.instrumentId = instrumentId;
			this.emitter = emitter;
			this.sender = Thread.ofVirtual().name("market-stream-" + instrumentId).unstarted(this::send);
			this.lastOffered = clock.instant();
		}

		void start() {
			sender.start();
		}

		void offer(Event event) {
			synchronized (slot) {
				slot.clear();
				slot.offer(event);
				lastOffered = clock.instant();
			}
		}

		/**
		 * Frames and heartbeats come from different threads, so a heartbeat is only put into an empty slot: it must
		 * never replace a frame that has not been sent yet.
		 */
		void offerHeartbeat(Event heartbeat, Instant now) {
			synchronized (slot) {
				if (isIdle(now) && slot.isEmpty()) {
					slot.offer(heartbeat);
					lastOffered = clock.instant();
				}
			}
		}

		boolean isIdle(Instant now) {
			return !now.isBefore(lastOffered.plus(settings.heartbeat()));
		}

		boolean isStuck(Instant now) {
			Instant since = sendingSince;
			return since != null && now.isAfter(since.plus(settings.slowLimit()));
		}

		private void send() {
			try {
				while (!removed.get()) {
					Event event = slot.take();
					sendingSince = clock.instant();
					emitter.send(SseEmitter.event().name(event.name()).data(event.data(), MediaType.APPLICATION_JSON));
					sendingSince = null;
				}
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			catch (IOException | IllegalStateException ex) {
				// The client went away, or the emitter was already completed.
				remove();
			}
		}

		void disconnect() {
			remove();
			sender.interrupt();
			// complete() waits for the emitter's write lock, which a send stuck in a write holds; on its own thread
			// it cannot hold up the heartbeats of everyone else.
			Thread.ofVirtual().name("market-stream-close-" + instrumentId).start(emitter::complete);
		}

		void remove() {
			if (removed.compareAndSet(false, true)) {
				Set<Subscriber> watching = subscribers.get(instrumentId);
				if (watching != null) {
					watching.remove(this);
				}
				open.decrementAndGet();
				sender.interrupt();
			}
		}

	}

}
