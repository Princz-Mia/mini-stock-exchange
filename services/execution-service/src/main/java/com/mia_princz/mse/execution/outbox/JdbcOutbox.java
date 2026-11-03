package com.mia_princz.mse.execution.outbox;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reads at READ COMMITTED, so an event is visible only once the command that wrote it has committed. Every writer
 * of outbox rows holds the market row lock, so ids are assigned in commit order and a pending row is never
 * overtaken by a later one.
 */
public final class JdbcOutbox implements Outbox {

	private final JdbcClient jdbc;

	public JdbcOutbox(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public List<PendingEvent> pending(int limit) {
		return jdbc.sql("""
				SELECT id, event_id, topic, event_key, payload FROM outbox
				WHERE published_at IS NULL ORDER BY id LIMIT ?""")
			.param(limit)
			.query((row, index) -> new PendingEvent(row.getLong("id"), row.getObject("event_id", UUID.class),
					row.getString("topic"), row.getString("event_key"), row.getBytes("payload")))
			.list();
	}

	@Override
	public void markPublished(List<Long> ids) {
		// Exact ids, never a range: a row inside the range may have committed after the batch was read, and would be
		// marked without ever being sent.
		int marked = jdbc.sql("UPDATE outbox SET published_at = now() WHERE id = ANY(?) AND published_at IS NULL")
			.param(ids.toArray(Long[]::new))
			.update();
		if (marked != ids.size()) {
			throw new IllegalStateException("Marked " + marked + " of " + ids.size()
					+ " outbox events; another publisher may be running");
		}
	}

	@Override
	public Backlog backlog() {
		return jdbc.sql("""
				SELECT count(*) AS events, coalesce(sum(octet_length(payload)), 0) AS bytes, min(created_at) AS oldest
				FROM outbox WHERE published_at IS NULL""")
			.query((row, index) -> {
				OffsetDateTime oldest = row.getObject("oldest", OffsetDateTime.class);
				return new Backlog(row.getLong("events"), row.getLong("bytes"),
						oldest == null ? null : oldest.toInstant());
			})
			.single();
	}

}
