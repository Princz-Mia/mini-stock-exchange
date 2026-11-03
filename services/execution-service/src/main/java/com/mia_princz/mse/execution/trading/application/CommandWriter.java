package com.mia_princz.mse.execution.trading.application;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The single writer: every trading mutation runs here, one at a time, in admission order, on one dedicated platform
 * thread. Admission never blocks: a full queue is refused at once, so the caller can answer "busy" knowing the command
 * was not queued. A command that has been admitted always runs; giving up on its future does not cancel it.
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

	private enum State {

		RUNNING, CLOSING, HALTED

	}

	private record Task<T>(Callable<T> command, CompletableFuture<T> future) {

		void run() throws Exception {
			future.complete(command.call());
		}

	}

	private final BlockingQueue<Task<?>> queue;

	private final Object admission = new Object();

	private final Thread thread;

	private volatile State state = State.RUNNING;

	/** Set when the grace period ran out; a command may swallow the interrupt, so the loop checks this too. */
	private volatile boolean abandoned;

	public CommandWriter(String name, int capacity) {
		this.queue = new ArrayBlockingQueue<>(capacity);
		this.thread = Thread.ofPlatform().name(name).daemon(false).start(this::loop);
	}

	public <T> CompletableFuture<T> submit(Callable<T> command) {
		Task<T> task = new Task<>(command, new CompletableFuture<>());
		synchronized (admission) {
			if (state != State.RUNNING) {
				throw new WriterStoppedException("Writer is " + state.name().toLowerCase());
			}
			if (!queue.offer(task)) {
				throw new WriterBusyException();
			}
		}
		return task.future();
	}

	/** Ready to take commands: running, not halted, and its thread alive. */
	public boolean isAvailable() {
		return state == State.RUNNING && thread.isAlive();
	}

	public int queueDepth() {
		return queue.size();
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
			Task<?> task;
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
			try {
				task.run();
			}
			catch (WriterHaltException ex) {
				halt(task, ex);
				return;
			}
			catch (Throwable ex) {
				task.future().completeExceptionally(ex);
			}
		}
	}

	private void halt(Task<?> failed, WriterHaltException cause) {
		synchronized (admission) {
			state = State.HALTED;
		}
		failed.future().completeExceptionally(cause);
		failQueued("Writer halted before this command ran");
	}

	private void failQueued(String message) {
		Task<?> task;
		while ((task = queue.poll()) != null) {
			task.future().completeExceptionally(new WriterStoppedException(message));
		}
	}

}
