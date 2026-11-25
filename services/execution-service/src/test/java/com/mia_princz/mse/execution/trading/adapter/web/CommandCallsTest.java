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
		assertThat(unfinished.getMessage())
			.contains("The market is closed", "at most 7000 orders still rest", "nothing is to be sent again")
			.doesNotContain("nothing was applied");
		assertThat(unfinished.retryAfterSeconds()).as("nothing is to be sent again, so no Retry-After").isZero();
	}

	/**
	 * A close sent to a writer that had already halted for an unfinished close: queued behind it, or sent after a
	 * request that stopped waiting before the halt. The engine fails it with the unfinished close itself, and the
	 * answer is the same one, not only "trading is unavailable".
	 */
	@Test
	void aCloseSentToAWriterThatHaltedForAnUnfinishedCloseIsToldSoAgain() {
		ApiException again = answer(new CloseUnfinishedException(41, 7_000, new IllegalStateException("gone")));

		assertThat(again.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(again.toProblem().getProperties()).containsEntry("code", "MARKET_CLOSE_UNFINISHED")
			.containsEntry("state", "CLOSED")
			.containsEntry("commandSequence", "41")
			.containsEntry("restingOrders", 7_000)
			.containsEntry("commandKey", KEY.toString());
		assertThat(again.retryAfterSeconds()).isZero();
	}

	/** One order is one order, and none is said as none: the count is what the books held when trading stopped. */
	@Test
	void anUnfinishedCloseCountsOneOrderAndNoOrderInPlainWords() {
		ApiException one = answer(new WriterHaltException("halted", new CloseUnfinishedException(41, 1, null)));
		ApiException none = answer(new WriterHaltException("halted", new CloseUnfinishedException(41, 0, null)));

		assertThat(one.getMessage()).contains("at most 1 order still rests.").doesNotContain("orders still");
		assertThat(none.getMessage()).contains("none of its orders rests, but the close did not finish")
			.doesNotContain("at most");
		assertThat(none.toProblem().getProperties()).containsEntry("restingOrders", 0);
	}

	/** A command that is not answered in time says what its route tells it to; by default, to look the key up. */
	@Test
	void aCommandNotAnsweredInTimeSaysWhatToDoWithItsKey() {
		ApiException lookUp = catchThrowableOfType(ApiException.class,
				() -> CommandCalls.await(KEY, Duration.ofMillis(10), CompletableFuture::new));
		ApiException sendAgain = catchThrowableOfType(ApiException.class,
				() -> CommandCalls.await(KEY, Duration.ofMillis(10), "send the same key again", CompletableFuture::new));

		assertThat(lookUp.status()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
		assertThat(lookUp.getMessage()).isEqualTo("The command may still complete; look it up by its key");
		assertThat(sendAgain.status()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
		assertThat(sendAgain.getMessage()).isEqualTo("send the same key again");
		assertThat(sendAgain.toProblem().getProperties()).containsEntry("code", "COMMAND_OUTCOME_UNKNOWN")
			.containsEntry("commandKey", KEY.toString());
	}

	/** Any other halt says only that trading has stopped: what the command did is not known. */
	@Test
	void anyOtherHaltIsTradingUnavailable() {
		ApiException halted = answer(new WriterHaltException("halted", new IllegalStateException("out of step")));

		assertThat(halted.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(halted.toProblem().getProperties()).containsEntry("code", "TRADING_UNAVAILABLE")
			.doesNotContainKey("restingOrders");
		assertThat(halted.retryAfterSeconds()).as("the same key answers once the exchange is back").isPositive();
	}

}
