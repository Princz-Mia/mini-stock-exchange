package com.mia_princz.mse.execution.trading.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.mia_princz.mse.execution.trading.application.CommandWriter.WriterBusyException;
import com.mia_princz.mse.execution.trading.application.CommandWriter.WriterHaltException;
import com.mia_princz.mse.execution.trading.application.CommandWriter.WriterStoppedException;

@Timeout(10)
class CommandWriterTest {

	private final List<CommandWriter> writers = new ArrayList<>();

	@AfterEach
	void closeWriters() throws InterruptedException {
		for (CommandWriter writer : writers) {
			writer.close(Duration.ofSeconds(1));
		}
	}

	@Test
	void runsEveryCommandOnOneThreadInAdmissionOrder() throws Exception {
		CommandWriter writer = writer(1_000);
		List<Integer> order = Collections.synchronizedList(new ArrayList<>());
		Set<Thread> threads = ConcurrentHashMap.newKeySet();
		List<CompletableFuture<Integer>> futures = new ArrayList<>();

		for (int i = 0; i < 500; i++) {
			int number = i;
			futures.add(writer.submit(() -> {
				order.add(number);
				threads.add(Thread.currentThread());
				return number;
			}));
		}
		CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get();

		assertThat(order).isSortedAccordingTo(Integer::compare).hasSize(500);
		assertThat(threads).singleElement().satisfies((thread) -> assertThat(thread.isVirtual()).isFalse());
	}

	@Test
	void commandsNeverOverlapEvenWhenSubmittedConcurrently() throws Exception {
		CommandWriter writer = writer(10_000);
		int[] counter = { 0 };
		List<CompletableFuture<Void>> futures = Collections.synchronizedList(new ArrayList<>());
		List<Thread> producers = new ArrayList<>();
		for (int p = 0; p < 8; p++) {
			producers.add(Thread.ofVirtual().start(() -> {
				for (int i = 0; i < 500; i++) {
					// An unsynchronized read-modify-write: it only adds up if commands never overlap.
					futures.add(writer.submit(() -> {
						counter[0] = counter[0] + 1;
						return null;
					}));
				}
			}));
		}
		for (Thread producer : producers) {
			producer.join();
		}
		CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get();

		assertThat(writer.submit(() -> counter[0]).get()).isEqualTo(4_000);
	}

	@Test
	void aFullQueueRefusesAtOnceWithoutQueueing() throws Exception {
		CommandWriter writer = writer(2);
		CountDownLatch running = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CompletableFuture<String> blocker = writer.submit(() -> {
			running.countDown();
			release.await();
			return "blocker";
		});
		running.await();
		CompletableFuture<String> first = writer.submit(() -> "first");
		CompletableFuture<String> second = writer.submit(() -> "second");

		assertThatThrownBy(() -> writer.submit(() -> "refused")).isInstanceOf(WriterBusyException.class);
		assertThat(writer.queueDepth()).isEqualTo(2);

		release.countDown();
		assertThat(List.of(blocker.get(), first.get(), second.get())).containsExactly("blocker", "first", "second");
	}

	@Test
	void aFailingCommandFailsOnlyItself() throws Exception {
		CommandWriter writer = writer(10);

		CompletableFuture<Object> failing = writer.submit(() -> {
			throw new IllegalStateException("domain failure");
		});
		CompletableFuture<String> next = writer.submit(() -> "still running");

		assertThatThrownBy(failing::get).isInstanceOf(ExecutionException.class)
			.hasCauseInstanceOf(IllegalStateException.class);
		assertThat(next.get()).isEqualTo("still running");
		assertThat(writer.isAvailable()).isTrue();
	}

	@Test
	void anUncertainStateHaltsTheWriterAndFailsQueuedCommands() throws Exception {
		CommandWriter writer = writer(10);
		CountDownLatch release = new CountDownLatch(1);
		CompletableFuture<Object> halting = writer.submit(() -> {
			release.await();
			throw new WriterHaltException("failure after commit", new RuntimeException());
		});
		CompletableFuture<String> queued = writer.submit(() -> "never runs");

		release.countDown();

		assertThatThrownBy(halting::get).hasCauseInstanceOf(WriterHaltException.class);
		assertThatThrownBy(queued::get).hasCauseInstanceOf(WriterStoppedException.class);
		assertThatThrownBy(() -> writer.submit(() -> "refused")).isInstanceOf(WriterStoppedException.class);
		assertThat(writer.isAvailable()).isFalse();
	}

	@Test
	void closingDrainsAdmittedCommandsThenRefusesNewOnes() throws Exception {
		CommandWriter writer = writer(10);
		CountDownLatch release = new CountDownLatch(1);
		CompletableFuture<String> slow = writer.submit(() -> {
			release.await();
			return "slow";
		});
		CompletableFuture<String> queued = writer.submit(() -> "queued");
		Thread closer = Thread.ofVirtual().start(() -> {
			try {
				writer.close(Duration.ofSeconds(5));
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		});
		while (writer.isAvailable()) {
			Thread.onSpinWait();
		}

		assertThatThrownBy(() -> writer.submit(() -> "refused")).isInstanceOf(WriterStoppedException.class);
		release.countDown();
		closer.join();

		assertThat(slow.get()).isEqualTo("slow");
		assertThat(queued.get()).isEqualTo("queued");
	}

	@Test
	void closingFailsWhatCannotFinishWithinTheGracePeriod() throws Exception {
		CommandWriter writer = writer(10);
		CountDownLatch running = new CountDownLatch(1);
		CompletableFuture<String> stuck = writer.submit(() -> {
			running.countDown();
			new CountDownLatch(1).await();
			return "never";
		});
		CompletableFuture<String> queued = writer.submit(() -> "queued");
		running.await();

		writer.close(Duration.ofMillis(100));

		assertThatThrownBy(() -> queued.get(1, TimeUnit.SECONDS)).as("must not run after the deadline")
			.hasCauseInstanceOf(WriterStoppedException.class);
		assertThatThrownBy(() -> stuck.get(1, TimeUnit.SECONDS)).as("interrupted")
			.hasCauseInstanceOf(InterruptedException.class);
	}

	private CommandWriter writer(int capacity) {
		CommandWriter writer = new CommandWriter("test-writer", capacity);
		writers.add(writer);
		return writer;
	}

}
