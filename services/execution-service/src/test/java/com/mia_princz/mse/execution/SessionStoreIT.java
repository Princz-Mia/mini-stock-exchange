package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.jdbc.config.annotation.SpringSessionDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import com.mia_princz.mse.execution.trading.adapter.WriterConnections;

/**
 * Sessions are stored through their own pool, whose commits do not wait for the WAL flush, and nothing else is: the
 * application's pool, its transaction manager and the writer's pool stay durable.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=session-store", "mse.dev-users.operator-password=" + SessionStoreIT.PASSWORD })
class SessionStoreIT {

	static final String PASSWORD = "test-session-store-password";

	@LocalServerPort
	int port;

	@Autowired
	DataSource applicationPool;

	@Autowired
	@SpringSessionDataSource
	DataSource sessionPool;

	@Autowired
	WriterConnections writer;

	@Autowired
	PlatformTransactionManager transactions;

	@Autowired
	MeterRegistry meters;

	@Autowired
	JdbcIndexedSessionRepository sessions;

	@Autowired
	HealthEndpointGroups health;

	@Test
	void onlyTheSessionStoreCommitsWithoutWaitingForTheFlush() throws Exception {
		assertThat(applicationPool.unwrap(HikariDataSource.class).getPoolName()).isEqualTo("execution");
		assertThat(sessionPool.unwrap(HikariDataSource.class).getPoolName()).isEqualTo("sessions");
		assertThat(synchronousCommit(sessionPool)).isEqualTo("off");
		assertThat(synchronousCommit(applicationPool)).isEqualTo("on");
		assertThat(synchronousCommit(writer.dataSource())).isEqualTo("on");
		// Spring Boot's own transaction manager still runs the application's transactions, on its pool.
		assertThat(transactions).isInstanceOf(DataSourceTransactionManager.class);
		assertThat(((DataSourceTransactionManager) transactions).getDataSource()).isSameAs(applicationPool);
		// The session store's statements and its transactions are on the same pool, so a session's rows commit
		// together.
		JdbcTemplate statements = (JdbcTemplate) ReflectionTestUtils.getField(sessions, "jdbcOperations");
		TransactionTemplate sessionTransactions = (TransactionTemplate) ReflectionTestUtils.getField(sessions,
				"transactionOperations");
		assertThat(statements.getDataSource()).isSameAs(sessionPool);
		assertThat(((DataSourceTransactionManager) sessionTransactions.getTransactionManager()).getDataSource())
			.isSameAs(sessionPool);
	}

	@Test
	void aSignedInRequestLoadsAndSavesItsSessionThroughTheSessionPool() throws Exception {
		TestBrowser operator = TestBrowser.loggedIn(port, "operator", PASSWORD);
		long before = borrows("sessions");

		HttpResponse<String> me = operator.get("/api/v1/me");

		assertThat(me.statusCode()).as(me.body()).isEqualTo(200);
		// One borrow to load the session and one to save its last access.
		assertThat(borrows("sessions") - before).isGreaterThanOrEqualTo(2);
		assertThat(new JdbcTemplate(applicationPool)
			.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name = 'operator'", Long.class))
			.isPositive();
	}

	@Test
	void readinessChecksTheApplicationsPoolAloneAndIsUp() throws Exception {
		HealthEndpointGroup readiness = health.get("readiness");
		assertThat(readiness.isMember("db/dataSource")).isTrue();
		assertThat(readiness.isMember("db/sessionStoreDataSource")).isFalse();

		HttpResponse<String> probe = new TestBrowser(port).get("/actuator/health/readiness");

		assertThat(probe.statusCode()).as(probe.body()).isEqualTo(200);
		assertThat(probe.body()).contains("\"UP\"");
	}

	private static String synchronousCommit(DataSource pool) {
		return new JdbcTemplate(pool).queryForObject("SHOW synchronous_commit", String.class);
	}

	private long borrows(String pool) {
		Timer usage = meters.find("hikaricp.connections.usage").tag("pool", pool).timer();
		return usage == null ? 0 : usage.count();
	}

}
