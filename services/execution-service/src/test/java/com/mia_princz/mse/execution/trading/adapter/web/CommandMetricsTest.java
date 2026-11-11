package com.mia_princz.mse.execution.trading.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import com.mia_princz.mse.execution.trading.application.CommandWriter;
import com.mia_princz.mse.execution.trading.application.CommandWriter.WriterBusyException;

class CommandMetricsTest {

	private static final long MILLI = 1_000_000L;

	private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

	private final AtomicLong clock = new AtomicLong();

	private final CommandActivity activity = new CommandActivity(clock::get, CommandMetrics.P95_BUDGET);

	private final CommandMetrics metrics = new CommandMetrics(meters, clock::get, activity);

	@Test
	void aCommittedCommandIsTimedFromAdmissionToItsResult() throws Exception {
		CompletableFuture<String> result = new CompletableFuture<>();
		CompletableFuture<String> admitted = metrics.timed("place", () -> {
			clock.addAndGet(30 * MILLI);
			return result;
		}).submit();
		assertThat(meters.find("mse.commands").timers()).as("nothing recorded before the result").isEmpty();

		clock.addAndGet(90 * MILLI);
		result.complete("done");

		assertThat(admitted).isSameAs(result);
		assertThat(activity.lastMinute()).as("in the health window too, within the budget")
			.isEqualTo(new CommandActivity.Window(1, 0, 1, 0));
		Timer timer = timer("place", "committed");
		assertThat(timer.count()).isEqualTo(1);
		assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).as("admission included").isEqualTo(120);
	}

	@Test
	void aFailedCommandIsTimedAsFailed() throws Exception {
		CompletableFuture<String> result = new CompletableFuture<>();
		metrics.timed("cancel", () -> result).submit();
		clock.addAndGet(5 * MILLI);
		result.completeExceptionally(new IllegalStateException("rolled back"));

		assertThat(timer("cancel", "failed").totalTime(TimeUnit.MILLISECONDS)).isEqualTo(5);
		assertThat(activity.lastMinute()).isEqualTo(new CommandActivity.Window(0, 1, 1, 0));
		assertThat(meters.find("mse.commands").tag("outcome", "committed").timer()).isNull();
	}

	@Test
	void aResultAfterTheCallerGaveUpStillCountsItsRealDuration() throws Exception {
		CompletableFuture<String> result = new CompletableFuture<>();
		CompletableFuture<String> admitted = metrics.timed("place", () -> result).submit();
		assertThat(admitted.isDone()).isFalse();
		clock.addAndGet(7_000 * MILLI);
		result.complete("late");

		assertThat(timer("place", "committed").totalTime(TimeUnit.MILLISECONDS)).isEqualTo(7_000);
		assertThat(activity.lastMinute()).as("past the budget").isEqualTo(new CommandActivity.Window(1, 0, 0, 0));
	}

	@Test
	void aFullQueueIsCountedAsRefusedAndNeverTimed() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		try (CommandWriter writer = new CommandWriter("metrics-test", 1)) {
			CountDownLatch running = new CountDownLatch(1);
			writer.submit(() -> {
				running.countDown();
				return release.await(5, TimeUnit.SECONDS);
			});
			running.await();
			writer.submit(() -> "queued");

			assertThatThrownBy(() -> metrics.timed("place", () -> writer.submit(() -> "refused")).submit())
				.isInstanceOf(WriterBusyException.class);
			release.countDown();
		}
		assertThat(meters.get("mse.commands.refused").tag("command", "place").tag("reason", "busy").counter().count())
			.isEqualTo(1);
		assertThat(meters.find("mse.commands").timers()).isEmpty();
		assertThat(activity.lastMinute().refused()).as("in the health window too").isEqualTo(1);
	}

	@Test
	void otherRefusalsAreCountedByReason() {
		metrics.refused("place", "backlog");
		metrics.refused("place", "backlog");

		assertThat(meters.get("mse.commands.refused").tag("reason", "backlog").counter().count()).isEqualTo(2);
		assertThat(activity.lastMinute()).isEqualTo(new CommandActivity.Window(0, 0, 0, 2));
	}

	@Test
	void theHistogramHasABucketEdgeAtEachLatencyBudget() throws Exception {
		CompletableFuture<String> result = new CompletableFuture<>();
		metrics.timed("place", () -> result).submit();
		result.complete("done");

		double[] edges = Arrays.stream(timer("place", "committed").takeSnapshot().histogramCounts())
			.mapToDouble((bucket) -> bucket.bucket(TimeUnit.MILLISECONDS))
			.toArray();
		assertThat(edges).contains(150.0, 500.0);
		assertThat(CommandMetrics.P95_BUDGET).isEqualTo(Duration.ofMillis(150));
		assertThat(CommandMetrics.P99_BUDGET).isEqualTo(Duration.ofMillis(500));
	}

	private Timer timer(String command, String outcome) {
		return meters.get("mse.commands").tag("command", command).tag("outcome", outcome).timer();
	}

}
