package com.mia_princz.mse.execution.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The relay's view of the outbox table: committed events still waiting for publication, oldest first. */
public interface Outbox {

	record PendingEvent(long id, UUID eventId, String topic, String key, byte[] payload) {
	}

	/** Unpublished events, oldest first. {@code oldestCreatedAt} is null when there are none. */
	record Backlog(long events, Instant oldestCreatedAt) {
	}

	/** Committed, unpublished events in id order. */
	List<PendingEvent> pending(int limit);

	/** Marks exactly these events as published; any other row, even one with an id in between, stays pending. */
	void markPublished(List<Long> ids);

	Backlog backlog();

}
