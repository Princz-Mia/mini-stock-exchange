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
		assertThat(job.secondsLeft()).as("nothing to go on yet").isEmpty();
		job = job.afterPart(job.nextPart().orElseThrow(), START.plusMillis(500));
		// 1,000 in half a second; 9,000 left.
		assertThat(job.secondsLeft()).hasValue(5);
		job = job.afterPart(job.nextPart().orElseThrow(), START.plus(Duration.ofMillis(1_100)));
		// 2,000 in 1.1 s; 8,000 left: 4.4 s, rounded up.
		assertThat(job.secondsLeft()).hasValue(5);

		// A restart takes it up again: the rate counts only what this process made, from when it took the job up.
		BotJob resumed = job.resumedAt(START.plusSeconds(60));
		assertThat(resumed.resumed()).isEqualTo(1);
		assertThat(resumed.secondsLeft()).as("nothing made since it was taken up").isEmpty();
		resumed = resumed.afterPart(resumed.nextPart().orElseThrow(), START.plusSeconds(62));
		// 1,000 in 2 s; 7,000 left.
		assertThat(resumed.secondsLeft()).hasValue(14);
		assertThat(resumed.stopped(START.plusSeconds(63)).secondsLeft()).as("not running").isEmpty();
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
