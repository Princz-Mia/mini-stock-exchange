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
		assertThat(writer.queueCapacity()).as("full or empty, the capacity it was made with").isEqualTo(2);

		release.countDown();
		assertThat(List.of(blocker.get(), first.get(), second.get())).containsExactly("blocker", "first", "second");
		assertThat(writer.queueCapacity()).isEqualTo(2);
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

	/**
	 * Commands queued together for one processor run together, up to the limit; any other command ends a run; the
	 * order is the order of admission throughout.
	 */
	@Test
	void commandsQueuedTogetherRunTogetherUpToTheLimitAndAnythingElseEndsARun() throws Exception {
		List<Integer> sizes = Collections.synchronizedList(new ArrayList<>());
		CommandWriter writer = writer(100, 2, sizes::add);
		Recorder processor = new Recorder();
		CountDownLatch release = new CountDownLatch(1);
		writer.submit(() -> release.await(5, TimeUnit.SECONDS));
		List<CompletableFuture<Object>> futures = new ArrayList<>();
		for (String name : List.of("p1", "p2", "p3")) {
			futures.add(writer.submitInRun(processor, name));
		}
		futures.add(writer.submit(() -> "control"));
		for (String name : List.of("p4", "p5")) {
			futures.add(writer.submitInRun(processor, name));
		}

		release.countDown();
		CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get();

		assertThat(processor.runs).containsExactly(List.of("p1", "p2"), List.of("p3"), List.of("p4", "p5"));
		assertThat(futures).extracting(CompletableFuture::join)
			.containsExactly("done p1", "done p2", "done p3", "control", "done p4", "done p5");
		assertThat(sizes).as("each run's size, and only runs").containsExactly(2, 1, 2);
	}

	/**
	 * A run never waits for more commands: one queued alone runs alone, at once, whatever the limit, and a command
	 * submitted a little later is a run of its own rather than one the first waited for.
	 */
	@Test
	void aRunTakesOnlyWhatIsQueuedAndNeverWaitsForMore() throws Exception {
		CommandWriter writer = writer(100, 32);
		Recorder processor = new Recorder();

		CompletableFuture<Object> alone = writer.submitInRun(processor, "alone");
		Thread.sleep(200);
		CompletableFuture<Object> later = writer.submitInRun(processor, "later");

		assertThat(alone.get(1, TimeUnit.SECONDS)).isEqualTo("done alone");
		assertThat(later.get(1, TimeUnit.SECONDS)).isEqualTo("done later");
		assertThat(processor.runs).containsExactly(List.of("alone"), List.of("later"));
	}

	/**
	 * A command of a run that halts the writer fails with the halt; the commands before it keep their results, those
	 * after it fail as the processor says, and once the run is done the writer halts and fails the queue.
	 */
	@Test
	void aHaltedOutcomeKeepsTheRunsOtherOutcomesThenHaltsTheWriter() throws Exception {
		List<Integer> sizes = Collections.synchronizedList(new ArrayList<>());
		CommandWriter writer = writer(100, 4, sizes::add);
		WriterHaltException halt = new WriterHaltException("state unknown", new RuntimeException());
		CommandWriter.RunProcessor<String> processor = (commands) -> List.of(CommandWriter.RunOutcome.done("kept"),
				CommandWriter.RunOutcome.halted(halt),
				CommandWriter.RunOutcome.failed(new WriterStoppedException("halted before this command ran")));
		CountDownLatch release = new CountDownLatch(1);
		writer.submit(() -> release.await(5, TimeUnit.SECONDS));
		CompletableFuture<Object> before = writer.submitInRun(processor, "before");
		CompletableFuture<Object> halting = writer.submitInRun(processor, "halting");
		CompletableFuture<Object> after = writer.submitInRun(processor, "after");
		CompletableFuture<String> queued = writer.submit(() -> "queued");

		release.countDown();

		assertThat(before.get()).isEqualTo("kept");
		assertThatThrownBy(halting::get).hasCause(halt);
		assertThatThrownBy(after::get).hasCauseInstanceOf(WriterStoppedException.class);
		assertThatThrownBy(queued::get).hasCauseInstanceOf(WriterStoppedException.class);
		assertThat(writer.isAvailable()).isFalse();
		assertThat(sizes).containsExactly(3);
	}

	/** Commands for different processors never share a run, so each processor sees only its own. */
	@Test
	void commandsForDifferentProcessorsNeverShareARun() throws Exception {
		CommandWriter writer = writer(100, 8);
		Recorder first = new Recorder();
		Recorder second = new Recorder();
		CountDownLatch release = new CountDownLatch(1);
		writer.submit(() -> release.await(5, TimeUnit.SECONDS));
		List<CompletableFuture<Object>> futures = new ArrayList<>();
		futures.add(writer.submitInRun(first, "a1"));
		futures.add(writer.submitInRun(second, "b1"));
		futures.add(writer.submitInRun(second, "b2"));
		futures.add(writer.submitInRun(first, "a2"));

		release.countDown();
		CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get();

		assertThat(first.runs).containsExactly(List.of("a1"), List.of("a2"));
		assertThat(second.runs).containsExactly(List.of("b1", "b2"));
	}

	/** By default every run holds one command, as before runs existed. */
	@Test
	void byDefaultEveryRunHoldsOneCommand() throws Exception {
		CommandWriter writer = writer(100);
		Recorder processor = new Recorder();
		CountDownLatch release = new CountDownLatch(1);
		writer.submit(() -> release.await(5, TimeUnit.SECONDS));
		List<CompletableFuture<Object>> futures = new ArrayList<>();
		for (String name : List.of("p1", "p2", "p3")) {
			futures.add(writer.submitInRun(processor, name));
		}

		release.countDown();
		CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get();

		assertThat(writer.maxRun()).isEqualTo(1);
		assertThat(processor.runs).containsExactly(List.of("p1"), List.of("p2"), List.of("p3"));
	}

	/** A command of a run that fails fails alone; the others complete with their results. */
	@Test
	void aFailedCommandInARunFailsAlone() throws Exception {
		CommandWriter writer = writer(100, 4);
		CommandWriter.RunProcessor<String> processor = (commands) -> commands.stream()
			.map((command) -> command.equals("bad") ? CommandWriter.RunOutcome.failed(new IllegalStateException(command))
					: CommandWriter.RunOutcome.done("done " + command))
			.toList();
		CountDownLatch release = new CountDownLatch(1);
		writer.submit(() -> release.await(5, TimeUnit.SECONDS));
		CompletableFuture<Object> good = writer.submitInRun(processor, "good");
		CompletableFuture<Object> bad = writer.submitInRun(processor, "bad");
		CompletableFuture<Object> after = writer.submitInRun(processor, "after");

		release.countDown();

		assertThat(good.get()).isEqualTo("done good");
		assertThatThrownBy(bad::get).hasCauseInstanceOf(IllegalStateException.class);
		assertThat(after.get()).isEqualTo("done after");
	}

	/** A halt in a run fails every command of the run, then every queued command, and stops admission. */
	@Test
	void aHaltInARunFailsTheWholeRunThenTheQueue() throws Exception {
		CommandWriter writer = writer(100, 2);
		CommandWriter.RunProcessor<String> processor = (commands) -> {
			throw new WriterHaltException("state unknown", new RuntimeException());
		};
		CountDownLatch release = new CountDownLatch(1);
		writer.submit(() -> release.await(5, TimeUnit.SECONDS));
		CompletableFuture<Object> first = writer.submitInRun(processor, "first");
		CompletableFuture<Object> second = writer.submitInRun(processor, "second");
		CompletableFuture<String> queued = writer.submit(() -> "queued");

		release.countDown();

		assertThatThrownBy(first::get).hasCauseInstanceOf(WriterHaltException.class);
		assertThatThrownBy(second::get).hasCauseInstanceOf(WriterHaltException.class);
		assertThatThrownBy(queued::get).hasCauseInstanceOf(WriterStoppedException.class);
		assertThatThrownBy(() -> writer.submitInRun(processor, "refused")).isInstanceOf(WriterStoppedException.class);
	}

	/** A processor that answers for the wrong number of commands fails its run, and the writer goes on. */
	@Test
	void aBrokenProcessorFailsItsRunAndTheWriterGoesOn() throws Exception {
		CommandWriter writer = writer(100, 4);
		CountDownLatch release = new CountDownLatch(1);
		writer.submit(() -> release.await(5, TimeUnit.SECONDS));
		CommandWriter.RunProcessor<String> shortOfOne = (commands) -> List.of(CommandWriter.RunOutcome.done("one"));
		CompletableFuture<Object> a = writer.submitInRun(shortOfOne, "a");
		CompletableFuture<Object> b = writer.submitInRun(shortOfOne, "b");

		release.countDown();

		assertThatThrownBy(a::get).hasCauseInstanceOf(IllegalStateException.class);
		assertThatThrownBy(b::get).hasCauseInstanceOf(IllegalStateException.class);
		assertThat(writer.submit(() -> "still running").get()).isEqualTo("still running");
	}

	@Test
	void aRunLimitOutsideOneToTheCapacityIsRefused() {
		assertThatThrownBy(() -> new CommandWriter("w", 10, 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CommandWriter("w", 10, 11)).isInstanceOf(IllegalArgumentException.class);
	}

	/** Records each run it is given and answers "done" for each command. */
	private static final class Recorder implements CommandWriter.RunProcessor<String> {

		final List<List<String>> runs = Collections.synchronizedList(new ArrayList<>());

		@Override
		public List<CommandWriter.RunOutcome> process(List<String> commands) {
			runs.add(List.copyOf(commands));
			return commands.stream().map((command) -> CommandWriter.RunOutcome.done("done " + command)).toList();
		}

	}

	private CommandWriter writer(int capacity) {
		CommandWriter writer = new CommandWriter("test-writer", capacity);
		writers.add(writer);
		return writer;
	}

	private CommandWriter writer(int capacity, int maxRun) {
		CommandWriter writer = new CommandWriter("test-writer", capacity, maxRun);
		writers.add(writer);
		return writer;
	}

	private CommandWriter writer(int capacity, int maxRun, java.util.function.IntConsumer sizes) {
		CommandWriter writer = new CommandWriter("test-writer", capacity, maxRun, sizes);
		writers.add(writer);
		return writer;
	}

}
