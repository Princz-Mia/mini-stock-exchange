package com.mia_princz.mse.execution.trading.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class CommandActivityTest {

	/** A clock the test moves by hand. */
	private static final class Hand extends Clock {

		private Instant now = Instant.parse("2026-11-11T09:00:00.250Z");

		void advance(Duration by) {
			now = now.plus(by);
		}

		/** The same moment as a monotonic reading, which is what the window counts by. */
		long nanos() {
			return now.getEpochSecond() * 1_000_000_000L + now.getNano();
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneId.of("UTC");
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

	}

	private final Hand clock = new Hand();

	private final CommandActivity activity = new CommandActivity(clock::nanos, Duration.ofMillis(150));

	@Test
	void countsCommandsAndRefusalsOfTheLastMinute() {
		activity.finished(true, Duration.ofMillis(20));
		activity.finished(true, Duration.ofMillis(150));
		activity.finished(true, Duration.ofMillis(151));
		activity.finished(false, Duration.ofMillis(5));
		activity.refused();

		assertThat(activity.lastMinute()).isEqualTo(new CommandActivity.Window(3, 1, 3, 1));
		assertThat(activity.lastMinute().finished()).isEqualTo(4);
	}

	@Test
	void forgetsWhatIsOlderThanSixtySeconds() {
		activity.finished(true, Duration.ofMillis(10));
		clock.advance(Duration.ofSeconds(59));
		activity.refused();
		assertThat(activity.lastMinute()).isEqualTo(new CommandActivity.Window(1, 0, 1, 1));

		clock.advance(Duration.ofSeconds(1));
		assertThat(activity.lastMinute()).as("the first second has left the window")
			.isEqualTo(new CommandActivity.Window(0, 0, 0, 1));

		clock.advance(Duration.ofMinutes(5));
		assertThat(activity.lastMinute()).isEqualTo(new CommandActivity.Window(0, 0, 0, 0));
	}

	@Test
	void reusesABucketAMinuteLaterWithoutAddingTheOldCounts() {
		activity.finished(true, Duration.ofMillis(10));
		activity.finished(true, Duration.ofMillis(10));
		clock.advance(Duration.ofSeconds(60));

		activity.finished(false, Duration.ofSeconds(1));

		assertThat(activity.lastMinute()).isEqualTo(new CommandActivity.Window(0, 1, 0, 0));
	}

	/** Against a plain list of events: any mix of commands and refusals over several minutes sums the same. */
	@Test
	void matchesAnEventListOverRandomTraffic() {
		for (int seed = 1; seed <= 30; seed++) {
			Random random = new Random(seed);
			Hand hand = new Hand();
			CommandActivity counted = new CommandActivity(hand::nanos, Duration.ofMillis(150));
			List<long[]> events = new ArrayList<>();
			for (int step = 0; step < 2_000; step++) {
				hand.advance(Duration.ofMillis(random.nextInt(400)));
				long second = hand.millis() / 1000;
				if (random.nextInt(10) == 0) {
					counted.refused();
					events.add(new long[] { second, 0, 0, 0, 1 });
				}
				else {
					boolean committed = random.nextInt(20) != 0;
					long took = random.nextInt(300);
					counted.finished(committed, Duration.ofMillis(took));
					events.add(new long[] { second, committed ? 1 : 0, committed ? 0 : 1, took <= 150 ? 1 : 0, 0 });
				}
				if (step % 97 == 0) {
					long now = hand.millis() / 1000;
					long[] sum = new long[4];
					for (long[] event : events) {
						if (event[0] > now - 60) {
							for (int i = 0; i < 4; i++) {
								sum[i] += event[i + 1];
							}
						}
					}
					assertThat(counted.lastMinute()).as("seed %d, step %d", seed, step)
						.isEqualTo(new CommandActivity.Window(sum[0], sum[1], sum[2], sum[3]));
				}
			}
		}
	}

}
