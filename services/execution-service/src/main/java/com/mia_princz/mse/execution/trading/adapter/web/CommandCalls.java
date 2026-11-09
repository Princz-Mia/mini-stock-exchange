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
			throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "COMMAND_OUTCOME_UNKNOWN",
					"The command may still complete; look it up by its key").with("commandKey", key.toString());
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
			if (cause instanceof IllegalArgumentException) {
				throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "UNKNOWN_INSTRUMENT", "No such instrument");
			}
			if (cause instanceof WriterHaltException) {
				// Trading stays stopped until a restart rebuilds from the database, so the reason must be on record.
				LOG.error("Command {} halted the writer; trading is stopped until a restart", key, cause);
				throw unavailable();
			}
			if (cause instanceof WriterStoppedException) {
				throw unavailable();
			}
			// Anything else failed inside the command's transaction, which rolled back. Answered here, because an error
			// dispatch would be refused by the deny-by-default rules and look like a 403.
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

	private static ApiException unavailable() {
		return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TRADING_UNAVAILABLE", "Trading is unavailable");
	}

}
