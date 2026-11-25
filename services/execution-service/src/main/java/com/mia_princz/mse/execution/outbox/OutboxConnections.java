package com.mia_princz.mse.execution.outbox;

import java.time.Duration;

import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.zaxxer.hikari.HikariDataSource;

/**
 * The relay's and the admission check's own connections, from the same connection settings as the application's.
 * Every read on them is bounded by a socket timeout. A connection can die with a query in flight, as one did while
 * the host slept: PostgreSQL's side of it was gone, and no answer or error would ever arrive. That query then fails
 * after the timeout, and the relay backs off and carries on. Without the bound, one such read stopped publication,
 * and soon every new order, until a restart. Their queries are short, while reads that may rightly run long, such as
 * the startup reconciliation, stay on pools without the bound.
 */
final class OutboxConnections implements AutoCloseable {

	static final String POOL_NAME = "outbox";

	/** Shown in {@code pg_stat_activity}, so the relay's and admission's sessions are easy to find. */
	static final String APPLICATION_NAME = "mse-outbox";

	private final HikariDataSource pool;

	private final JdbcClient jdbc;

	private OutboxConnections(HikariDataSource pool) {
		this.pool = pool;
		this.jdbc = JdbcClient.create(pool);
	}

	static OutboxConnections create(DataSourceProperties properties, int size, Duration socketTimeout) {
		if (socketTimeout.toSeconds() < 1) {
			throw new IllegalArgumentException("mse.outbox.socket-timeout must be at least 1s, not " + socketTimeout);
		}
		HikariDataSource pool = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
		pool.setPoolName(POOL_NAME);
		pool.setMaximumPoolSize(size);
		pool.setMinimumIdle(1);
		// The driver reads its properties as strings, and the timeout in whole seconds. A read that waits longer fails
		// with an I/O error, which also makes Hikari retire the connection.
		pool.addDataSourceProperty("socketTimeout", String.valueOf(socketTimeout.toSeconds()));
		pool.addDataSourceProperty("ApplicationName", APPLICATION_NAME);
		return new OutboxConnections(pool);
	}

	HikariDataSource pool() {
		return pool;
	}

	JdbcClient jdbc() {
		return jdbc;
	}

	@Override
	public void close() {
		pool.close();
	}

}
