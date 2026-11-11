package com.mia_princz.mse.execution.trading.adapter.web;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.mia_princz.mse.execution.trading.application.CommandWriter.WriterBusyException;

/**
 * How long trading commands take from admission to their committed result, and how many are refused before admission.
 * The timer starts before the command is offered to the writer and stops when its result is final, whether or not the
 * caller is still waiting, so a timed-out request still counts its real duration. A refused command is counted, never
 * timed. Tags are bounded: the command and its outcome, no account, order or key.
 */
@Component
class CommandMetrics {

	/** The trading latency budgets, so the histogram has a bucket edge exactly at each. */
	static final Duration P95_BUDGET = Duration.ofMillis(150);

	static final Duration P99_BUDGET = Duration.ofMillis(500);

	private final MeterRegistry meters;

	private final LongSupplier nanoTime;

	private final CommandActivity activity;

	@Autowired
	CommandMetrics(MeterRegistry meters, CommandActivity activity) {
		this(meters, System::nanoTime, activity);
	}

	CommandMetrics(MeterRegistry meters, LongSupplier nanoTime, CommandActivity activity) {
		this.meters = meters;
		this.nanoTime = nanoTime;
		this.activity = activity;
	}

	/** The same submission, timed from admission to result; a full queue is counted as refused and rethrown. */
	<T> CommandCalls.Submission<T> timed(String command, CommandCalls.Submission<T> submission) {
		return () -> {
			long start = nanoTime.getAsLong();
			CompletableFuture<T> future;
			try {
				future = submission.submit();
			}
			catch (WriterBusyException ex) {
				refused(command, "busy");
				throw ex;
			}
			future.whenComplete((result, failure) -> {
				long took = nanoTime.getAsLong() - start;
				timer(command, failure == null ? "committed" : "failed").record(took, TimeUnit.NANOSECONDS);
				activity.finished(failure == null, Duration.ofNanos(took));
			});
			return future;
		};
	}

	/** A command turned away before admission, for a reason such as a full queue or the publication backlog. */
	void refused(String command, String reason) {
		activity.refused();
		Counter.builder("mse.commands.refused")
			.description("Trading commands refused before admission")
			.tag("command", command)
			.tag("reason", reason)
			.register(meters)
			.increment();
	}

	private Timer timer(String command, String outcome) {
		return Timer.builder("mse.commands")
			.description("Trading commands from admission to committed result")
			.tag("command", command)
			.tag("outcome", outcome)
			.publishPercentileHistogram()
			.serviceLevelObjectives(P95_BUDGET, P99_BUDGET)
			.minimumExpectedValue(Duration.ofMillis(1))
			.maximumExpectedValue(Duration.ofSeconds(10))
			.register(meters);
	}

}
