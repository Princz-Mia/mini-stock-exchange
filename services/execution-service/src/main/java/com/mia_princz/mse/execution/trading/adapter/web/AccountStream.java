package com.mia_princz.mse.execution.trading.adapter.web;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.mia_princz.mse.execution.trading.application.AccountChanges;

/**
 * Private server-sent events: each trader's streams hear that their account changed, and nothing more. A frame
 * carries only the account's version, the command sequence of its latest change; the client then reads its orders and
 * portfolio from the REST API, which is the authority. Nothing private is in a frame, so a stream can't leak one
 * account's data to another even if it were misrouted.
 *
 * <ul>
 * <li>{@link #changed} runs on the writer thread after commit: it only records the version and fills each stream's
 * one-frame slot. Each stream has its own virtual thread to send, so a slow client never holds the writer back, and
 * a newer frame replaces one not yet sent.</li>
 * <li>A ticker, once per heartbeat interval, closes every stream whose session has ended (logged out, or expired),
 * disconnects one whose send has been stuck past the slow limit, and sends a heartbeat to the rest that were sent
 * nothing in that time. Logging out also closes the session's streams at once.</li>
 * </ul>
 */
final class AccountStream implements AccountChanges, AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(AccountStream.class);

	record Settings(Duration heartbeat, Duration slowLimit, int maxStreams, int maxPerAccount) {

		Settings {
			if (maxStreams < 1 || maxPerAccount < 1) {
				throw new IllegalArgumentException("Stream limits must allow at least one: " + maxStreams + ", "
						+ maxPerAccount);
			}
		}

	}

	record AccountFrame(String accountVersion) {
	}

	record Heartbeat(Instant at) {
	}

	/** Too many streams in all; refused instead of degrading everyone. */
	static final class TooManyStreamsException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		TooManyStreamsException(String message) {
			super(message);
		}

	}

	private record Event(String name, Object data) {
	}

	private final Supplier<SseEmitter> emitters;

	private final Predicate<String> sessionActive;

	private final Settings settings;

	private final Clock clock;

	private final Map<UUID, Set<Stream>> streams = new ConcurrentHashMap<>();

	/** Each account's latest change since this process started; a new stream starts from it. */
	private final Map<UUID, Long> versions = new ConcurrentHashMap<>();

	private final AtomicInteger open = new AtomicInteger();

	/** Orders streams by when they opened, to find an account's oldest. */
	private final AtomicLong openings = new AtomicLong();

	private final ScheduledExecutorService ticker = Executors
		.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("account-stream").daemon().factory());

	AccountStream(Supplier<SseEmitter> emitters, Predicate<String> sessionActive, Settings settings, Clock clock) {
		this.emitters = emitters;
		this.sessionActive = sessionActive;
		this.settings = settings;
		this.clock = clock;
	}

	void start() {
		long period = settings.heartbeat().toMillis();
		ticker.scheduleWithFixedDelay(this::tickSafely, period, period, TimeUnit.MILLISECONDS);
	}

	/** Opens a stream for the account, tied to the session that opened it; its first frame is the current version. */
	SseEmitter subscribe(UUID accountId, String sessionId) {
		Set<Stream> own = streams.computeIfAbsent(accountId, (id) -> ConcurrentHashMap.newKeySet());
		// At the account's limit the oldest stream makes way. It is most likely a tab already closed: a closed
		// connection only shows when a write to it fails, up to two heartbeats later. A live one reconnects by itself.
		while (own.size() >= settings.maxPerAccount()) {
			own.stream().min(Comparator.comparingLong((stream) -> stream.opened)).ifPresent(Stream::disconnect);
		}
		if (open.incrementAndGet() > settings.maxStreams()) {
			open.decrementAndGet();
			throw new TooManyStreamsException("Too many account streams are open");
		}
		SseEmitter emitter = emitters.get();
		Stream stream = new Stream(accountId, sessionId, emitter);
		own.add(stream);
		emitter.onCompletion(stream::remove);
		emitter.onTimeout(stream::remove);
		emitter.onError((error) -> stream.remove());
		stream.offer(frame(accountId));
		stream.start();
		return emitter;
	}

	@Override
	public void changed(Set<UUID> accountIds, long commandSequence) {
		for (UUID accountId : accountIds) {
			versions.merge(accountId, commandSequence, Math::max);
			Set<Stream> own = streams.get(accountId);
			if (own != null && !own.isEmpty()) {
				Event event = frame(accountId);
				own.forEach((stream) -> stream.offer(event));
			}
		}
	}

	/** Closes every stream the session opened; called when it logs out. */
	void closeSession(String sessionId) {
		streams.values().forEach((own) -> own.stream()
			.filter((stream) -> stream.sessionId.equals(sessionId))
			.toList()
			.forEach(Stream::disconnect));
	}

	int openStreams() {
		return open.get();
	}

	private Event frame(UUID accountId) {
		return new Event("account", new AccountFrame(Long.toString(versions.getOrDefault(accountId, 0L))));
	}

	private void tickSafely() {
		try {
			tick();
		}
		catch (RuntimeException ex) {
			log.warn("Account stream tick failed", ex);
		}
	}

	/** Ended sessions closed, stuck streams dropped, idle ones sent a heartbeat. */
	void tick() {
		Instant now = clock.instant();
		Map<String, Boolean> active = new ConcurrentHashMap<>();
		for (Set<Stream> own : streams.values()) {
			for (Stream stream : own) {
				if (!active.computeIfAbsent(stream.sessionId, sessionActive::test)) {
					stream.disconnect();
				}
				else if (stream.isStuck(now)) {
					log.info("Disconnecting an account stream that has not taken data for {}", settings.slowLimit());
					stream.disconnect();
				}
				else if (stream.isIdle(now)) {
					stream.offer(new Event("heartbeat", new Heartbeat(now)));
				}
			}
		}
	}

	@Override
	public void close() {
		ticker.shutdownNow();
		streams.values().forEach((own) -> own.forEach(Stream::disconnect));
	}

	private final class Stream {

		private final UUID accountId;

		private final String sessionId;

		private final SseEmitter emitter;

		/** The latest event not yet sent; a newer one replaces it. */
		private final BlockingQueue<Event> slot = new ArrayBlockingQueue<>(1);

		private final AtomicBoolean removed = new AtomicBoolean();

		private final long opened = openings.incrementAndGet();

		private final Thread sender;

		private volatile Instant lastOffered;

		private volatile Instant sendingSince;

		Stream(UUID accountId, String sessionId, SseEmitter emitter) {
			this.accountId = accountId;
			this.sessionId = sessionId;
			this.emitter = emitter;
			this.sender = Thread.ofVirtual().name("account-stream-" + accountId).unstarted(this::send);
			this.lastOffered = clock.instant();
		}

		void start() {
			sender.start();
		}

		void offer(Event event) {
			synchronized (slot) {
				slot.clear();
				slot.offer(event);
			}
			lastOffered = clock.instant();
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
			emitter.complete();
		}

		void remove() {
			if (removed.compareAndSet(false, true)) {
				Set<Stream> own = streams.get(accountId);
				if (own != null) {
					own.remove(this);
				}
				open.decrementAndGet();
				sender.interrupt();
			}
		}

	}

}
