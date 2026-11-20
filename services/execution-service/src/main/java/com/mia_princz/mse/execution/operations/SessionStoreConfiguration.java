package com.mia_princz.mse.execution.operations;

import javax.sql.DataSource;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.session.jdbc.config.annotation.SpringSessionDataSource;
import org.springframework.session.jdbc.config.annotation.SpringSessionTransactionManager;

import com.zaxxer.hikari.HikariDataSource;

/**
 * The session store's own connections, which commit without waiting for the WAL flush.
 * <p>
 * Every signed-in request updates its session's last access, so with the application's pool each request made one
 * durable commit beside the trading writer's, and at a high request rate those commits filled the pool and shared the
 * writer's flushes. A session row is not trading data: if PostgreSQL itself crashes, the session changes of its last
 * fraction of a second may be lost, so a trader signs in again or a sign-out made in that instant is undone. A
 * session a password change ended is still refused on its next request, by the durable password version, though an
 * account stream it had open runs on until its own lifetime ends. Commit order is unchanged, as recovery replays the
 * log in order. Orders, trades, balances and every other write stay durable, on the application's and the writer's
 * pools.
 * <p>
 * Neither bean is a default candidate, so Spring Boot keeps its own data source and transaction manager as the
 * application's; only Spring Session, which asks for these by their qualifiers, uses them.
 */
@Configuration(proxyBeanMethods = false)
class SessionStoreConfiguration {

	static final String POOL_NAME = "sessions";

	@Bean(defaultCandidate = false, destroyMethod = "close")
	@SpringSessionDataSource
	HikariDataSource sessionStoreDataSource(DataSourceProperties properties, MeterRegistry meters,
			@Value("${mse.sessions.pool-size:4}") int size) {
		HikariDataSource pool = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
		pool.setPoolName(POOL_NAME);
		pool.setMaximumPoolSize(size);
		pool.setMinimumIdle(1);
		// Per connection, and only on this pool: the setting lasts for the connection's life, and Hikari keeps it.
		pool.setConnectionInitSql("SET synchronous_commit = off");
		pool.setMetricRegistry(meters);
		return pool;
	}

	@Bean(defaultCandidate = false)
	@SpringSessionTransactionManager
	JdbcTransactionManager sessionStoreTransactionManager(@SpringSessionDataSource DataSource sessions) {
		return new JdbcTransactionManager(sessions);
	}

}
