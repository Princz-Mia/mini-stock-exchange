package com.mia_princz.mse.execution.trading.adapter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

/**
 * Exclusive right to write: a session-level PostgreSQL advisory lock held on one dedicated connection for the life of
 * the process. A second instance cannot take it. If this process dies or its connection drops, PostgreSQL releases the
 * lock; the writer epoch then fences anything this process might still try to commit.
 */
public final class WriterOwnership implements AutoCloseable {

	/** An arbitrary constant naming the lock; it reads "msewrite" in ASCII. */
	static final long LOCK_KEY = 0x6d73657772697465L;

	private final DataSource dataSource;

	private Connection connection;

	public WriterOwnership(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	/** Takes the lock without waiting. False means another process owns the market. */
	public synchronized boolean tryAcquire() throws SQLException {
		if (connection != null) {
			return true;
		}
		Connection candidate = dataSource.getConnection();
		try (PreparedStatement statement = candidate.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
			candidate.setAutoCommit(true);
			statement.setLong(1, LOCK_KEY);
			try (ResultSet result = statement.executeQuery()) {
				result.next();
				if (result.getBoolean(1)) {
					connection = candidate;
					return true;
				}
			}
		}
		catch (SQLException ex) {
			candidate.close();
			throw ex;
		}
		candidate.close();
		return false;
	}

	/** The lock lives exactly as long as its connection. */
	public synchronized boolean isHeld() {
		try {
			return connection != null && connection.isValid(2);
		}
		catch (SQLException ex) {
			return false;
		}
	}

	@Override
	public synchronized void close() throws SQLException {
		if (connection == null) {
			return;
		}
		try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
			statement.setLong(1, LOCK_KEY);
			statement.execute();
		}
		finally {
			connection.close();
			connection = null;
		}
	}

}
