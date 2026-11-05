package com.mia_princz.mse.marketdata.projection;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.apache.avro.AvroRuntimeException;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Projects one Kafka topic into this service's database through a {@link Handler}. It reads every partition itself,
 * starting from the offsets saved in {@code projection_checkpoint}, or from the beginning when there are none, so an
 * empty database rebuilds from the retained topic. Each poll is applied in one transaction together with the new
 * checkpoints; Kafka keeps no consumer-group offsets for it.
 *
 * <p>
 * The feed is {@link State#LIVE} only after reaching the end offsets the topic had when it connected, so an empty or
 * half-rebuilt projection is never reported as current. A record that cannot be decoded, or that contradicts the
 * projection, pauses the feed at that offset instead of being skipped. A checkpoint older than the topic's oldest retained record is reported as a
 * history gap. The feed's state is outside readiness: stale public data is reported, not treated as a reason to stop
 * serving.
 */
public final class MarketFeedConsumer implements HealthIndicator, AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(MarketFeedConsumer.class);

	private static final Duration KAFKA_CALL_TIMEOUT = Duration.ofSeconds(5);

	public enum State {

		/** Not connected yet. */
		STARTING,

		/** Connected, but behind the end offsets it found when connecting. */
		CATCHING_UP,

		/** Caught up, and Kafka answers. */
		LIVE,

		/** Kafka has not answered for longer than the stale limit; the projection may be behind. */
		STALE,

		/** Stopped at a record it cannot read; needs a person. */
		PAUSED

	}

	public record Settings(String topic, Duration pollTimeout, Duration lagCheckInterval, Duration staleAfter,
			Duration retryBackoff) {
	}

	/** Decodes and applies one record inside the batch transaction; returns whether it changed the projection. */
	@FunctionalInterface
	public interface Handler {

		/**
		 * @throws IOException or {@link AvroRuntimeException} when the record cannot be read,
		 * {@link UnusableRecordException} when it contradicts the projection, or a {@code DataIntegrityViolationException}
		 * when the database refuses it; the feed pauses in each case
		 */
		boolean apply(byte[] payload) throws IOException;

	}

	private final Supplier<Consumer<String, byte[]>> consumers;

	private final TransactionTemplate transactions;

	private final Handler handler;

	private final Checkpoints checkpoints;

	private final Settings settings;

	private final Clock clock;

	private final Thread thread;

	private final AtomicLong applied = new AtomicLong();

	private volatile boolean running;

	private volatile Consumer<String, byte[]> current;

	private volatile State state = State.STARTING;

	private volatile long lag = -1;

	private volatile Instant lastContact;

	private volatile String error;

	private volatile String pausedAt;

	private volatile String historyGap;

	private Map<TopicPartition, Long> targets = Map.of();

	public MarketFeedConsumer(Supplier<Consumer<String, byte[]>> consumers, TransactionTemplate transactions,
			Handler handler, Checkpoints checkpoints, Settings settings, Clock clock) {
		this.consumers = consumers;
		this.transactions = transactions;
		this.handler = handler;
		this.checkpoints = checkpoints;
		this.settings = settings;
		this.clock = clock;
		this.thread = Thread.ofPlatform().name("feed-" + settings.topic()).unstarted(this::loop);
	}

	public void start() {
		running = true;
		thread.start();
	}

	private void loop() {
		while (running) {
			try (Consumer<String, byte[]> consumer = consumers.get()) {
				current = consumer;
				consume(consumer);
				if (state == State.PAUSED) {
					return;
				}
			}
			catch (WakeupException | InterruptException ex) {
				if (!running) {
					return;
				}
			}
			catch (RuntimeException ex) {
				error = ex.getMessage();
				if (state == State.LIVE) {
					state = State.STALE;
				}
				log.warn("Feed {} reconnecting in {} ms: {}", settings.topic(), settings.retryBackoff().toMillis(),
						ex.getMessage());
				log.debug("Feed failure", ex);
				sleepQuietly(settings.retryBackoff());
			}
			finally {
				current = null;
			}
		}
	}

	private void consume(Consumer<String, byte[]> consumer) {
		List<PartitionInfo> infos = consumer.partitionsFor(settings.topic(), KAFKA_CALL_TIMEOUT);
		if (infos == null || infos.isEmpty()) {
			throw new IllegalStateException("Topic " + settings.topic() + " has no partitions yet");
		}
		List<TopicPartition> partitions = infos.stream()
			.map((info) -> new TopicPartition(info.topic(), info.partition()))
			.toList();
		consumer.assign(partitions);
		Map<Integer, Long> saved = checkpoints.load(settings.topic());
		Map<TopicPartition, Long> oldest = consumer.beginningOffsets(partitions, KAFKA_CALL_TIMEOUT);
		for (TopicPartition partition : partitions) {
			Long next = saved.get(partition.partition());
			if (next != null && next < oldest.get(partition)) {
				// Retention deleted records this projection never read; they are gone from Kafka.
				historyGap = partition + " offsets " + next + " to " + (oldest.get(partition) - 1);
				log.warn("History gap on {}: records were deleted before they were projected", historyGap);
			}
		}
		for (TopicPartition partition : partitions) {
			Long next = saved.get(partition.partition());
			if (next == null) {
				consumer.seekToBeginning(List.of(partition));
			}
			else {
				consumer.seek(partition, next);
			}
		}
		targets = consumer.endOffsets(partitions, KAFKA_CALL_TIMEOUT);
		lastContact = clock.instant();
		state = State.CATCHING_UP;
		Instant lagCheckedAt = Instant.EPOCH;
		while (running) {
			ConsumerRecords<String, byte[]> records = consumer.poll(settings.pollTimeout());
			if (!records.isEmpty() && !applyBatch(records)) {
				state = State.PAUSED;
				return;
			}
			if (state == State.CATCHING_UP && reached(consumer, targets)) {
				state = State.LIVE;
				error = null;
				log.info("Feed {} is live", settings.topic());
			}
			Instant now = clock.instant();
			if (!now.isBefore(lagCheckedAt.plus(settings.lagCheckInterval()))) {
				checkLag(consumer, partitions, now);
				lagCheckedAt = now;
			}
		}
	}

	/**
	 * Applies records in order and saves their checkpoints in one transaction; false if one could not be used. That
	 * record's own writes, such as its processed-event mark, are rolled back to a savepoint while the records before
	 * it commit, so once the cause is fixed it is read again instead of being skipped as already seen.
	 */
	private boolean applyBatch(ConsumerRecords<String, byte[]> records) {
		Boolean complete = transactions.execute((status) -> {
			for (ConsumerRecord<String, byte[]> record : records) {
				boolean changed;
				Object savepoint = status.createSavepoint();
				try {
					changed = handler.apply(record.value());
					status.releaseSavepoint(savepoint);
				}
				catch (IOException | AvroRuntimeException | UnusableRecordException
						| DataIntegrityViolationException ex) {
					status.rollbackToSavepoint(savepoint);
					pausedAt = record.topic() + "/" + record.partition() + "@" + record.offset();
					error = "Unusable record at " + pausedAt + ": " + ex.getMessage();
					log.error("Feed paused: {}", error);
					return false;
				}
				if (changed) {
					applied.incrementAndGet();
				}
				checkpoints.save(record.topic(), record.partition(), record.offset() + 1);
			}
			return true;
		});
		return Boolean.TRUE.equals(complete);
	}

	private boolean reached(Consumer<String, byte[]> consumer, Map<TopicPartition, Long> ends) {
		for (Map.Entry<TopicPartition, Long> end : ends.entrySet()) {
			if (consumer.position(end.getKey(), KAFKA_CALL_TIMEOUT) < end.getValue()) {
				return false;
			}
		}
		return true;
	}

	/** Measures lag against the current end offsets; a Kafka that stops answering makes the feed stale. */
	private void checkLag(Consumer<String, byte[]> consumer, List<TopicPartition> partitions, Instant now) {
		Map<TopicPartition, Long> ends;
		try {
			ends = consumer.endOffsets(partitions, KAFKA_CALL_TIMEOUT);
		}
		catch (TimeoutException ex) {
			if (lastContact != null && now.isAfter(lastContact.plus(settings.staleAfter()))
					&& (state == State.LIVE || state == State.CATCHING_UP)) {
				state = State.STALE;
				error = "Kafka has not answered since " + lastContact;
				log.warn("Feed {} is stale: {}", settings.topic(), error);
			}
			return;
		}
		lastContact = now;
		long behind = 0;
		for (TopicPartition partition : partitions) {
			behind += Math.max(0, ends.get(partition) - consumer.position(partition, KAFKA_CALL_TIMEOUT));
		}
		lag = behind;
		if (state == State.STALE) {
			// Back in contact: live again only after catching up to where the topic is now.
			targets = ends;
			state = State.CATCHING_UP;
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

	public State state() {
		return state;
	}

	public long lag() {
		return lag;
	}

	public long appliedEvents() {
		return applied.get();
	}

	@Override
	public Health health() {
		Health.Builder health = state == State.LIVE ? Health.up() : Health.status(Status.OUT_OF_SERVICE);
		health.withDetail("state", state.name()).withDetail("lag", lag).withDetail("applied", applied.get());
		String problem = error;
		if (problem != null) {
			health.withDetail("error", problem);
		}
		String at = pausedAt;
		if (at != null) {
			health.withDetail("pausedAt", at);
		}
		String gap = historyGap;
		if (gap != null) {
			health.withDetail("historyGap", gap);
		}
		return health.build();
	}

	@Override
	public void close() throws InterruptedException {
		running = false;
		Consumer<String, byte[]> consumer = current;
		if (consumer != null) {
			consumer.wakeup();
		}
		if (thread.getState() == Thread.State.NEW) {
			return;
		}
		// The wakeup ends a poll cleanly. An interrupt is only for a thread asleep between retries: an interrupted
		// thread cannot close its Kafka consumer cleanly, and Kafka logs that as an error.
		thread.join(Duration.ofSeconds(2));
		if (thread.isAlive()) {
			thread.interrupt();
			thread.join(Duration.ofSeconds(10));
		}
	}

}
