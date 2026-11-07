package com.mia_princz.mse.execution.trading.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class RateLimiterTest {

	private static final long SECOND = 1_000_000_000L;

	private static final long MILLI = 1_000_000L;

	/** A clock the test moves by hand. */
	private static final class FakeClock {

		long now;

		FakeClock(long start) {
			now = start;
		}

	}

	@Test
	void twoASecondLetsTwoGoAtOnceThenOneEveryHalfSecond() {
		FakeClock clock = new FakeClock(0);
		RateLimiter limiter = new RateLimiter(2, () -> clock.now);
		UUID bot = UUID.randomUUID();

		assertThat(limiter.acquire(bot)).isZero();
		assertThat(limiter.acquire(bot)).isZero();
		assertThat(limiter.acquire(bot)).as("the third at once").isEqualTo(500 * MILLI);
		clock.now = 200 * MILLI;
		assertThat(limiter.acquire(bot)).as("a refusal takes nothing").isEqualTo(300 * MILLI);
		clock.now = 500 * MILLI;
		assertThat(limiter.acquire(bot)).isZero();
		assertThat(limiter.acquire(bot)).isEqualTo(500 * MILLI);
		clock.now = 10 * SECOND;
		assertThat(limiter.acquire(bot)).as("idle time saves up one second's worth, no more").isZero();
		assertThat(limiter.acquire(bot)).isZero();
		assertThat(limiter.acquire(bot)).isEqualTo(500 * MILLI);
	}

	@Test
	void eachKeyHasItsOwnRate() {
		FakeClock clock = new FakeClock(0);
		RateLimiter limiter = new RateLimiter(2, () -> clock.now);
		UUID busy = UUID.randomUUID();
		UUID quiet = UUID.randomUUID();
		limiter.acquire(busy);
		limiter.acquire(busy);

		assertThat(limiter.acquire(busy)).isPositive();
		assertThat(limiter.acquire(quiet)).isZero();
		assertThat(limiter.acquire(quiet)).isZero();
	}

	@Test
	void aClockThatWrapsAroundIsFine() {
		FakeClock clock = new FakeClock(Long.MAX_VALUE - 100 * MILLI);
		RateLimiter limiter = new RateLimiter(2, () -> clock.now);
		UUID bot = UUID.randomUUID();
		limiter.acquire(bot);
		limiter.acquire(bot);
		clock.now += 600 * MILLI;

		assertThat(clock.now).isNegative();
		assertThat(limiter.acquire(bot)).isZero();
		assertThat(limiter.acquire(bot)).isEqualTo(400 * MILLI);
	}

	/**
	 * Two commands at once, where the first reads the clock and the second then runs in full before the first goes on.
	 * The first must not be refused because the second's later time was used first. Correct code reads the clock while
	 * holding the key, so there the second waits instead, and the hand-off below gives up after a moment.
	 */
	@Test
	void aCommandThatReadTheClockFirstIsNotRefusedForIt() throws Exception {
		AtomicLong ticks = new AtomicLong();
		UUID key = UUID.randomUUID();
		Thread first = Thread.currentThread();
		AtomicReference<RateLimiter> limiter = new AtomicReference<>();
		AtomicReference<Thread> second = new AtomicReference<>();
		AtomicLong secondWait = new AtomicLong(-1);
		limiter.set(new RateLimiter(2, () -> {
			long now = ticks.incrementAndGet();
			if (Thread.currentThread() == first && second.get() == null) {
				second.set(Thread.ofPlatform().start(() -> secondWait.set(limiter.get().acquire(key))));
				try {
					second.get().join(Duration.ofMillis(300));
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}
			return now;
		}));

		assertThat(limiter.get().acquire(key)).as("the first").isZero();
		second.get().join();
		assertThat(secondWait.get()).as("the second").isZero();
	}

	@Test
	void aRateBelowOneIsRefused() {
		assertThatIllegalArgumentException().isThrownBy(() -> new RateLimiter(0, System::nanoTime));
		assertThatIllegalArgumentException().isThrownBy(() -> new RateLimiter(-2, System::nanoTime));
	}

	/**
	 * Against a token bucket that holds one second of credit in nanoseconds, refills one per nanosecond and spends one
	 * interval per command: seeded rates, keys and gaps, including bursts at the same instant and long pauses.
	 */
	@Test
	void agreesWithATokenBucketOnSeededTraffic() {
		Random random = new Random(20261107L);
		for (int run = 0; run < 300; run++) {
			int rate = 1 + random.nextInt(20);
			long interval = SECOND / rate;
			long capacity = interval * rate;
			FakeClock clock = new FakeClock(random.nextLong());
			RateLimiter limiter = new RateLimiter(rate, () -> clock.now);
			List<UUID> keys = new ArrayList<>();
			for (int k = 0; k < 1 + random.nextInt(4); k++) {
				keys.add(UUID.randomUUID());
			}
			Map<UUID, long[]> buckets = new HashMap<>();
			for (int step = 0; step < 400; step++) {
				clock.now += switch (random.nextInt(4)) {
					case 0 -> 0;
					case 1 -> random.nextLong(interval);
					case 2 -> random.nextLong(3 * interval);
					default -> random.nextLong(2 * SECOND);
				};
				UUID key = keys.get(random.nextInt(keys.size()));
				long[] bucket = buckets.computeIfAbsent(key, (k) -> new long[] { capacity, clock.now });
				bucket[0] = Math.min(capacity, bucket[0] + (clock.now - bucket[1]));
				bucket[1] = clock.now;
				long expected = bucket[0] >= interval ? 0 : interval - bucket[0];
				if (expected == 0) {
					bucket[0] -= interval;
				}

				assertThat(limiter.acquire(key)).as("run %d step %d rate %d", run, step, rate).isEqualTo(expected);
			}
		}
	}

	/** However the commands are spread, no second admits more than one second's burst plus that second's rate. */
	@Test
	void noWindowAdmitsMoreThanTheRateAllows() {
		Random random = new Random(7L);
		for (int run = 0; run < 200; run++) {
			int rate = 1 + random.nextInt(12);
			FakeClock clock = new FakeClock(0);
			RateLimiter limiter = new RateLimiter(rate, () -> clock.now);
			UUID key = UUID.randomUUID();
			List<Long> admitted = new ArrayList<>();
			for (int step = 0; step < 300; step++) {
				clock.now += random.nextLong(SECOND / rate + 1);
				if (limiter.acquire(key) == 0) {
					admitted.add(clock.now);
				}
			}
			for (int i = 0; i < admitted.size(); i++) {
				int inWindow = 0;
				for (int j = i; j < admitted.size() && admitted.get(j) - admitted.get(i) < SECOND; j++) {
					inWindow++;
				}
				assertThat(inWindow).as("run %d rate %d", run, rate).isLessThanOrEqualTo(2 * rate);
			}
			long span = admitted.getLast() - admitted.getFirst();
			assertThat(admitted.size()).as("sustained, run %d rate %d", run, rate)
				.isLessThanOrEqualTo(rate + 1 + (int) (span * rate / SECOND));
		}
	}

}
