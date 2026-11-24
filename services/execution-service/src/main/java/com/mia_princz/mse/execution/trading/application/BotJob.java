package com.mia_princz.mse.execution.trading.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * A job that creates any number of bots in the background while the market is in SETUP, as a row that every operator's
 * screen reads and a restart resumes. It makes them in parts, one ordinary provisioning command each, every bot of a
 * part with one strategy and every bot of the job with the same endowment. Bots keep their numbering and tags, as if
 * an operator had sent each part by hand.
 * <p>
 * {@code partsDone} is how many parts have committed, which is also the number of the next one. {@code runStartedAt}
 * and {@code createdAtRunStart} are when this process took the job up and how many bots it had then, which the time
 * left is estimated from; {@code resumed} counts restarts that took it up again.
 */
public record BotJob(UUID id, UUID simulationId, UUID startedBy, UUID idempotencyKey, State state, int partSize,
		Counts requested, Counts created, int partsDone, long cashCents, List<ProvisionAccount.Holding> holdings,
		Instant startedAt, Instant runStartedAt, int createdAtRunStart, Instant lastPartAt, Instant finishedAt,
		String failure, int resumed) {

	/**
	 * RUNNING until every bot is made (DONE), an operator stops it (STOPPED), or a part cannot be made (FAILED). Bots
	 * already made stay in every case.
	 */
	public enum State {

		RUNNING, DONE, STOPPED, FAILED

	}

	/** How many bots of each strategy. */
	public record Counts(int marketMakers, int noise, int momentum) {

		public static final Counts NONE = new Counts(0, 0, 0);

		public Counts {
			if (marketMakers < 0 || noise < 0 || momentum < 0) {
				throw new IllegalArgumentException("Counts are never negative: " + marketMakers + ", " + noise + ", "
						+ momentum);
			}
		}

		/** All of them; more than an int holds is refused, as no population can be counted past it. */
		public int total() {
			return Math.addExact(Math.addExact(marketMakers, noise), momentum);
		}

		public int of(BotStrategy strategy) {
			return switch (strategy) {
				case MARKET_MAKER -> marketMakers;
				case NOISE -> noise;
				case MOMENTUM -> momentum;
			};
		}

		Counts plus(BotStrategy strategy, int count) {
			return switch (strategy) {
				case MARKET_MAKER -> new Counts(Math.addExact(marketMakers, count), noise, momentum);
				case NOISE -> new Counts(marketMakers, Math.addExact(noise, count), momentum);
				case MOMENTUM -> new Counts(marketMakers, noise, Math.addExact(momentum, count));
			};
		}

	}

	/** One part: how many bots, all with one strategy. */
	public record Part(BotStrategy strategy, int count) {
	}

	public BotJob {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(state, "state");
		Objects.requireNonNull(requested, "requested");
		Objects.requireNonNull(created, "created");
		holdings = List.copyOf(holdings);
		if (partSize < 1) {
			throw new IllegalArgumentException("A part makes at least one bot: " + partSize);
		}
	}

	/** A job as it starts: nothing made yet, taken up by this process now. */
	public static BotJob start(UUID id, UUID simulationId, UUID startedBy, UUID idempotencyKey, int partSize,
			Counts requested, long cashCents, List<ProvisionAccount.Holding> holdings, Instant now) {
		return new BotJob(id, simulationId, startedBy, idempotencyKey, State.RUNNING, partSize, requested, Counts.NONE,
				0, cashCents, holdings, now, now, 0, null, null, null, 0);
	}

	public int total() {
		return requested.total();
	}

	public int createdTotal() {
		return created.total();
	}

	/**
	 * The next part to make, if any bot is left: the strategy furthest behind its share of the job (the smallest
	 * fraction made, ties in assignment order), as many of it as a part makes or as are left. So a job stopped halfway
	 * leaves about half of each strategy, not every market maker and no momentum bot, and the run form's counts stay
	 * in proportion. Decided from the counts alone, so a restarted job plans the same part as the process before.
	 */
	public Optional<Part> nextPart() {
		BotStrategy behind = null;
		for (BotStrategy strategy : BotStrategy.values()) {
			int wanted = requested.of(strategy);
			if (created.of(strategy) >= wanted) {
				continue;
			}
			// made/wanted < made'/wanted', compared without division: both sides fit a long.
			if (behind == null || (long) created.of(strategy) * requested.of(behind) < (long) created.of(behind)
					* wanted) {
				behind = strategy;
			}
		}
		if (behind == null) {
			return Optional.empty();
		}
		return Optional.of(new Part(behind, Math.min(partSize, requested.of(behind) - created.of(behind))));
	}

	/** The job once {@code part} has committed at {@code at}: DONE if it was the last. */
	BotJob afterPart(Part part, Instant at) {
		Counts made = created.plus(part.strategy(), part.count());
		boolean done = made.equals(requested);
		return new BotJob(id, simulationId, startedBy, idempotencyKey, done ? State.DONE : State.RUNNING, partSize,
				requested, made, partsDone + 1, cashCents, holdings, startedAt, runStartedAt, createdAtRunStart, at,
				done ? at : null, null, resumed);
	}

	BotJob stopped(Instant at) {
		return finished(State.STOPPED, at, null);
	}

	BotJob failed(String reason, Instant at) {
		return finished(State.FAILED, at, Objects.requireNonNull(reason, "reason"));
	}

	/** Taken up again by a process that started while the job was running. */
	BotJob resumedAt(Instant at) {
		return new BotJob(id, simulationId, startedBy, idempotencyKey, state, partSize, requested, created, partsDone,
				cashCents, holdings, startedAt, at, createdTotal(), lastPartAt, finishedAt, failure, resumed + 1);
	}

	private BotJob finished(State to, Instant at, String reason) {
		return new BotJob(id, simulationId, startedBy, idempotencyKey, to, partSize, requested, created, partsDone,
				cashCents, holdings, startedAt, runStartedAt, createdAtRunStart, lastPartAt, at, reason, resumed);
	}

	/**
	 * Seconds left at the rate this process has made bots since it took the job up, rounded up; empty unless the job
	 * runs and a part has committed since then.
	 */
	public OptionalLong secondsLeft() {
		if (state != State.RUNNING || lastPartAt == null || !lastPartAt.isAfter(runStartedAt)) {
			return OptionalLong.empty();
		}
		long made = (long) createdTotal() - createdAtRunStart;
		if (made <= 0) {
			return OptionalLong.empty();
		}
		long nanos = Duration.between(runStartedAt, lastPartAt).toNanos();
		long left = (long) total() - createdTotal();
		// left / (made / nanos), in seconds, rounded up.
		double seconds = (double) left * nanos / made / 1e9;
		return OptionalLong.of((long) Math.ceil(seconds));
	}

	/**
	 * The idempotency key of part {@code number}: a name-based UUID of the job's own key and the number, so the same
	 * part sent again, by this process or one that resumed the job, is the same command and is answered from its
	 * stored answer.
	 */
	public static UUID partKey(UUID jobKey, int number) {
		return UUID.nameUUIDFromBytes(("bot-job-part:" + jobKey + ":" + number).getBytes(StandardCharsets.UTF_8));
	}

	/** The key under which a job that could not go on is recorded as failed, once. */
	static UUID failureKey(UUID jobKey) {
		return UUID.nameUUIDFromBytes(("bot-job-failure:" + jobKey).getBytes(StandardCharsets.UTF_8));
	}

	/** What a part asks for, hashed: the job, the part's number, its strategy and its size. */
	static byte[] partHash(UUID jobId, int number, Part part) {
		return sha256(jobId + "|" + number + "|" + part.strategy().name() + "|" + part.count());
	}

	static byte[] sha256(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
