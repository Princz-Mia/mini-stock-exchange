package com.mia_princz.mse.execution.trading.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import com.mia_princz.mse.execution.trading.application.CommandWriter;
import com.mia_princz.mse.execution.trading.application.TradingEngine;
import com.mia_princz.mse.execution.trading.application.TradingStore;
import com.mia_princz.mse.execution.trading.application.TransactionRunner;

class MarketControllerTest {

	/**
	 * A close with many orders resting takes far longer than any other command, so its request waits its own, longer
	 * time. Here the writer is held by another command: a halt gives up after the commands' wait, and a close sent
	 * then is still waiting well past that, until the writer comes to it.
	 */
	@Test
	void aCloseWaitsLongerForItsAnswerThanTheOtherTransitions() throws Exception {
		TradingQueries queries = mock(TradingQueries.class);
		when(queries.userId("operator")).thenReturn(Optional.of(UUID.randomUUID()));
		Authentication operator = new TestingAuthenticationToken("operator", "n/a");
		CountDownLatch release = new CountDownLatch(1);
		try (CommandWriter writer = new CommandWriter("market-controller-test", 8)) {
			TradingEngine engine = new TradingEngine(mock(TradingStore.class), mock(TransactionRunner.class), writer,
					Clock.systemUTC(), (account) -> new byte[0]);
			engine.startEmpty();
			MarketController controller = new MarketController(engine, queries, Duration.ofMillis(100),
					Duration.ofSeconds(30));
			CountDownLatch running = new CountDownLatch(1);
			writer.submit(() -> {
				running.countDown();
				return release.await(30, TimeUnit.SECONDS);
			});
			running.await();

			ApiException halt = catchThrowableOfType(ApiException.class,
					() -> controller.halt(UUID.randomUUID().toString(), operator));
			assertThat(halt.status()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
			assertThat(halt.toProblem().getProperties()).containsEntry("code", "COMMAND_OUTCOME_UNKNOWN");

			CompletableFuture<Throwable> closing = CompletableFuture
				.supplyAsync(() -> catchThrowable(() -> controller.close(UUID.randomUUID().toString(), operator)));
			assertThatThrownBy(() -> closing.get(1, TimeUnit.SECONDS)).as("ten times the commands' wait")
				.isInstanceOf(TimeoutException.class);

			release.countDown();
			// No market in this engine, which is an answer from the writer all the same.
			assertThat(closing.get(20, TimeUnit.SECONDS)).isInstanceOfSatisfying(ApiException.class,
					(answer) -> assertThat(answer.toProblem().getProperties()).containsEntry("code",
							"MARKET_NOT_SET_UP"));
		}
		finally {
			release.countDown();
		}
	}

	/**
	 * No lookup by key serves a close, so a close that is not answered in time does not send its caller to one: it
	 * says to send the same key again, which is what returns the answer.
	 */
	@Test
	void aCloseNotAnsweredInTimeSaysToSendTheSameKeyAgain() throws Exception {
		TradingQueries queries = mock(TradingQueries.class);
		when(queries.userId("operator")).thenReturn(Optional.of(UUID.randomUUID()));
		Authentication operator = new TestingAuthenticationToken("operator", "n/a");
		CountDownLatch release = new CountDownLatch(1);
		try (CommandWriter writer = new CommandWriter("market-controller-test", 8)) {
			TradingEngine engine = new TradingEngine(mock(TradingStore.class), mock(TransactionRunner.class), writer,
					Clock.systemUTC(), (account) -> new byte[0]);
			engine.startEmpty();
			MarketController controller = new MarketController(engine, queries, Duration.ofMillis(100),
					Duration.ofMillis(100));
			CountDownLatch running = new CountDownLatch(1);
			writer.submit(() -> {
				running.countDown();
				return release.await(30, TimeUnit.SECONDS);
			});
			running.await();
			UUID key = UUID.randomUUID();

			ApiException close = catchThrowableOfType(ApiException.class,
					() -> controller.close(key.toString(), operator));

			assertThat(close.status()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
			assertThat(close.toProblem().getProperties()).containsEntry("code", "COMMAND_OUTCOME_UNKNOWN")
				.containsEntry("commandKey", key.toString());
			assertThat(close.getMessage()).contains("send the same key again").doesNotContain("look it up");
		}
		finally {
			release.countDown();
		}
	}

}
