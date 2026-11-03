package com.mia_princz.mse.execution.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stops new orders while too much is waiting to be published, so a long Kafka outage cannot fill the database.
 * Either limit closes admission: the number of unpublished events, or their encoded size. Cancels are not checked:
 * they reduce exposure, and the events they write are bounded by the number of open orders.
 *
 * <p>
 * The backlog is read at most once per refresh interval, so checking costs one query a second at most, whatever
 * the order rate. Admission reopens as soon as a reading is back under both limits.
 */
public final class OutboxAdmission {

	private static final Logger log = LoggerFactory.getLogger(OutboxAdmission.class);

	public record Limits(long maxEvents, long maxBytes, Duration refresh) {
	}

	private final Outbox outbox;

	private final Limits limits;

	private final Clock clock;

	private Outbox.Backlog backlog = Outbox.Backlog.EMPTY;

	private Instant checkedAt = Instant.EPOCH;

	private boolean open = true;

	public OutboxAdmission(Outbox outbox, Limits limits, Clock clock) {
		this.outbox = outbox;
		this.limits = limits;
		this.clock = clock;
	}

	/** Whether a new order may be queued now. */
	public synchronized boolean admitsNewOrders() {
		Instant now = clock.instant();
		if (!now.isBefore(checkedAt.plus(limits.refresh()))) {
			backlog = outbox.backlog();
			checkedAt = now;
			boolean wasOpen = open;
			open = backlog.events() < limits.maxEvents() && backlog.bytes() < limits.maxBytes();
			if (wasOpen && !open) {
				log.warn("New orders paused: {} unpublished events, {} bytes (limits {} events, {} bytes)",
						backlog.events(), backlog.bytes(), limits.maxEvents(), limits.maxBytes());
			}
			else if (!wasOpen && open) {
				log.info("New orders resumed: {} unpublished events, {} bytes", backlog.events(), backlog.bytes());
			}
		}
		return open;
	}

	/** The last reading, for health and metrics; not a reason to read again. */
	public synchronized boolean isOpen() {
		return open;
	}

}
