package com.mia_princz.mse.execution.trading.adapter;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

/**
 * The writer's own connections, apart from the pool that serves reads, sessions and the outbox relay: a burst of reads
 * can then never make a trading command wait for a connection. The writer is one thread and holds one connection at
 * a time; the pool keeps a second for a reconnect. Held in this wrapper, not exposed as a {@link DataSource} bean, so
 * Spring Boot's own data source stays the application's one.
 */
public final class WriterConnections implements AutoCloseable {

	private final HikariDataSource pool;

	WriterConnections(HikariDataSource pool) {
		this.pool = pool;
	}

	public DataSource dataSource() {
		return pool;
	}

	public String poolName() {
		return pool.getPoolName();
	}

	@Override
	public void close() {
		pool.close();
	}

}
