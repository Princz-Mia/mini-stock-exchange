package com.mia_princz.mse.execution.trading.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import com.mia_princz.mse.execution.trading.application.CommandWriter;
import com.mia_princz.mse.execution.trading.application.CommandWriter.WriterHaltException;
import com.mia_princz.mse.execution.trading.application.TradingEngine;
import com.mia_princz.mse.execution.trading.application.TradingStore;
import com.mia_princz.mse.execution.trading.application.TransactionRunner;

/** What the startup reports as trading's health. */
class TradingStartupTest {

	/**
	 * A process that started before any market existed takes commands like any other, the ones that set a market up
	 * first. Its writer can halt like any other's, and its health must say so.
	 */
	@Test
	void aWriterThatHaltsAfterAStartWithNoMarketIsReported() throws Exception {
		WriterOwnership ownership = held();
		TradingStore store = mock(TradingStore.class);
		when(store.hasSimulation()).thenReturn(false);
		try (CommandWriter writer = new CommandWriter("startup-test", 8)) {
			TradingStartup startup = new TradingStartup(ownership, store, engine(store, writer), writer);
			assertThat(startup.health().getStatus()).as("before it has started").isEqualTo(Status.DOWN);

			startup.run(null);
			assertThat(startup.health().getStatus()).isEqualTo(Status.UP);
			assertThat(startup.health().getDetails()).containsEntry("market", "not set up");

			halt(writer);

			assertThat(startup.health().getStatus()).isEqualTo(Status.DOWN);
			assertThat(startup.health().getDetails()).containsEntry("reason", "writer halted");
		}
	}

	/**
	 * The usual case: the process found a market when it started, as every restart of a stack that has one does. A
	 * close that cannot finish halts this writer, and health is how its operator sees that trading has stopped.
	 */
	@Test
	void aWriterThatHaltsAfterAStartWithAMarketIsReported() throws Exception {
		WriterOwnership ownership = held();
		TradingStore store = mock(TradingStore.class);
		when(store.hasSimulation()).thenReturn(true);
		try (CommandWriter writer = new CommandWriter("startup-test", 8)) {
			// The transactions do nothing here, so the engine's start loads nothing and succeeds.
			TradingStartup startup = new TradingStartup(ownership, store, engine(store, writer), writer);

			startup.run(null);
			assertThat(startup.health().getStatus()).isEqualTo(Status.UP);
			assertThat(startup.health().getDetails()).as("it has a market").doesNotContainKey("market");

			halt(writer);

			assertThat(startup.health().getStatus()).isEqualTo(Status.DOWN);
			assertThat(startup.health().getDetails()).containsEntry("reason", "writer halted");
		}
	}

	private static WriterOwnership held() throws Exception {
		WriterOwnership ownership = mock(WriterOwnership.class);
		when(ownership.tryAcquire()).thenReturn(true);
		when(ownership.isHeld()).thenReturn(true);
		return ownership;
	}

	private static TradingEngine engine(TradingStore store, CommandWriter writer) {
		return new TradingEngine(store, mock(TransactionRunner.class), writer, Clock.systemUTC(),
				(account) -> new byte[0]);
	}

	private static void halt(CommandWriter writer) {
		CompletableFuture<Object> halting = writer.submit(() -> {
			throw new WriterHaltException("injected", null);
		});
		assertThatThrownBy(() -> halting.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(WriterHaltException.class);
	}

}
