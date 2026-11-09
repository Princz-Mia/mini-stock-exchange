package com.mia_princz.mse.execution.identity.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class FailedLoginLimiterTest {

	private static final long SECOND = 1_000_000_000L;

	/** A clock the test moves by hand, started where the nanosecond counter wraps. */
	private long now = Long.MAX_VALUE - 30 * SECOND;

	/** The defaults: 5 failures per username then one every 12 s; 20 per address then one every 3 s. */
	private final FailedLoginLimiter limiter = new FailedLoginLimiter(new FailedLoginLimiter.Limit(5, 12 * SECOND),
			new FailedLoginLimiter.Limit(20, 3 * SECOND), () -> now);

	@Test
	void fiveFailuresAtOnceThenOneEveryTwelveSeconds() {
		for (int i = 0; i < 5; i++) {
			assertThat(limiter.refusedFor("trader", "10.0.0.1")).as("failure %d", i + 1).isZero();
			limiter.failed("trader", "10.0.0.1");
		}

		assertThat(limiter.refusedFor("trader", "10.0.0.1")).as("the sixth").isEqualTo(12 * SECOND);
		now += 5 * SECOND;
		assertThat(limiter.refusedFor("trader", "10.0.0.1")).as("asking takes nothing").isEqualTo(7 * SECOND);
		now += 7 * SECOND;
		assertThat(limiter.refusedFor("trader", "10.0.0.1")).isZero();
		limiter.failed("trader", "10.0.0.1");
		assertThat(limiter.refusedFor("trader", "10.0.0.1")).isEqualTo(12 * SECOND);
		now += 60 * SECOND;
		assertThat(limiter.refusedFor("trader", "10.0.0.1")).as("a quiet minute restores the five").isZero();
	}

	@Test
	void theUsernameLimitFollowsThePersonFromAnyAddressInAnyCase() {
		for (int i = 0; i < 5; i++) {
			limiter.failed(i % 2 == 0 ? "Trader" : " trader ", "10.0.0." + i);
		}

		assertThat(limiter.refusedFor("TRADER", "10.9.9.9")).isEqualTo(12 * SECOND);
		assertThat(limiter.refusedFor("operator", "10.9.9.9")).as("another user").isZero();
	}

	@Test
	void theAddressLimitCoversEveryUsernameTriedFromIt() {
		for (int i = 0; i < 20; i++) {
			limiter.failed("guess-" + i, "10.0.0.7");
		}

		assertThat(limiter.refusedFor("never-tried", "10.0.0.7")).isEqualTo(3 * SECOND);
		assertThat(limiter.refusedFor("never-tried", "10.0.0.8")).as("another address").isZero();
	}

	@Test
	void keysBackWithinTheirLimitArePrunedOnceTheTableIsLarge() {
		for (int i = 0; i < FailedLoginLimiter.PRUNE_ABOVE / 2; i++) {
			limiter.failed("guess-" + i, "10.1." + (i / 250) + "." + (i % 250));
		}
		assertThat(limiter.keys()).isEqualTo(FailedLoginLimiter.PRUNE_ABOVE);
		limiter.failed("trader", "10.0.0.1");
		assertThat(limiter.keys()).as("all still within their window").isEqualTo(FailedLoginLimiter.PRUNE_ABOVE + 2);

		now += 13 * SECOND;
		limiter.failed("operator", "10.0.0.2");

		assertThat(limiter.keys()).as("only keys that would still refuse something remain").isLessThanOrEqualTo(2);
		assertThat(limiter.refusedFor("guess-1", "10.1.0.1")).isZero();
	}

	@Test
	void aLimitNeedsABurstAndAnInterval() {
		assertThatIllegalArgumentException().isThrownBy(() -> new FailedLoginLimiter.Limit(0, SECOND));
		assertThatIllegalArgumentException().isThrownBy(() -> new FailedLoginLimiter.Limit(5, 0));
	}

}
