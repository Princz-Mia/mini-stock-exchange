package com.mia_princz.mse.execution.identity.adapter;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Limits failed sign-ins, per username and per client address, so passwords can't be guessed at speed. Each key may
 * fail a few times at once, then once per interval. A key over its limit is refused before its password is checked,
 * so a correct guess made while refused learns nothing. Successful sign-ins cost nothing: someone who knows their
 * password is never slowed down by their own logins.
 *
 * <p>
 * Each key keeps the time its next failure is due (the generic cell rate algorithm, like the command rate limit).
 * Times come from a monotonic nanosecond source and are only compared by difference. Usernames are chosen by the
 * caller, so the table is pruned of keys back within their limit whenever it grows past a bound; those are
 * indistinguishable from keys never seen.
 */
final class FailedLoginLimiter {

	/** Past this many keys, the ones back within their limit are dropped. */
	static final int PRUNE_ABOVE = 10_000;

	/** How many failures one key may have at once, and the time it then waits between further failures. */
	record Limit(int burst, long intervalNanos) {

		Limit {
			if (burst < 1 || intervalNanos < 1) {
				throw new IllegalArgumentException("A login limit needs a burst and an interval: " + burst + ", "
						+ intervalNanos);
			}
		}

		long tolerance() {
			return intervalNanos * (burst - 1);
		}

	}

	private final Limit perUser;

	private final Limit perAddress;

	private final LongSupplier nanoTime;

	private final ConcurrentHashMap<String, Long> due = new ConcurrentHashMap<>();

	FailedLoginLimiter(Limit perUser, Limit perAddress, LongSupplier nanoTime) {
		this.perUser = perUser;
		this.perAddress = perAddress;
		this.nanoTime = nanoTime;
	}

	/** Zero if this sign-in may be tried now; otherwise the nanoseconds until it may. Takes nothing. */
	long refusedFor(String username, String address) {
		long now = nanoTime.getAsLong();
		return Math.max(wait(userKey(username), perUser, now), wait(addressKey(address), perAddress, now));
	}

	/**
	 * Zero if this attempt may go on, having already counted it as a failure against both keys; otherwise the
	 * nanoseconds until it may, counting nothing. Counting before the password is checked means attempts sent at once
	 * cannot all pass the check while the first is still being hashed. A successful attempt gives it back with
	 * {@link #succeeded}.
	 */
	long reserve(String username, String address) {
		String user = userKey(username);
		String place = addressKey(address);
		long refused = take(user, perUser);
		if (refused > 0) {
			return refused;
		}
		refused = take(place, perAddress);
		if (refused > 0) {
			giveBack(user, perUser);
			return refused;
		}
		prune();
		return 0;
	}

	/** Gives back what {@link #reserve} counted: someone who knows their password is never slowed by it. */
	void succeeded(String username, String address) {
		giveBack(userKey(username), perUser);
		giveBack(addressKey(address), perAddress);
	}

	/** Counts a failed sign-in against both the username and the address. */
	void failed(String username, String address) {
		record(userKey(username), perUser);
		record(addressKey(address), perAddress);
		prune();
	}

	private void prune() {
		if (due.size() > PRUNE_ABOVE) {
			long now = nanoTime.getAsLong();
			due.values().removeIf((next) -> next - now <= 0);
		}
	}

	/** In one step: the wait if this key is over its limit, counting nothing; otherwise 0, counting a failure. */
	private long take(String key, Limit limit) {
		long[] refused = { 0 };
		due.compute(key, (k, next) -> {
			long now = nanoTime.getAsLong();
			if (next != null && next - now > limit.tolerance()) {
				refused[0] = next - limit.tolerance() - now;
				return next;
			}
			long start = (next == null || next - now < 0) ? now : next;
			return start + limit.intervalNanos();
		});
		return refused[0];
	}

	private void giveBack(String key, Limit limit) {
		due.computeIfPresent(key, (k, next) -> next - limit.intervalNanos());
	}

	int keys() {
		return due.size();
	}

	private long wait(String key, Limit limit, long now) {
		Long next = due.get(key);
		if (next == null || next - now <= limit.tolerance()) {
			return 0;
		}
		return next - limit.tolerance() - now;
	}

	private void record(String key, Limit limit) {
		due.compute(key, (k, next) -> {
			long now = nanoTime.getAsLong();
			long start = (next == null || next - now < 0) ? now : next;
			return start + limit.intervalNanos();
		});
	}

	/** One key per person however they type their name; usernames are matched exactly, so this only groups more. */
	private static String userKey(String username) {
		return "user:" + username.strip().toLowerCase(Locale.ROOT);
	}

	private static String addressKey(String address) {
		return "address:" + address;
	}

}
