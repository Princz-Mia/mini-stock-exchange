package com.mia_princz.mse.execution.trading.adapter;

import java.time.Clock;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.json.JsonMapper;

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
	CommandWriter commandWriter(@Value("${mse.trading.queue-capacity:256}") int capacity) {
		return new CommandWriter("trading-writer", capacity);
	}

	@Bean
	TradingEngine tradingEngine(TradingStore store, PlatformTransactionManager transactionManager, CommandWriter writer,
			Clock clock) {
		return new TradingEngine(store, new SpringTransactionRunner(transactionManager), writer, clock);
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
