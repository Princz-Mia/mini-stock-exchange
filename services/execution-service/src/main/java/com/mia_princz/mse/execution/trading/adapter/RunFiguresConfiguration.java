package com.mia_princz.mse.execution.trading.adapter;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

/** Finished bot runs get their figures from a pass every few seconds, off the writer. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class RunFiguresConfiguration {

	@Bean
	RunFigures runFigures(JdbcClient jdbc, PlatformTransactionManager transactions, Clock clock) {
		return new RunFigures(jdbc, transactions, clock);
	}

}
