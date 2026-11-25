package com.mia_princz.mse.execution.trading.adapter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mia_princz.mse.execution.trading.application.CloseSchedule;
import com.mia_princz.mse.execution.trading.application.CommandWriter.WriterHaltException;
import com.mia_princz.mse.execution.trading.application.MarketClosing;
import com.mia_princz.mse.execution.trading.application.TradingEngine.CloseUnfinishedException;

/**
 * Asks the engine to close the market when its close time comes, on a thread of its own: Spring's scheduling thread
 * runs the run figures, and a close must not wait for a pass of them. It never sleeps longer than the check interval,
 * so a clock that is set forward, or a machine that slept, is noticed within that interval; until the close time is
 * nearer than that it only compares two times.
 * <p>
 * It asks only while this process owns the writer. A close that could not be submitted (the writer busy, say) or that
 * failed and rolled back is asked for again after the interval; the engine decides on its writer whether a close is
 * still to be made, so asking again never closes twice. A close that halted the writer is not asked for again: the
 * restart closes the market before it takes a command.
 */
public final class ScheduledClose implements CloseSchedule, AutoCloseable {

	/** What the schedule asks of the engine: {@code TradingEngine::closeOnSchedule}. */
	@FunctionalInterface
	public interface Closer {

		CompletableFuture<Optional<MarketClosing>> close(UUID simulationId, Instant closesAt);

	}

	private static final Logger LOG = LoggerFactory.getLogger(ScheduledClose.class);

	private record Target(UUID simulationId, Instant closesAt) {
	}

	private final Clock clock;

	private final BooleanSupplier ownsWriter;

	private final Duration checkInterval;

	private final Closer closer;

	private final ScheduledExecutorService timer;

	/** What is to be closed, and when; null for nothing. */
	private Target target;

	private ScheduledFuture<?> pending;

	/** The target this process last complained about, so that a close that keeps failing is logged once. */
	private Target complained;

	public ScheduledClose(Clock clock, BooleanSupplier ownsWriter, Duration checkInterval, Closer closer) {
		if (checkInterval.isNegative() || checkInterval.isZero()) {
			throw new IllegalArgumentException("The check interval must be positive: " + checkInterval);
		}
		this.clock = clock;
		this.ownsWriter = ownsWriter;
		this.checkInterval = checkInterval;
		this.closer = closer;
		this.timer = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("scheduled-close").daemon()
			.factory());
	}

	@Override
	public synchronized void arm(UUID simulationId, Instant closesAt) {
		Target next = simulationId == null || closesAt == null ? null : new Target(simulationId, closesAt);
		if (next == null ? target == null : next.equals(target)) {
			return;
		}
		if (pending != null) {
			pending.cancel(false);
			pending = null;
		}
		target = next;
		if (next != null) {
			LOG.info("The market of simulation {} closes at {}", next.simulationId(), next.closesAt());
			later(next, Duration.ZERO);
		}
	}

	/** What is armed now, for tests and diagnostics: the close time, or null for none. */
	public synchronized Instant closesAt() {
		return target == null ? null : target.closesAt();
	}

	private synchronized void check(Target armed) {
		if (!armed.equals(target)) {
			return;
		}
		Duration left = Duration.between(clock.instant(), armed.closesAt());
		if (left.compareTo(Duration.ZERO) > 0) {
			later(armed, left.compareTo(checkInterval) < 0 ? left : checkInterval);
			return;
		}
		if (!ownsWriter.getAsBoolean()) {
			complainOnce(armed, "this process does not own the writer, so it does not close the market", null);
			later(armed, checkInterval);
			return;
		}
		CompletableFuture<Optional<MarketClosing>> close;
		try {
			close = closer.close(armed.simulationId(), armed.closesAt());
		}
		catch (RuntimeException ex) {
			complainOnce(armed, "the close could not be submitted; asking again every " + checkInterval, ex);
			later(armed, checkInterval);
			return;
		}
		close.whenComplete((closing, failure) -> closed(armed, closing, failure));
	}

	private synchronized void closed(Target armed, Optional<MarketClosing> closing, Throwable failure) {
		if (failure == null) {
			closing.ifPresent((result) -> LOG.info("The market closed at its close time, {}, at sequence {}",
					armed.closesAt(), result.commandSequence()));
			// Closed now, or by an operator first, or no longer this simulation's time: either way nothing is left to
			// ask for, and the engine arms whatever is next.
			if (armed.equals(target)) {
				target = null;
			}
			return;
		}
		Throwable cause = failure instanceof java.util.concurrent.CompletionException ? failure.getCause() : failure;
		if (cause instanceof WriterHaltException || cause instanceof CloseUnfinishedException) {
			// Logged where the writer halted; asking again gets only the halt again.
			LOG.error("The market's close at its close time, {}, halted the writer; the next start closes the market, "
					+ "or finishes its close, before it takes a command", armed.closesAt());
			if (armed.equals(target)) {
				target = null;
			}
			return;
		}
		LOG.error("The market's close at its close time, {}, failed; asking again in {}", armed.closesAt(),
				checkInterval, cause);
		if (armed.equals(target)) {
			later(armed, checkInterval);
		}
	}

	private void complainOnce(Target armed, String what, Throwable cause) {
		if (!armed.equals(complained)) {
			complained = armed;
			LOG.warn("The market's close time, {}, has come, but {}", armed.closesAt(), what, cause);
		}
	}

	private void later(Target armed, Duration delay) {
		try {
			pending = timer.schedule(() -> check(armed), delay.toNanos(), TimeUnit.NANOSECONDS);
		}
		catch (java.util.concurrent.RejectedExecutionException ex) {
			// Shut down with the application; the next start arms it again.
			pending = null;
		}
	}

	@Override
	public void close() {
		timer.shutdownNow();
	}

}
