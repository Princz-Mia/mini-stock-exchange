package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.json.JsonMapper;

import com.mia_princz.mse.execution.trading.adapter.WriterConnections;
import com.mia_princz.mse.execution.trading.application.CommandKey;
import com.mia_princz.mse.execution.trading.application.PlaceOrder;
import com.mia_princz.mse.execution.trading.application.PlacementResult;
import com.mia_princz.mse.execution.trading.application.TradingEngine;
import com.mia_princz.mse.execution.trading.domain.OrderType;
import com.mia_princz.mse.execution.trading.domain.Price;
import com.mia_princz.mse.execution.trading.domain.Quantity;
import com.mia_princz.mse.execution.trading.domain.Side;

/**
 * The writer has its own connections: with every connection of the application's pool held, as a burst of reads
 * would hold them, a trading command still commits at once instead of waiting for one.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=writer-connections",
				"mse.dev-users.trader-password=" + WriterConnectionsIT.PASSWORD,
				"mse.dev-users.operator-password=" + WriterConnectionsIT.PASSWORD,
				// A connection the application pool cannot give fails after 2 s, so the test can take them all.
				"spring.datasource.hikari.connection-timeout=2000" })
class WriterConnectionsIT {

	static final String PASSWORD = "test-writer-connections-password";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@LocalServerPort
	int port;

	@Autowired
	TradingEngine engine;

	@Autowired
	DataSource applicationPool;

	@Autowired
	WriterConnections writer;

	@Autowired
	MeterRegistry meters;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void aCommandCommitsWhileTheApplicationPoolIsFull() throws Exception {
		TestBrowser operator = TestBrowser.loggedIn(port, "operator", PASSWORD);
		HttpResponse<String> added = operator.post("/api/v1/admin/instruments",
				"{\"symbol\":\"NOVA\",\"referencePrice\":\"100.00\"}", "Idempotency-Key", UUID.randomUUID().toString());
		assertThat(added.statusCode()).as(added.body()).isEqualTo(201);
		UUID nova = UUID.fromString(JSON.readTree(added.body()).get("instrumentId").asString());
		HttpResponse<String> trader = operator.post("/api/v1/admin/traders", """
				{"username":"maria.writer","password":"%s","cash":"10000.00","holdings":[]}""".formatted(PASSWORD),
				"Idempotency-Key", UUID.randomUUID().toString());
		assertThat(trader.statusCode()).as(trader.body()).isEqualTo(201);
		assertThat(operator.post("/api/v1/admin/market/open", "", "Idempotency-Key", UUID.randomUUID().toString())
			.statusCode()).isEqualTo(200);
		UUID account = jdbc.queryForObject("""
				SELECT a.id FROM account a JOIN app_user u ON u.id = a.owner_user_id WHERE u.username = 'maria.writer'""",
				UUID.class);

		// The pools are apart, and the writer's reports its own connections.
		assertThat(writer.dataSource()).isNotSameAs(applicationPool);
		assertThat(writer.poolName()).isEqualTo("writer");
		assertThat(meters.find("hikaricp.connections").tag("pool", "writer").gauge()).isNotNull();

		int size = applicationPool.unwrap(HikariDataSource.class).getMaximumPoolSize();
		List<Connection> held = new ArrayList<>();
		try {
			// Every connection the application pool will give; others already hold the rest (the writer's lock).
			while (held.size() < size) {
				try {
					held.add(applicationPool.getConnection());
				}
				catch (SQLTransientConnectionException ex) {
					break;
				}
			}
			assertThat(applicationPool.unwrap(HikariDataSource.class).getHikariPoolMXBean().getIdleConnections())
				.as("the application pool is full")
				.isZero();
			PlaceOrder order = new PlaceOrder(UUID.randomUUID(), account, nova, Side.BUY, OrderType.LIMIT,
					Price.parse("1.00"), new Quantity(1));
			CommandKey key = new CommandKey(account, UUID.randomUUID(), "POST /api/v1/orders", new byte[32]);
			// A command on the application pool would wait for a connection and fail; the writer must not wait at all.
			PlacementResult placed = engine.place(key, order).get(10, TimeUnit.SECONDS);
			assertThat(placed.rejection()).isNull();
		}
		finally {
			for (Connection connection : held) {
				connection.close();
			}
		}
		assertThat(jdbc.queryForObject("SELECT state FROM orders WHERE account_id = ?", String.class, account))
			.isEqualTo("OPEN");
	}

}
