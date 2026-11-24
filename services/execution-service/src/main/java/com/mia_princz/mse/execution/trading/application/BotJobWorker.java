package com.mia_princz.mse.execution.trading.application;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mia_princz.mse.execution.trading.application.CommandWriter.WriterBusyException;
import com.mia_princz.mse.execution.trading.application.CommandWriter.WriterHaltException;
import com.mia_princz.mse.execution.trading.application.CommandWriter.WriterStoppedException;

/**
 * Makes bot jobs' parts on a thread of its own, one part at a time, each submitted to the writer as a command and
 * awaited before the next: other commands queue between parts and wait for one part at most, never for the job. The
 * thread also hands each part's tokens over, so the writer holds only the database work.
 * <p>
 * A part that fails and rolls back is tried again a few times with a short wait, under the same key, so one that
 * committed after all is answered from its stored answer. If it still fails, the job is recorded as failed, with the
 * bots made so far kept. A busy writer is waited for. A writer that has stopped or halted ends the work here; the job
 * stays running in the database, and the next start takes it up again from its next part.
 */
public final class BotJobWorker implements BotJobRunner, AutoCloseable {

	/** How many times a part that failed is tried before the job is recorded as failed. */
	static final int ATTEMPTS = 3;

	private static final long RETRY_PAUSE_MILLIS = 200;

	private static final int REASON_LENGTH = 500;

	private static final Logger LOG = LoggerFactory.getLogger(BotJobWorker.class);

	/** Hears of each part that committed, on the worker's thread, before the next part is made. */
	@FunctionalInterface
	public interface Listener {

		Listener NONE = (job) -> {
		};

		void partMade(BotJob job);

	}

	private final Supplier<TradingEngine> engine;

	private final Duration partWait;

	private final TradingEngine.RetryPause pause;

	private final Listener listener;

	private final ExecutorService thread = Executors
		.newSingleThreadExecutor(Thread.ofPlatform().name("bot-jobs").daemon().factory());

	/** The jobs being made or queued to be: a job is made by one loop at a time. */
	private final Set<UUID> taken = ConcurrentHashMap.newKeySet();

	private volatile boolean closed;

	/**
	 * {@code engine} is looked up when the first job runs, since the engine is built with this as its runner.
	 * {@code partWait} is how long a part's answer is awaited before it is asked for again under its key.
	 */
	public BotJobWorker(Supplier<TradingEngine> engine, Duration partWait, TradingEngine.RetryPause pause,
			Listener listener) {
		this.engine = engine;
		this.partWait = partWait;
		this.pause = pause;
		this.listener = listener;
	}

	public BotJobWorker(Supplier<TradingEngine> engine, Duration partWait) {
		this(engine, partWait, Thread::sleep, Listener.NONE);
	}

	@Override
	public void run(BotJob job) {
		if (closed || job.state() != BotJob.State.RUNNING || !taken.add(job.id())) {
			return;
		}
		try {
			thread.execute(() -> {
				try {
					make(job);
				}
				catch (InterruptedException ex) {
					// Closed while waiting to try a part again: the next start takes the job up again.
					Thread.currentThread().interrupt();
				}
				catch (RuntimeException | Error ex) {
					LOG.error("Bot job {} stopped being made; it stays running, and the next start takes it up again",
							job.id(), ex);
				}
				finally {
					taken.remove(job.id());
				}
			});
		}
		catch (RejectedExecutionException ex) {
			// Closed with the application: the next start takes the job up again.
			taken.remove(job.id());
		}
	}

	private void make(BotJob first) throws InterruptedException {
		BotJob job = first;
		int failures = 0;
		while (!closed && job.state() == BotJob.State.RUNNING && job.nextPart().isPresent()) {
			BotJobResult answer;
			try {
				answer = await(engine.get().botJobPart(job));
			}
			catch (InterruptedException ex) {
				return;
			}
			catch (RuntimeException ex) {
				Throwable cause = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
				if (closed) {
					return;
				}
				if (cause instanceof WriterStoppedException || cause instanceof WriterHaltException) {
					LOG.warn("The writer stopped while bot job {} was being made, after {} of {} bots; the next start "
							+ "takes the job up again", job.id(), job.createdTotal(), job.total());
					return;
				}
				if (cause instanceof WriterBusyException) {
					pause.pause(RETRY_PAUSE_MILLIS);
					continue;
				}
				failures++;
				if (failures >= ATTEMPTS || cause instanceof IdempotencyConflictException) {
					LOG.error("Part {} of bot job {} failed {} times; the job stops with {} of {} bots made",
							job.partsDone(), job.id(), failures, job.createdTotal(), job.total(), cause);
					fail(job, cause);
					return;
				}
				LOG.warn("Part {} of bot job {} failed; trying it again (attempt {} of {})", job.partsDone(), job.id(),
						failures, ATTEMPTS, cause);
				pause.pause(RETRY_PAUSE_MILLIS * failures);
				continue;
			}
			if (answer.status() != BotJobResult.Status.PART_CREATED) {
				// Stopped, or failed by the part itself, or no longer this simulation's: nothing is left to make here.
				LOG.info("Bot job {} ends here: its part was answered {}", job.id(), answer.status());
				return;
			}
			failures = 0;
			job = answer.job();
			listener.partMade(job);
		}
	}

	/** A part's answer; a wait that runs out is a failure, and the part is asked for again under its key. */
	private BotJobResult await(CompletableFuture<BotJobResult> part) throws InterruptedException {
		try {
			return part.get(partWait.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (ExecutionException ex) {
			throw new CompletionException(ex.getCause());
		}
		catch (TimeoutException ex) {
			throw new CompletionException(new IllegalStateException("No answer from the writer within " + partWait, ex));
		}
	}

	private void fail(BotJob job, Throwable cause) throws InterruptedException {
		String reason = "Bots could not be made: " + (cause.getMessage() == null ? cause.getClass().getSimpleName()
				: cause.getMessage());
		if (reason.length() > REASON_LENGTH) {
			reason = reason.substring(0, REASON_LENGTH - 1) + "…";
		}
		try {
			BotJobResult failed = await(engine.get().failBotJob(job, reason));
			LOG.info("Bot job {} recorded as {}", job.id(), failed.status());
		}
		catch (RuntimeException ex) {
			LOG.error("Bot job {} could not be recorded as failed; it stays running, and the next start takes it up "
					+ "again", job.id(), ex);
		}
	}

	/** Stops after the part being made, if any; a job left running is taken up again by the next start. */
	@Override
	public void close() {
		closed = true;
		thread.shutdownNow();
	}

}
