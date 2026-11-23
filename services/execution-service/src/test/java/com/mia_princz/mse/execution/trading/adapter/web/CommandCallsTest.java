package com.mia_princz.mse.execution.trading.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.mia_princz.mse.execution.trading.application.CommandWriter.WriterHaltException;
import com.mia_princz.mse.execution.trading.application.TradingEngine.CloseUnfinishedException;
import com.mia_princz.mse.execution.trading.application.TradingEngine.UnknownAccountException;
import com.mia_princz.mse.execution.trading.application.TradingEngine.UnknownInstrumentException;

class CommandCallsTest {

	private static final UUID KEY = UUID.randomUUID();

	private static ApiException answer(RuntimeException failure) {
		return catchThrowableOfType(ApiException.class,
				() -> CommandCalls.await(KEY, Duration.ofSeconds(1), () -> CompletableFuture.failedFuture(failure)));
	}

	@Test
	void anUnknownInstrumentIsANamedRefusal() {
		ApiException refused = answer(new UnknownInstrumentException(UUID.randomUUID()));

		assertThat(refused.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
		assertThat(refused.toProblem().getProperties()).containsEntry("code", "UNKNOWN_INSTRUMENT");
	}

	/** The answer a user without an account gets on the same route: by now the caller is one. */
	@Test
	void anAccountThatDoesNotTradeHereIsANamedRefusal() {
		ApiException refused = answer(new UnknownAccountException(UUID.randomUUID()));

		assertThat(refused.status()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(refused.toProblem().getProperties()).containsEntry("code", "NO_TRADING_ACCOUNT");
	}

	/** A fault rolled its transaction back; answering it as a refusal would claim something was decided. */
	@Test
	void anyOtherIllegalArgumentIsAFailureNothingWasAppliedFor() {
		ApiException failed = answer(new IllegalArgumentException("Not a command result"));

		assertThat(failed.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(failed.toProblem().getProperties()).containsEntry("code", "COMMAND_FAILED");
	}

	/**
	 * A close that committed and then halted the writer is not a command that applied nothing, and not only "trading
	 * is unavailable": the answer says the market is closed, which close it was and how much of it is left.
	 */
	@Test
	void aCloseThatCommittedAndCouldNotFinishSaysTheMarketIsClosed() {
		ApiException unfinished = answer(new WriterHaltException("halted",
				new CloseUnfinishedException(41, 7_000, new IllegalStateException("the database went away"))));

		assertThat(unfinished.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(unfinished.toProblem().getProperties()).containsEntry("code", "MARKET_CLOSE_UNFINISHED")
			.containsEntry("state", "CLOSED")
			.containsEntry("commandSequence", "41")
			.containsEntry("restingOrders", 7_000)
			.containsEntry("commandKey", KEY.toString());
		assertThat(unfinished.getMessage()).contains("The market is closed", "7000 orders still rest")
			.doesNotContain("nothing was applied");
	}

	/** Any other halt says only that trading has stopped: what the command did is not known. */
	@Test
	void anyOtherHaltIsTradingUnavailable() {
		ApiException halted = answer(new WriterHaltException("halted", new IllegalStateException("out of step")));

		assertThat(halted.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(halted.toProblem().getProperties()).containsEntry("code", "TRADING_UNAVAILABLE")
			.doesNotContainKey("restingOrders");
	}

}
