package com.mia_princz.mse.execution.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.mia_princz.mse.execution.TestcontainersConfiguration;

/**
 * A read on the outbox's connections that gets no answer fails after the socket timeout instead of waiting for ever.
 * Pausing PostgreSQL's container stands in for a connection lost with a query in flight: the socket stays open, and
 * nothing comes back. The container is this test's own, so pausing it stalls nothing else.
 */
class OutboxConnectionsIT {

	private static final Duration SOCKET_TIMEOUT = Duration.ofSeconds(2);

	private static PostgreSQLContainer postgres;

	private static OutboxConnections connections;

	@BeforeAll
	static void start() {
		postgres = new PostgreSQLContainer(DockerImageName.parse(TestcontainersConfiguration.POSTGRES_IMAGE)
			.asCompatibleSubstituteFor("postgres"));
		postgres.start();
		DataSourceProperties properties = new DataSourceProperties();
		properties.setUrl(postgres.getJdbcUrl());
		properties.setUsername(postgres.getUsername());
		properties.setPassword(postgres.getPassword());
		connections = OutboxConnections.create(properties, 2, SOCKET_TIMEOUT);
	}

	@AfterAll
	static void stop() {
		if (connections != null) {
			connections.close();
		}
		if (postgres != null) {
			postgres.stop();
		}
	}

	@Test
	void aReadWithNoAnswerFailsAfterTheTimeoutAndTheNextConnectionWorks() throws Exception {
		try (Connection connection = connections.pool().getConnection()) {
			assertThat(one(connection)).isEqualTo(1);
			pause(true);
			try {
				long started = System.nanoTime();
				CompletableFuture<Integer> read = CompletableFuture.supplyAsync(() -> {
					try {
						return one(connection);
					}
					catch (SQLException ex) {
						throw new IllegalStateException(ex);
					}
				});
				try {
					read.get(20, TimeUnit.SECONDS);
					fail("A read from a paused database returned");
				}
				catch (TimeoutException ex) {
					fail("A read from a paused database was still waiting after 20 s");
				}
				catch (ExecutionException ex) {
					assertThat(ex.getCause()).hasCauseInstanceOf(SQLException.class);
					assertThat(Duration.ofNanos(System.nanoTime() - started)).as("failed by the timeout, not at once")
						.isGreaterThanOrEqualTo(SOCKET_TIMEOUT.minusMillis(500));
				}
			}
			finally {
				pause(false);
			}
		}
		try (Connection connection = connections.pool().getConnection()) {
			assertThat(one(connection)).as("a working connection after the database answers again").isEqualTo(1);
		}
	}

	@Test
	void sessionsCarryTheOutboxName() throws Exception {
		try (Connection connection = connections.pool().getConnection();
				Statement statement = connection.createStatement();
				ResultSet name = statement.executeQuery("SELECT current_setting('application_name')")) {
			name.next();
			assertThat(name.getString(1)).isEqualTo(OutboxConnections.APPLICATION_NAME);
		}
	}

	private static int one(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery("SELECT 1")) {
			row.next();
			return row.getInt(1);
		}
	}

	private static void pause(boolean paused) {
		var docker = DockerClientFactory.instance().client();
		if (paused) {
			docker.pauseContainerCmd(postgres.getContainerId()).exec();
		}
		else {
			docker.unpauseContainerCmd(postgres.getContainerId()).exec();
		}
	}

}
