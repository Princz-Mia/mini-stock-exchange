package com.mia_princz.mse.execution.trading.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.mia_princz.mse.execution.MovableClock;
import com.mia_princz.mse.execution.trading.application.CommandWriter.WriterHaltException;
import com.mia_princz.mse.execution.trading.application.MarketClosing;

/**
 * When the schedule asks the engine to close, and when it does not, with a clock the test moves and a closer that
 * records. Whether the engine then closes, once, is {@code ScheduledCloseIT}'s.
 */
class ScheduledCloseTest {

	private static final Instant NOW = Instant.parse("2031-03-04T05:06:07.123456Z");

	private static final Instant CLOSES_AT = NOW.plus(Duration.ofHours(1));

	/** Short, so that a moved clock is noticed at once; how long nothing happens is waited for in a few of these. */
	private static final Duration INTERVAL = Duration.ofMillis(20);

	private static final MarketClosing CLOSED = new MarketClosing(MarketClosing.Status.CLOSED, "CLOSED", 9, 0, 0);

	private record Asked(UUID simulationId, Instant closesAt, String thread) {
	}

	private final MovableClock clock = new MovableClock(NOW);

	private final List<Asked> asked = new CopyOnWriteArrayList<>();

	private final AtomicBoolean owns = new AtomicBoolean(true);

	/** What the closer answers each time it is asked; closed, unless a test says otherwise. */
	private volatile Supplier<CompletableFuture<Optional<MarketClosing>>> answer = () -> CompletableFuture
		.completedFuture(Optional.of(CLOSED));

	private final ScheduledClose schedule = new ScheduledClose(clock, owns::get, INTERVAL, (simulation, closesAt) -> {
		asked.add(new Asked(simulation, closesAt, Thread.currentThread().getName()));
		return answer.get();
	});

	private final UUID simulation = UUID.randomUUID();

	@AfterEach
	void stop() {
		schedule.close();
	}

	@Test
	void itAsksOnceWhenTheClockReachesTheCloseTimeAndNotBefore() throws Exception {
		schedule.arm(simulation, CLOSES_AT);
		assertThat(schedule.closesAt()).isEqualTo(CLOSES_AT);
		quietFor(Duration.ofMillis(150));
		clock.set(CLOSES_AT.minusNanos(1_000));
		quietFor(Duration.ofMillis(150));

		clock.set(CLOSES_AT);
		await(() -> asked.size() == 1);
		Asked first = asked.getFirst();
		assertThat(first.simulationId()).isEqualTo(simulation);
		assertThat(first.closesAt()).isEqualTo(CLOSES_AT);
		assertThat(first.thread()).as("its own thread, not Spring's scheduling one that runs the figures")
			.isEqualTo("scheduled-close");
		await(() -> schedule.closesAt() == null);
		askedAfter(Duration.ofMillis(150), 1);
	}

	@Test
	void aProcessThatDoesNotOwnTheWriterNeverAsks() throws Exception {
		owns.set(false);
		schedule.arm(simulation, CLOSES_AT);
		clock.set(CLOSES_AT.plusSeconds(60));
		quietFor(Duration.ofMillis(300));
		assertThat(schedule.closesAt()).as("still armed, only not this process's to close").isEqualTo(CLOSES_AT);

		owns.set(true);
		await(() -> asked.size() == 1);
	}

	@Test
	void aCloseTimeDisarmedOrReplacedIsNotAskedFor() throws Exception {
		schedule.arm(simulation, CLOSES_AT);
		schedule.arm(simulation, null);
		assertThat(schedule.closesAt()).isNull();
		clock.set(CLOSES_AT.plusSeconds(1));
		quietFor(Duration.ofMillis(150));

		clock.set(NOW);
		UUID next = UUID.randomUUID();
		Instant later = CLOSES_AT.plus(Duration.ofHours(2));
		schedule.arm(simulation, CLOSES_AT);
		schedule.arm(next, later);
		clock.set(CLOSES_AT.plusSeconds(1));
		quietFor(Duration.ofMillis(150));
		clock.set(later);
		await(() -> asked.size() == 1);
		assertThat(asked.getFirst().simulationId()).isEqualTo(next);
		assertThat(asked.getFirst().closesAt()).isEqualTo(later);
	}

	@Test
	void aCloseThatFailedOrCouldNotBeSubmittedIsAskedForAgain() throws Exception {
		List<Supplier<CompletableFuture<Optional<MarketClosing>>>> answers = new CopyOnWriteArrayList<>(List.of(
				() -> {
					throw new IllegalStateException("the writer's queue is full");
				}, () -> CompletableFuture.failedFuture(new IllegalStateException("rolled back")),
				() -> CompletableFuture.completedFuture(Optional.of(CLOSED))));
		answer = () -> answers.removeFirst().get();
		schedule.arm(simulation, CLOSES_AT);
		clock.set(CLOSES_AT);
		await(() -> asked.size() == 3);
		await(() -> schedule.closesAt() == null);
		askedAfter(Duration.ofMillis(150), 3);
	}

	@Test
	void aCloseThatHaltedTheWriterIsNotAskedForAgain() throws Exception {
		answer = () -> CompletableFuture
			.failedFuture(new WriterHaltException("halted", new IllegalStateException("out of step")));
		schedule.arm(simulation, CLOSES_AT);
		clock.set(CLOSES_AT);
		await(() -> asked.size() == 1);
		askedAfter(Duration.ofMillis(200), 1);
		assertThat(schedule.closesAt()).isNull();
	}

	@Test
	void anAnswerOfNothingToCloseEndsTheSchedule() throws Exception {
		answer = () -> CompletableFuture.completedFuture(Optional.empty());
		schedule.arm(simulation, CLOSES_AT);
		clock.set(CLOSES_AT);
		await(() -> schedule.closesAt() == null);
		askedAfter(Duration.ofMillis(150), 1);
	}

	private void quietFor(Duration duration) throws InterruptedException {
		askedAfter(duration, 0);
	}

	/** Waits, then checks the closer has been asked this many times in all. */
	private void askedAfter(Duration duration, int times) throws InterruptedException {
		Thread.sleep(duration);
		assertThat(asked).hasSize(times);
	}

	private static void await(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		while (!condition.getAsBoolean()) {
			assertThat(System.nanoTime()).as("waited five seconds").isLessThan(deadline);
			Thread.sleep(5);
		}
	}

}
