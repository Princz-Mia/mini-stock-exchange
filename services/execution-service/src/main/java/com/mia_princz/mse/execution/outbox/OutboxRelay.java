package com.mia_princz.mse.execution.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Publishes committed outbox events to Kafka in id order, one batch at a time: send the batch, wait for each
 * acknowledgement in order, then mark the acknowledged prefix as published. It never marks an event that was not
 * acknowledged and never moves past one that failed; after a failure it backs off and starts again from the oldest
 * pending event. A crash between acknowledgement and marking sends that event again with the same id and bytes, so
 * consumers deduplicate by event id.
 *
 * <p>
 * Only the instance that owns the writer publishes. The relay is outside readiness: with Kafka down, trading goes
 * on, events wait in the outbox, and the backlog shows in health details and metrics.
 */
public final class OutboxRelay implements HealthIndicator, AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

	private static final Duration BACKLOG_REFRESH = Duration.ofSeconds(1);

	public record Settings(int batchSize, Duration pollInterval, Duration ackTimeout, Duration maxBackoff) {
	}

	private final Outbox outbox;

	private final Supplier<Producer<String, byte[]>> producers;

	private final BooleanSupplier owner;

	private final Settings settings;

	private final Clock clock;

	private final Thread thread;

	private final AtomicLong published = new AtomicLong();

	private final AtomicLong failures = new AtomicLong();

	private Producer<String, byte[]> producer;

	private volatile boolean running;

	private volatile Outbox.Backlog backlog = Outbox.Backlog.EMPTY;

	private volatile Instant backlogCheckedAt = Instant.EPOCH;

	private volatile long lastPublishedId;

	private volatile String lastError;

	public OutboxRelay(Outbox outbox, Supplier<Producer<String, byte[]>> producers, BooleanSupplier owner,
			Settings settings, Clock clock) {
		this.outbox = outbox;
		this.producers = producers;
		this.owner = owner;
		this.settings = settings;
		this.clock = clock;
		this.thread = Thread.ofPlatform().name("outbox-relay").unstarted(this::loop);
	}

	public void start() {
		running = true;
		thread.start();
	}

	/**
	 * One pass over the oldest pending events. Returns how many were published; throws after marking the
	 * acknowledged prefix if any event in the batch failed. The relay thread calls this in a loop.
	 */
	public int relayOnce() throws InterruptedException {
		if (!owner.getAsBoolean()) {
			return 0;
		}
		List<Outbox.PendingEvent> batch = outbox.pending(settings.batchSize());
		if (batch.isEmpty()) {
			return 0;
		}
		List<Future<RecordMetadata>> acks = new ArrayList<>(batch.size());
		RuntimeException sendFailure = null;
		try {
			Producer<String, byte[]> current = producer();
			for (Outbox.PendingEvent event : batch) {
				acks.add(current.send(new ProducerRecord<>(event.topic(), event.key(), event.payload())));
			}
		}
		catch (RuntimeException ex) {
			sendFailure = ex;
		}
		List<Long> acknowledged = new ArrayList<>(acks.size());
		Exception failure = sendFailure;
		long deadline = System.nanoTime() + settings.ackTimeout().toNanos();
		for (int i = 0; i < acks.size() && failure == null; i++) {
			try {
				acks.get(i).get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
				acknowledged.add(batch.get(i).id());
			}
			catch (InterruptedException ex) {
				markPublished(acknowledged);
				throw ex;
			}
			catch (Exception ex) {
				failure = ex;
			}
		}
		markPublished(acknowledged);
		if (failure != null) {
			resetProducer();
			Outbox.PendingEvent stuck = batch.get(acknowledged.size());
			throw new OutboxPublishException(stuck, failure);
		}
		return acknowledged.size();
	}

	private void markPublished(List<Long> ids) {
		if (ids.isEmpty()) {
			return;
		}
		outbox.markPublished(ids);
		published.addAndGet(ids.size());
		lastPublishedId = ids.getLast();
	}

	private void loop() {
		Duration backoff = Duration.ZERO;
		while (running) {
			try {
				int count = relayOnce();
				lastError = null;
				backoff = Duration.ZERO;
				refreshBacklog(count == 0);
				if (count == 0) {
					Thread.sleep(settings.pollInterval());
				}
			}
			catch (InterruptedException ex) {
				if (!running) {
					return;
				}
			}
			catch (RuntimeException ex) {
				failures.incrementAndGet();
				lastError = ex.getMessage();
				backoff = backoff.isZero() ? settings.pollInterval()
						: min(backoff.multipliedBy(2), settings.maxBackoff());
				log.warn("Outbox relay paused for {} ms: {}", backoff.toMillis(), ex.getMessage());
				log.debug("Outbox relay failure", ex);
				refreshBacklog(true);
				sleepQuietly(backoff);
			}
		}
	}

	private void refreshBacklog(boolean force) {
		Instant now = clock.instant();
		if (force || now.isAfter(backlogCheckedAt.plus(BACKLOG_REFRESH))) {
			try {
				backlog = outbox.backlog();
				backlogCheckedAt = now;
			}
			catch (RuntimeException ex) {
				log.debug("Could not read the outbox backlog", ex);
			}
		}
	}

	private Producer<String, byte[]> producer() {
		if (producer == null) {
			producer = producers.get();
		}
		return producer;
	}

	/** After any failure: an idempotent producer may be unusable, and a fresh one starts a clean sequence. */
	private void resetProducer() {
		if (producer != null) {
			try {
				producer.close(Duration.ZERO);
			}
			catch (RuntimeException ex) {
				log.debug("Closing a failed producer", ex);
			}
			producer = null;
		}
	}

	private void sleepQuietly(Duration duration) {
		try {
			Thread.sleep(duration);
		}
		catch (InterruptedException ex) {
			// close() interrupts to stop; the loop condition decides.
		}
	}

	private static Duration min(Duration a, Duration b) {
		return a.compareTo(b) <= 0 ? a : b;
	}

	public long publishedCount() {
		return published.get();
	}

	public long failureCount() {
		return failures.get();
	}

	public long pendingEvents() {
		return backlog.events();
	}

	/** Age of the oldest unpublished event, or zero when nothing is waiting. */
	public Duration oldestPendingAge() {
		Instant oldest = backlog.oldestCreatedAt();
		return oldest == null ? Duration.ZERO : Duration.between(oldest, clock.instant());
	}

	@Override
	public Health health() {
		Health.Builder health = (thread.isAlive() || !running) ? Health.up() : Health.down();
		health.withDetail("pendingEvents", pendingEvents())
			.withDetail("oldestPendingSeconds", oldestPendingAge().toSeconds())
			.withDetail("lastPublishedId", lastPublishedId);
		String error = lastError;
		if (error != null) {
			health.withDetail("lastError", error);
		}
		return health.build();
	}

	@Override
	public void close() throws InterruptedException {
		running = false;
		if (thread.getState() != Thread.State.NEW) {
			thread.interrupt();
			thread.join(Duration.ofSeconds(10));
		}
		resetProducer();
	}

	/** An event that was not acknowledged; it and everything after it stay pending. */
	public static final class OutboxPublishException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		OutboxPublishException(Outbox.PendingEvent event, Throwable cause) {
			super("Publishing outbox event " + event.id() + " (" + event.eventId() + ") to " + event.topic()
					+ " failed: " + cause.getMessage(), cause);
		}

	}

}
