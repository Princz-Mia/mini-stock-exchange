package com.mia_princz.mse.execution.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The relay's view of the outbox table: committed events still waiting for publication, oldest first. */
public interface Outbox {

	record PendingEvent(long id, UUID eventId, String topic, String key, byte[] payload) {
	}

	/**
	 * Unpublished events: how many, their encoded size in bytes, and when the oldest was written ({@code null} when
	 * there are none).
	 */
	record Backlog(long events, long bytes, Instant oldestCreatedAt) {

		static final Backlog EMPTY = new Backlog(0, 0, null);

	}

	/** Committed, unpublished events in id order. */
	List<PendingEvent> pending(int limit);

	/** Marks exactly these events as published; any other row, even one with an id in between, stays pending. */
	void markPublished(List<Long> ids);

	Backlog backlog();

}
