package com.mia_princz.mse.marketdata.projection;

import java.util.HashMap;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;

/** The next offset to read per partition, saved in the same transaction as the changes it covers. */
public final class Checkpoints {

	private final JdbcClient jdbc;

	public Checkpoints(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Partition to next offset; a partition never consumed is absent. */
	public Map<Integer, Long> load(String topic) {
		Map<Integer, Long> offsets = new HashMap<>();
		jdbc.sql("SELECT partition, next_offset FROM projection_checkpoint WHERE topic = ?")
			.param(topic)
			.query((row) -> {
				offsets.put(row.getInt("partition"), row.getLong("next_offset"));
			});
		return offsets;
	}

	public void save(String topic, int partition, long nextOffset) {
		jdbc.sql("""
				INSERT INTO projection_checkpoint (topic, partition, next_offset) VALUES (?, ?, ?)
				ON CONFLICT (topic, partition) DO UPDATE SET next_offset = EXCLUDED.next_offset, updated_at = now()
				WHERE projection_checkpoint.next_offset < EXCLUDED.next_offset""")
			.params(topic, partition, nextOffset)
			.update();
	}

}
