package com.mia_princz.mse.execution.trading.adapter;

import java.sql.SQLException;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import com.mia_princz.mse.execution.trading.application.CommandWriter;
import com.mia_princz.mse.execution.trading.application.TradingEngine;
import com.mia_princz.mse.execution.trading.application.TradingStore;

/**
 * The startup order: take writer ownership, then rebuild and reconcile the engine. Either failing stops the
 * application, so it never accepts traffic as a second writer or with untrusted state. Also reports trading
 * readiness: ownership still held, and the writer still running.
 */
public final class TradingStartup implements ApplicationRunner, HealthIndicator {

	enum State {

		STARTING, NO_SIMULATION, TRADING

	}

	private final WriterOwnership ownership;

	private final TradingStore store;

	private final TradingEngine engine;

	private final CommandWriter writer;

	private volatile State state = State.STARTING;

	public TradingStartup(WriterOwnership ownership, TradingStore store, TradingEngine engine, CommandWriter writer) {
		this.ownership = ownership;
		this.store = store;
		this.engine = engine;
		this.writer = writer;
	}

	@Override
	public void run(ApplicationArguments args) throws SQLException {
		if (!ownership.tryAcquire()) {
			throw new IllegalStateException("Another execution-service instance owns the market; refusing to start");
		}
		if (!store.hasSimulation()) {
			state = State.NO_SIMULATION;
			return;
		}
		engine.start();
		state = State.TRADING;
	}

	@Override
	public Health health() {
		if (!ownership.isHeld()) {
			return Health.down().withDetail("reason", "writer ownership lost").build();
		}
		return switch (state) {
			case STARTING -> Health.down().withDetail("reason", "starting").build();
			case NO_SIMULATION -> Health.up().withDetail("market", "not set up").build();
			case TRADING -> writer.isAvailable() ? Health.up().build()
					: Health.down().withDetail("reason", "writer halted").build();
		};
	}

}
