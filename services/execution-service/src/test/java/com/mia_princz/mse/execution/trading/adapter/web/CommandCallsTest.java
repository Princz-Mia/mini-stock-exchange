package com.mia_princz.mse.execution.trading.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.mia_princz.mse.execution.trading.application.TradingEngine.UnknownInstrumentException;

class CommandCallsTest {

	private static ApiException answer(RuntimeException failure) {
		return catchThrowableOfType(ApiException.class, () -> CommandCalls.await(UUID.randomUUID(), Duration.ofSeconds(1),
				() -> CompletableFuture.failedFuture(failure)));
	}

	@Test
	void anUnknownInstrumentIsANamedRefusal() {
		ApiException refused = answer(new UnknownInstrumentException(UUID.randomUUID()));

		assertThat(refused.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
		assertThat(refused.toProblem().getProperties()).containsEntry("code", "UNKNOWN_INSTRUMENT");
	}

	/** A fault rolled its transaction back; answering it as a refusal would claim something was decided. */
	@Test
	void anyOtherIllegalArgumentIsAFailureNothingWasAppliedFor() {
		ApiException failed = answer(new IllegalArgumentException("Not a command result"));

		assertThat(failed.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(failed.toProblem().getProperties()).containsEntry("code", "COMMAND_FAILED");
	}

}
