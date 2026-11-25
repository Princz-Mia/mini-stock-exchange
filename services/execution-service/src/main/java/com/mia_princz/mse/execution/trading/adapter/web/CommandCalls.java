package com.mia_princz.mse.execution.trading.adapter.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import com.mia_princz.mse.execution.trading.application.CommandWriter.WriterBusyException;
import com.mia_princz.mse.execution.trading.application.CommandWriter.WriterHaltException;
import com.mia_princz.mse.execution.trading.application.CommandWriter.WriterStoppedException;
import com.mia_princz.mse.execution.trading.application.IdempotencyConflictException;
import com.mia_princz.mse.execution.trading.application.TradingEngine.CloseUnfinishedException;
import com.mia_princz.mse.execution.trading.application.TradingEngine.UnknownAccountException;
import com.mia_princz.mse.execution.trading.application.TradingEngine.UnknownInstrumentException;

/** How every command endpoint submits to the writer and answers: keys, admission, timeouts and failures. */
final class CommandCalls {

	private static final Logger LOG = LoggerFactory.getLogger(CommandCalls.class);

	private CommandCalls() {
	}

	interface Submission<T> {

		CompletableFuture<T> submit();

	}

	/** Waits for the committed result. A timeout does not cancel the command; its outcome is recoverable by key. */
	static <T> T await(UUID key, Duration timeout, Submission<T> submission) {
		return await(key, timeout, "The command may still complete; look it up by its key", submission);
	}

	/** As above; {@code unanswered} is what a caller is told to do when the wait runs out, where no lookup serves. */
	static <T> T await(UUID key, Duration timeout, String unanswered, Submission<T> submission) {
		CompletableFuture<T> future;
		try {
			future = submission.submit();
		}
		catch (WriterBusyException ex) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EXCHANGE_BUSY",
					"The exchange is busy and did not accept the command; retry with the same key");
		}
		catch (WriterStoppedException ex) {
			throw unavailable();
		}
		try {
			return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException ex) {
			throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "COMMAND_OUTCOME_UNKNOWN", unanswered)
				.with("commandKey", key.toString());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw unavailable();
		}
		catch (ExecutionException ex) {
			Throwable cause = ex.getCause();
			if (cause instanceof IdempotencyConflictException) {
				throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
						"This idempotency key was already used for a different request");
			}
			// Only this exception means an unknown instrument; any other is a fault, answered below as one.
			if (cause instanceof UnknownInstrumentException) {
				throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "UNKNOWN_INSTRUMENT", "No such instrument");
			}
			// The account was the caller's when the request was authenticated but is not the current simulation's now.
			if (cause instanceof UnknownAccountException) {
				throw new ApiException(HttpStatus.FORBIDDEN, "NO_TRADING_ACCOUNT", "This user has no trading account");
			}
			if (cause instanceof WriterHaltException) {
				// Trading stays stopped until a restart rebuilds from the database, so the reason must be on record.
				LOG.error("Command {} halted the writer; trading is stopped until a restart", key, cause);
				if (cause.getCause() instanceof CloseUnfinishedException unfinished) {
					throw closeUnfinished(key, unfinished);
				}
				throw unavailable();
			}
			// A close sent to a writer that had already halted for one. The engine logged that halt when it happened.
			if (cause instanceof CloseUnfinishedException unfinished) {
				throw closeUnfinished(key, unfinished);
			}
			if (cause instanceof WriterStoppedException) {
				throw unavailable();
			}
			// Anything else failed inside the command's transaction, which rolled back. Answered here, because an error
			// dispatch would be refused by the deny-by-default rules and look like a 403. A close is the one command
			// with more than one transaction, and a failure after its first never comes here: it halts the writer.
			LOG.error("Command {} failed and was not applied", key, cause);
			throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "COMMAND_FAILED",
					"The command failed and nothing was applied; retrying the same key is safe");
		}
	}

	static UUID idempotencyKey(String header) {
		try {
			return UUID.fromString(header);
		}
		catch (IllegalArgumentException | NullPointerException ex) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
					"An Idempotency-Key header with a UUID is required");
		}
	}

	static byte[] sha256(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * A close that committed and then could not cancel every resting order. Answered as what it is: the market is
	 * closed, so "nothing was applied" would be false, and nothing is to be sent again, since the next start finishes
	 * it. So this 503 has no Retry-After: a retry before the restart only gets this answer again. The count is the
	 * books', and the database may hold up to one transaction of the close fewer, hence "at most".
	 */
	private static ApiException closeUnfinished(UUID key, CloseUnfinishedException unfinished) {
		int resting = unfinished.restingOrders();
		String left = switch (resting) {
			case 0 -> "The market is closed and none of its orders rests, but the close did not finish.";
			case 1 -> "The market is closed, but at most 1 order still rests.";
			default -> "The market is closed, but at most " + resting + " orders still rest.";
		};
		return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MARKET_CLOSE_UNFINISHED",
				left + " Trading is stopped, and nothing is to be sent again: the exchange finishes the close when "
						+ "it restarts, and the key that closed the market then returns the close's answer")
			.with("state", "CLOSED")
			.with("commandSequence", Long.toString(unfinished.commandSequence()))
			.with("restingOrders", resting)
			.with("commandKey", key.toString())
			.withoutRetryAfter();
	}

	private static ApiException unavailable() {
		return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TRADING_UNAVAILABLE", "Trading is unavailable");
	}

}
