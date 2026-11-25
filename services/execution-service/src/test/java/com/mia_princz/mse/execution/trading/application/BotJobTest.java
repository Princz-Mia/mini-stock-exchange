package com.mia_princz.mse.execution.trading.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * How a bot job plans its parts, over seeded random totals, splits and part sizes, so a failure repeats exactly: every
 * part has one strategy and at most a part's bots, the parts make exactly what was asked, and after every part each
 * strategy is within one part of its share. And the time left, and the keys of the parts.
 */
class BotJobTest {

	private static final Duration STALLED_AFTER = Duration.ofSeconds(15);

	private static final long SEED = 20261124L;

	private static final Instant START = Instant.parse("2026-11-24T10:00:00Z");

	@Test
	void thePartsMakeExactlyWhatWasAskedOneStrategyAtATimeAndKeepEachStrategyInItsShare() {
		Random random = new Random(SEED);
		List<int[]> jobs = new ArrayList<>(List.of(new int[] { 4, 12, 4, 1000 }, new int[] { 1, 0, 0, 1 },
				new int[] { 0, 0, 7, 3 }, new int[] { 4000, 12000, 4000, 1000 }, new int[] { 1, 1, 1, 1 },
				new int[] { 0, 5, 0, 5 }, new int[] { 999, 1, 0, 1000 }));
		for (int i = 0; i < 2_000; i++) {
			int scale = random.nextInt(4);
			int bound = scale == 0 ? 10 : scale == 1 ? 300 : scale == 2 ? 5_000 : 60_000;
			int[] job = { random.nextInt(bound), random.nextInt(bound), random.nextInt(bound), 1 + random.nextInt(
					random.nextBoolean() ? 20 : 2_000) };
			if (job[0] + job[1] + job[2] == 0) {
				job[1] = 1;
			}
			jobs.add(job);
		}
		for (int[] spec : jobs) {
			BotJob.Counts requested = new BotJob.Counts(spec[0], spec[1], spec[2]);
			BotJob job = BotJob.start(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
					spec[3], requested, 100, List.of(), START);
			int parts = 0;
			while (job.nextPart().isPresent()) {
				BotJob.Part part = job.nextPart().get();
				assertThat(part.count()).as("a part of %s", job).isBetween(1, spec[3]);
				assertThat(part.count()).isLessThanOrEqualTo(requested.of(part.strategy())
						- job.created().of(part.strategy()));
				job = job.afterPart(part, START.plusSeconds(++parts));
				assertThat(job.partsDone()).isEqualTo(parts);
				for (BotStrategy one : BotStrategy.values()) {
					for (BotStrategy other : BotStrategy.values()) {
						// Each strategy's made share is within one part of every other's, until one of them is done.
						if (requested.of(one) == 0 || requested.of(other) == 0
								|| job.created().of(other) == requested.of(other)) {
							continue;
						}
						double ahead = (double) job.created().of(one) / requested.of(one)
								- (double) job.created().of(other) / requested.of(other);
						assertThat(ahead).as("%s ahead of %s in %s", one, other, job)
							.isLessThanOrEqualTo((double) spec[3] / requested.of(one) + 1e-9);
					}
				}
				assertThat(job.state()).isEqualTo(job.created().equals(requested) ? BotJob.State.DONE
						: BotJob.State.RUNNING);
			}
			assertThat(job.created()).isEqualTo(requested);
			assertThat(job.state()).isEqualTo(BotJob.State.DONE);
			assertThat(job.finishedAt()).isNotNull();
			int fewest = 0;
			for (BotStrategy strategy : BotStrategy.values()) {
				fewest += (requested.of(strategy) + spec[3] - 1) / spec[3];
			}
			assertThat(parts).as("parts of %s", job).isEqualTo(fewest);
		}
	}

	/** The usual split of 20,000 in parts of 1,000: every strategy starts at once, and noise catches up. */
	@Test
	void theUsualSplitTakesTurnsFromTheFirstPart() {
		BotJob job = BotJob.start(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1_000,
				new BotJob.Counts(4_000, 12_000, 4_000), 100, List.of(), START);
		List<BotStrategy> order = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			BotJob.Part part = job.nextPart().orElseThrow();
			order.add(part.strategy());
			job = job.afterPart(part, START);
		}
		assertThat(order).containsExactly(BotStrategy.MARKET_MAKER, BotStrategy.NOISE, BotStrategy.MOMENTUM,
				BotStrategy.NOISE, BotStrategy.NOISE, BotStrategy.MARKET_MAKER);
	}

	@Test
	void theTimeLeftIsTheRateSinceTheJobWasTakenUpAppliedToWhatIsLeft() {
		BotJob job = BotJob.start(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1_000,
				new BotJob.Counts(0, 10_000, 0), 100, List.of(), START);
		assertThat(job.secondsLeft(START, STALLED_AFTER)).as("nothing to go on yet").isEmpty();
		job = job.afterPart(job.nextPart().orElseThrow(), START.plusMillis(500));
		// 1,000 in half a second; 9,000 left.
		assertThat(job.secondsLeft(START.plusMillis(500), STALLED_AFTER)).hasValue(5);
		job = job.afterPart(job.nextPart().orElseThrow(), START.plus(Duration.ofMillis(1_100)));
		// 2,000 in 1.1 s; 8,000 left: 4.4 s, rounded up.
		assertThat(job.secondsLeft(START.plusMillis(1_100), STALLED_AFTER)).hasValue(5);

		// A restart takes it up again: the rate counts only what this process made, from when it took the job up.
		BotJob resumed = job.resumedAt(START.plusSeconds(60));
		assertThat(resumed.resumed()).isEqualTo(1);
		assertThat(resumed.secondsLeft(START.plusSeconds(60), STALLED_AFTER)).as("nothing made since it was taken up")
			.isEmpty();
		resumed = resumed.afterPart(resumed.nextPart().orElseThrow(), START.plusSeconds(62));
		// 1,000 in 2 s; 7,000 left.
		assertThat(resumed.secondsLeft(START.plusSeconds(62), STALLED_AFTER)).hasValue(14);
		assertThat(resumed.stopped(START.plusSeconds(63)).secondsLeft(START.plusSeconds(63), STALLED_AFTER))
			.as("not running").isEmpty();
	}

	/**
	 * The estimate is read at the time of the read: it counts down between parts, never below one second while bots
	 * are left, and once no part has committed for longer than the job may take between parts, the job is stalled and
	 * has no estimate at all. A frozen "about 10 s left" on a job nothing makes any more would say it is moving.
	 */
	@Test
	void theTimeLeftCountsDownToTheReadAndAJobThatStopsMovingIsStalled() {
		BotJob job = BotJob.start(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1_000,
				new BotJob.Counts(0, 20_000, 0), 100, List.of(), START);
		assertThat(job.progressAt()).as("taken up, nothing made yet").isEqualTo(START);
		assertThat(job.stalled(START.plus(STALLED_AFTER), STALLED_AFTER)).as("exactly at the limit").isFalse();
		assertThat(job.stalled(START.plus(STALLED_AFTER).plusMillis(1), STALLED_AFTER))
			.as("a job taken up that never makes a part").isTrue();

		Instant firstPart = START.plusSeconds(1);
		job = job.afterPart(job.nextPart().orElseThrow(), firstPart);
		assertThat(job.progressAt()).isEqualTo(firstPart);
		// 1,000 a second; 19,000 left.
		assertThat(job.secondsLeft(firstPart, STALLED_AFTER)).hasValue(19);
		assertThat(job.secondsLeft(firstPart.plusMillis(4_500), STALLED_AFTER)).as("4.5 s on: 14.5, rounded up")
			.hasValue(15);
		assertThat(job.secondsLeft(firstPart.minusMillis(300), STALLED_AFTER))
			.as("a clock read just before the part counts nothing off").hasValue(19);
		assertThat(job.stalled(firstPart.plusSeconds(14), STALLED_AFTER)).isFalse();
		assertThat(job.secondsLeft(firstPart.plus(STALLED_AFTER), STALLED_AFTER)).as("at least a second while running")
			.hasValue(4);

		Instant later = firstPart.plus(STALLED_AFTER).plusSeconds(1);
		assertThat(job.stalled(later, STALLED_AFTER)).isTrue();
		assertThat(job.secondsLeft(later, STALLED_AFTER)).as("no estimate for a job that does not move").isEmpty();
		BotJob nearlyDone = BotJob.start(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				1_000, new BotJob.Counts(0, 2_000, 0), 100, List.of(), START);
		nearlyDone = nearlyDone.afterPart(nearlyDone.nextPart().orElseThrow(), firstPart);
		assertThat(nearlyDone.secondsLeft(firstPart.plusSeconds(5), STALLED_AFTER))
			.as("overdue but not stalled: still a second, never none").hasValue(1);

		BotJob resumed = job.resumedAt(later.plusSeconds(30));
		assertThat(resumed.progressAt()).as("taken up again: moving from then").isEqualTo(later.plusSeconds(30));
		assertThat(resumed.stalled(later.plusSeconds(31), STALLED_AFTER)).isFalse();
		assertThat(job.stopped(later).stalled(later.plusSeconds(60), STALLED_AFTER)).as("only a running job stalls")
			.isFalse();
	}

	@Test
	void aPartsKeyIsTheSameForTheSameJobAndNumberAndDiffersOtherwise() {
		UUID jobKey = UUID.randomUUID();
		Set<UUID> keys = new HashSet<>();
		for (int number = 0; number < 1_000; number++) {
			assertThat(BotJob.partKey(jobKey, number)).isEqualTo(BotJob.partKey(jobKey, number));
			keys.add(BotJob.partKey(jobKey, number));
		}
		assertThat(keys).hasSize(1_000).doesNotContain(jobKey);
		assertThat(BotJob.partKey(UUID.randomUUID(), 0)).isNotEqualTo(BotJob.partKey(jobKey, 0));
		assertThat(BotJob.failureKey(jobKey)).isNotIn(keys);
	}

	@Test
	void countsAreNeverNegativeAndATotalPastAnIntIsRefused() {
		assertThatThrownBy(() -> new BotJob.Counts(-1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
		assertThat(new BotJob.Counts(Integer.MAX_VALUE, 0, 0).total()).isEqualTo(Integer.MAX_VALUE);
		assertThatThrownBy(() -> new BotJob.Counts(Integer.MAX_VALUE, 1, 0).total())
			.isInstanceOf(ArithmeticException.class);
		assertThatThrownBy(() -> new StartBotJob(UUID.randomUUID(), BotJob.Counts.NONE, 0, List.of(),
				UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
	}

}
