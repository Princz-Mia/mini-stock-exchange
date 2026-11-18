package com.mia_princz.mse.execution.trading.adapter;

import java.nio.file.Path;
import java.time.Clock;

import javax.sql.DataSource;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;

import com.zaxxer.hikari.HikariDataSource;

import tools.jackson.databind.json.JsonMapper;

import com.mia_princz.mse.execution.trading.application.AccountChanges;
import com.mia_princz.mse.execution.trading.application.BotCredentials;
import com.mia_princz.mse.execution.trading.application.CommandWriter;
import com.mia_princz.mse.execution.trading.application.TradingEngine;
import com.mia_princz.mse.execution.trading.application.TradingStore;

@Configuration(proxyBeanMethods = false)
class TradingConfiguration {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

	/**
	 * The writer's own pool, from the same connection settings as the application's: reads, sessions and the outbox
	 * relay use the application's pool, so they never hold a connection a command is waiting for.
	 */
	@Bean(destroyMethod = "close")
	WriterConnections writerConnections(DataSourceProperties properties, MeterRegistry meters,
			@Value("${mse.trading.writer-pool-size:2}") int size) {
		HikariDataSource pool = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
		pool.setPoolName("writer");
		pool.setMaximumPoolSize(size);
		pool.setMinimumIdle(1);
		pool.setMetricRegistry(meters);
		return new WriterConnections(pool);
	}

	/**
	 * On the writer's connections, since every trading command writes through it. Its own mapper, so stored command
	 * results never change shape with the web layer's JSON settings.
	 */
	@Bean
	TradingStore tradingStore(WriterConnections connections) {
		return new JdbcTradingStore(JdbcClient.create(connections.dataSource()), JsonMapper.builder().build());
	}

	@Bean(destroyMethod = "close")
	CommandWriter commandWriter(@Value("${mse.trading.queue-capacity:256}") int capacity,
			@Value("${mse.trading.max-batch:1}") int maxBatch, MeterRegistry meters) {
		// Trading commands queued together run as one run of at most max-batch; one until group commit is turned on.
		CommandWriter.checkRunLimit(maxBatch, capacity);
		DistributionSummary runs = DistributionSummary.builder("mse.writer.run.size")
			.description("Trading commands the writer took together as one run")
			.baseUnit("commands")
			.minimumExpectedValue(1.0)
			.maximumExpectedValue((double) maxBatch)
			.publishPercentileHistogram()
			.register(meters);
		CommandWriter writer = new CommandWriter("trading-writer", capacity, maxBatch, runs::record);
		// Admitted commands not yet taken by the writer; growing under steady load means the writer is falling behind.
		Gauge.builder("mse.writer.queue.depth", writer, CommandWriter::queueDepth)
			.description("Commands admitted and waiting for the writer")
			.register(meters);
		Gauge.builder("mse.writer.queue.capacity", () -> capacity)
			.description("Commands the writer queue holds before refusing more")
			.register(meters);
		return writer;
	}

	/** Plaintext bot tokens go to the simulator's secret directory, once, and nowhere else. */
	@Bean
	BotCredentials botCredentials(@Value("${mse.bots.token-directory}") Path directory) {
		return new FileBotCredentials(directory);
	}

	@Bean
	TradingEngine tradingEngine(TradingStore store, WriterConnections connections, CommandWriter writer, Clock clock,
			BotCredentials botCredentials, AccountChanges accountChanges, MeterRegistry meters) {
		// The store and the transactions share the writer's data source, so a command's statements join its transaction.
		return new TradingEngine(store,
				new SpringTransactionRunner(new JdbcTransactionManager(connections.dataSource())), writer, clock,
				botCredentials, accountChanges, new MicrometerWriterTimings(meters), System::nanoTime);
	}

	@Bean(destroyMethod = "close")
	WriterOwnership writerOwnership(DataSource dataSource) {
		return new WriterOwnership(dataSource);
	}

	@Bean("trading")
	TradingStartup tradingStartup(WriterOwnership ownership, TradingStore store, TradingEngine engine,
			CommandWriter writer) {
		return new TradingStartup(ownership, store, engine, writer);
	}

}
