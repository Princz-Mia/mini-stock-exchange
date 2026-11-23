package com.mia_princz.mse.execution.trading.adapter;

import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import com.mia_princz.mse.execution.trading.application.WriterTimings;

/**
 * The writer's timings as timers: {@code mse.writer.queue.wait}, {@code mse.writer.begin}, {@code mse.writer.work},
 * {@code mse.writer.commit} and {@code mse.writer.apply}, each tagged {@code command} ({@code place}, {@code cancel},
 * {@code other}, {@code run} for a transaction of several orders and cancels together, or {@code close} for each
 * transaction of a market's close, which cancels many orders at once) and published with a histogram, so the time a
 * command spends on the writer can be split; and {@code mse.writer.run.fallbacks}, the runs that failed without
 * halting and ran again one by one.
 */
public final class MicrometerWriterTimings implements WriterTimings {

	static final List<String> COMMANDS = List.of("place", "cancel", "other", "run", "close");

	private final Map<Phase, Map<String, Timer>> timers = new EnumMap<>(Phase.class);

	private final Counter fallbacks;

	public MicrometerWriterTimings(MeterRegistry meters) {
		fallbacks = Counter.builder("mse.writer.run.fallbacks")
			.description("Runs of orders and cancels that failed without halting and ran again one by one")
			.baseUnit("runs")
			.register(meters);
		for (Phase phase : Phase.values()) {
			Map<String, Timer> byCommand = new HashMap<>();
			for (String command : COMMANDS) {
				byCommand.put(command, Timer.builder(name(phase))
					.description(description(phase))
					.tag("command", command)
					.publishPercentileHistogram()
					.minimumExpectedValue(Duration.ofNanos(10_000))
					.maximumExpectedValue(Duration.ofSeconds(5))
					.register(meters));
			}
			timers.put(phase, byCommand);
		}
	}

	@Override
	public void record(Phase phase, String command, long nanos) {
		Timer timer = timers.get(phase).get(command);
		if (timer != null && nanos >= 0) {
			timer.record(nanos, TimeUnit.NANOSECONDS);
		}
	}

	@Override
	public void runFellBack(int commands) {
		fallbacks.increment();
	}

	static String name(Phase phase) {
		return switch (phase) {
			case QUEUE_WAIT -> "mse.writer.queue.wait";
			case BEGIN -> "mse.writer.begin";
			case WORK -> "mse.writer.work";
			case COMMIT -> "mse.writer.commit";
			case APPLY -> "mse.writer.apply";
		};
	}

	private static String description(Phase phase) {
		return switch (phase) {
			case QUEUE_WAIT -> "From admission until the writer starts the command";
			case BEGIN -> "Opening the command's transaction, a pooled connection included";
			case WORK -> "The command's statements, inside its transaction";
			case COMMIT -> "The command's commit, until the database answers";
			case APPLY -> "Applying a committed command to the in-memory book";
		};
	}

}
