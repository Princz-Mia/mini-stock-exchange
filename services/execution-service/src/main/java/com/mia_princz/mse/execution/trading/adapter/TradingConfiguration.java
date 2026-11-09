package com.mia_princz.mse.execution.trading.adapter;

import java.nio.file.Path;
import java.time.Clock;

import javax.sql.DataSource;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

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

	/** Its own mapper, so stored command results never change shape with the web layer's JSON settings. */
	@Bean
	TradingStore tradingStore(JdbcClient jdbcClient) {
		return new JdbcTradingStore(jdbcClient, JsonMapper.builder().build());
	}

	@Bean(destroyMethod = "close")
	CommandWriter commandWriter(@Value("${mse.trading.queue-capacity:256}") int capacity, MeterRegistry meters) {
		CommandWriter writer = new CommandWriter("trading-writer", capacity);
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
	TradingEngine tradingEngine(TradingStore store, PlatformTransactionManager transactionManager, CommandWriter writer,
			Clock clock, BotCredentials botCredentials, AccountChanges accountChanges) {
		return new TradingEngine(store, new SpringTransactionRunner(transactionManager), writer, clock, botCredentials,
				accountChanges);
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
