package com.mia_princz.mse.execution.trading.adapter.web;

import java.time.Duration;
import java.util.Arrays;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Trading commands over the last minute, for the operator's health panel. The Micrometer meters behind the dashboard
 * are cumulative since start; this keeps sixty one-second buckets of the same events instead, so "the last minute" is
 * a sum rather than a difference between scrapes. Each bucket remembers its second, so a bucket from a minute ago is
 * reused, not added in. Seconds come from the monotonic clock, as command durations do, so a step of the wall clock
 * can neither hide recent counts nor add a minute's counts twice. Cheap and bounded: a fixed array behind one lock,
 * touched once per command.
 */
@Component
final class CommandActivity {

	static final int SECONDS = 60;

	/** What the last minute held: commands finished, how many of them within the budget, and refusals. */
	record Window(long committed, long failed, long withinBudget, long refused) {

		long finished() {
			return committed + failed;
		}

	}

	private final LongSupplier nanoTime;

	private final Duration budget;

	private final long[] second = new long[SECONDS];

	private final long[] committed = new long[SECONDS];

	private final long[] failed = new long[SECONDS];

	private final long[] withinBudget = new long[SECONDS];

	private final long[] refused = new long[SECONDS];

	/** Counted against the trading latency budget the dashboard and the load test use. */
	@Autowired
	CommandActivity() {
		this(System::nanoTime, CommandMetrics.P95_BUDGET);
	}

	CommandActivity(LongSupplier nanoTime, Duration budget) {
		this.nanoTime = nanoTime;
		this.budget = budget;
		Arrays.fill(second, Long.MIN_VALUE);
	}

	/** A command that reached its final result, committed or failed, after {@code took}. */
	synchronized void finished(boolean wasCommitted, Duration took) {
		int slot = slot();
		if (wasCommitted) {
			committed[slot]++;
		}
		else {
			failed[slot]++;
		}
		if (took.compareTo(budget) <= 0) {
			withinBudget[slot]++;
		}
	}

	/** A command turned away before admission. */
	synchronized void refused() {
		refused[slot()]++;
	}

	/** The sixty seconds up to and including the current one. */
	synchronized Window lastMinute() {
		long now = currentSecond();
		long sumCommitted = 0;
		long sumFailed = 0;
		long sumWithin = 0;
		long sumRefused = 0;
		for (int index = 0; index < SECONDS; index++) {
			if (second[index] > now - SECONDS && second[index] <= now) {
				sumCommitted += committed[index];
				sumFailed += failed[index];
				sumWithin += withinBudget[index];
				sumRefused += refused[index];
			}
		}
		return new Window(sumCommitted, sumFailed, sumWithin, sumRefused);
	}

	/** This second's bucket, cleared first if it last held an older second. */
	private long currentSecond() {
		return Math.floorDiv(nanoTime.getAsLong(), 1_000_000_000L);
	}

	private int slot() {
		long now = currentSecond();
		int index = (int) Math.floorMod(now, SECONDS);
		if (second[index] != now) {
			second[index] = now;
			committed[index] = 0;
			failed[index] = 0;
			withinBudget[index] = 0;
			refused[index] = 0;
		}
		return index;
	}

}
