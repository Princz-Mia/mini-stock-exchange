package com.mia_princz.mse.execution.trading.adapter.web;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * A per-key rate of whole commands per second, with up to one second's worth at once. Each key keeps the time its
 * next command is due at the sustained rate (the generic cell rate algorithm, equivalent to a token bucket holding one
 * second of commands). Times come from a monotonic nanosecond source and are only ever compared by difference, so
 * the source may wrap.
 */
final class RateLimiter {

	private static final long SECOND = 1_000_000_000L;

	/** Time between commands at the sustained rate. */
	private final long interval;

	/** How far ahead of now a key's schedule may run: the rest of one second's worth of commands. */
	private final long tolerance;

	private final LongSupplier nanoTime;

	/** Bounded by the number of accounts that send commands, which provisioning limits. */
	private final ConcurrentHashMap<UUID, Long> due = new ConcurrentHashMap<>();

	RateLimiter(int perSecond, LongSupplier nanoTime) {
		if (perSecond < 1) {
			throw new IllegalArgumentException("A rate limit needs at least one command per second: " + perSecond);
		}
		this.interval = SECOND / perSecond;
		this.tolerance = this.interval * (perSecond - 1);
		this.nanoTime = nanoTime;
	}

	/** Takes one command for the key: zero if it may go now, otherwise the nanoseconds until it may. */
	long acquire(UUID key) {
		long now = nanoTime.getAsLong();
		long[] wait = { 0 };
		due.compute(key, (k, next) -> {
			long start = (next == null || next - now < 0) ? now : next;
			if (start - now > tolerance) {
				wait[0] = start - tolerance - now;
				return next;
			}
			return start + interval;
		});
		return wait[0];
	}

}
