package com.mia_princz.mse.execution.trading.application;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

/**
 * The single writer: every trading mutation runs here, one at a time, in admission order, on one dedicated platform
 * thread. Admission never blocks: a full queue is refused at once, so the caller can answer "busy" knowing the command
 * was not queued. A command that has been admitted always runs; giving up on its future does not cancel it.
 *
 * <p>
 * Commands submitted with {@link #submitInRun} may run together: when the writer takes one, it also takes the
 * commands behind it in the queue that go to the same {@link RunProcessor}, up to the run's limit, and hands them over
 * as one run. It never waits for more to arrive, and any other command ends the run, so the order is always the order
 * of admission. With a limit of one, every run holds a single command.
 */
public final class CommandWriter implements AutoCloseable {

	/** The queue is full. The command was not admitted and can be retried. */
	public static final class WriterBusyException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		WriterBusyException() {
			super("Command queue is full");
		}

	}

	/** The writer no longer admits commands, because it is closing or has halted. */
	public static final class WriterStoppedException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		WriterStoppedException(String message) {
			super(message);
		}

	}

	/**
	 * Thrown by a command when the writer's state can no longer be trusted, for example a failure after a database
	 * commit but before the in-memory book was updated. The writer stops and fails every queued command.
	 */
	public static final class WriterHaltException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		public WriterHaltException(String message, Throwable cause) {
			super(message, cause);
		}

	}

	/**
	 * Runs a run of commands taken together, in admission order, and returns one outcome for each, in the same order.
	 * A {@link RunOutcome#halted halted} outcome halts the writer once every command of the run has its outcome. A
	 * {@link WriterHaltException} thrown instead halts it and fails every command of the run; anything else thrown
	 * fails every command of the run, and the writer goes on.
	 */
	@FunctionalInterface
	public interface RunProcessor<C> {

		List<RunOutcome> process(List<C> commands);

	}

	/** What one command of a run came to: its result, or the failure its future completes with. */
	public sealed interface RunOutcome {

		static RunOutcome done(Object result) {
			return new Done(result);
		}

		static RunOutcome failed(Throwable failure) {
			return new Failed(failure);
		}

		/** The command that halted the writer: its future fails with the cause, and the writer halts after the run. */
		static RunOutcome halted(WriterHaltException cause) {
			return new Halted(cause);
		}

		record Done(Object result) implements RunOutcome {
		}

		record Failed(Throwable failure) implements RunOutcome {
		}

		record Halted(WriterHaltException cause) implements RunOutcome {
		}

	}

	private enum State {

		RUNNING, CLOSING, HALTED

	}

	private sealed interface Task {

		CompletableFuture<?> future();

	}

	private record Single<T>(Callable<T> command, CompletableFuture<T> future) implements Task {

		void run() throws Exception {
			future.complete(command.call());
		}

	}

	private record InRun<C>(RunProcessor<C> processor, C command, CompletableFuture<Object> future) implements Task {
	}

	private final BlockingQueue<Task> queue;

	private final int maxRun;

	/** Hears how many commands each run held, once it has been processed. */
	private final IntConsumer runs;

	private final Object admission = new Object();

	private final Thread thread;

	private volatile State state = State.RUNNING;

	/** Set when the grace period ran out; a command may swallow the interrupt, so the loop checks this too. */
	private volatile boolean abandoned;

	public CommandWriter(String name, int capacity) {
		this(name, capacity, 1);
	}

	/** Refuses a run limit outside one to the queue's capacity, before anything is built around it. */
	public static void checkRunLimit(int maxRun, int capacity) {
		if (maxRun < 1 || maxRun > capacity) {
			throw new IllegalArgumentException("A run holds from one command up to the queue's capacity: " + maxRun);
		}
	}

	/** A writer whose runs hold at most {@code maxRun} commands. */
	public CommandWriter(String name, int capacity, int maxRun) {
		this(name, capacity, maxRun, (size) -> {
		});
	}

	/** As above, with {@code runs} hearing each run's size once the run has been processed. */
	public CommandWriter(String name, int capacity, int maxRun, IntConsumer runs) {
		checkRunLimit(maxRun, capacity);
		this.queue = new ArrayBlockingQueue<>(capacity);
		this.maxRun = maxRun;
		this.runs = runs;
		this.thread = Thread.ofPlatform().name(name).daemon(false).start(this::loop);
	}

	public <T> CompletableFuture<T> submit(Callable<T> command) {
		Single<T> task = new Single<>(command, new CompletableFuture<>());
		admit(task);
		return task.future();
	}

	/**
	 * Admits a command that may run together with the commands queued next to it for the same processor. The future
	 * completes with the result the processor gives it, which must be a {@code T}.
	 */
	@SuppressWarnings("unchecked")
	public <C, T> CompletableFuture<T> submitInRun(RunProcessor<C> processor, C command) {
		InRun<C> task = new InRun<>(processor, command, new CompletableFuture<>());
		admit(task);
		return (CompletableFuture<T>) (CompletableFuture<?>) task.future();
	}

	/** The most commands one run holds. */
	public int maxRun() {
		return maxRun;
	}

	private void admit(Task task) {
		synchronized (admission) {
			if (state != State.RUNNING) {
				throw new WriterStoppedException("Writer is " + state.name().toLowerCase());
			}
			if (!queue.offer(task)) {
				throw new WriterBusyException();
			}
		}
	}

	/** Ready to take commands: running, not halted, and its thread alive. */
	public boolean isAvailable() {
		return state == State.RUNNING && thread.isAlive();
	}

	public int queueDepth() {
		return queue.size();
	}

	/** How many commands the queue holds before admission refuses more. */
	public int queueCapacity() {
		return queue.size() + queue.remainingCapacity();
	}

	/** Stops admission, lets admitted commands finish within the grace period, then fails whatever is left. */
	public void close(Duration grace) throws InterruptedException {
		synchronized (admission) {
			if (state == State.RUNNING) {
				state = State.CLOSING;
			}
		}
		if (!thread.join(grace)) {
			abandoned = true;
			thread.interrupt();
			thread.join();
		}
		failQueued("Writer closed before this command ran");
	}

	@Override
	public void close() throws InterruptedException {
		close(Duration.ofSeconds(10));
	}

	private void loop() {
		while (!abandoned) {
			Task task;
			try {
				task = queue.poll(50, TimeUnit.MILLISECONDS);
			}
			catch (InterruptedException ex) {
				return;
			}
			if (task == null) {
				if (state != State.RUNNING) {
					return;
				}
				continue;
			}
			boolean halted = switch (task) {
				case Single<?> single -> runSingle(single);
				case InRun<?> first -> runInRun(first);
			};
			if (halted) {
				return;
			}
		}
	}

	/** Runs one command; true if it halted the writer. */
	private boolean runSingle(Single<?> task) {
		try {
			task.run();
		}
		catch (WriterHaltException ex) {
			halt(List.of(task), ex);
			return true;
		}
		catch (Throwable ex) {
			task.future().completeExceptionally(ex);
		}
		return false;
	}

	/**
	 * Takes the commands queued behind {@code first} for the same processor, up to the run's limit, and runs them
	 * together; true if the run halted the writer. Only this thread takes from the queue, so the command it peeks at
	 * is the one it then takes.
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private boolean runInRun(InRun<?> first) {
		List<InRun<?>> run = new ArrayList<>();
		run.add(first);
		while (run.size() < maxRun && queue.peek() instanceof InRun<?> next && next.processor() == first.processor()) {
			run.add((InRun<?>) queue.poll());
		}
		List<Object> commands = new ArrayList<>(run.size());
		run.forEach((task) -> commands.add(task.command()));
		List<RunOutcome> outcomes;
		try {
			outcomes = ((RunProcessor) first.processor()).process(commands);
			if (outcomes == null || outcomes.size() != run.size()) {
				throw new IllegalStateException("A run of " + run.size() + " commands came back with "
						+ (outcomes == null ? "no" : outcomes.size()) + " outcomes");
			}
		}
		catch (WriterHaltException ex) {
			halt(run, ex);
			return true;
		}
		catch (Throwable ex) {
			run.forEach((task) -> task.future().completeExceptionally(ex));
			return false;
		}
		WriterHaltException halt = null;
		for (RunOutcome outcome : outcomes) {
			if (outcome instanceof RunOutcome.Halted halted) {
				halt = halted.cause();
			}
		}
		// Counted, and the writer marked halted, before any caller hears back, so whoever sees an answer sees both.
		runs.accept(run.size());
		if (halt != null) {
			synchronized (admission) {
				state = State.HALTED;
			}
		}
		for (int i = 0; i < run.size(); i++) {
			switch (outcomes.get(i)) {
				case RunOutcome.Done done -> run.get(i).future().complete(done.result());
				case RunOutcome.Failed failed -> run.get(i).future().completeExceptionally(failed.failure());
				case RunOutcome.Halted halted -> run.get(i).future().completeExceptionally(halted.cause());
			}
		}
		if (halt != null) {
			halt(List.of(), halt);
			return true;
		}
		return false;
	}

	/** Every command of the failed run fails with the cause, then every queued one. */
	private void halt(List<? extends Task> failed, WriterHaltException cause) {
		synchronized (admission) {
			state = State.HALTED;
		}
		failed.forEach((task) -> task.future().completeExceptionally(cause));
		failQueued("Writer halted before this command ran");
	}

	private void failQueued(String message) {
		Task task;
		while ((task = queue.poll()) != null) {
			task.future().completeExceptionally(new WriterStoppedException(message));
		}
	}

}
