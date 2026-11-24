package com.mia_princz.mse.execution;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock that stands still until a test moves it, read on any thread: what a market's close time is reached by in a
 * test, without waiting for it.
 */
public final class MovableClock extends Clock {

	private volatile Instant now;

	public MovableClock(Instant now) {
		this.now = now;
	}

	public void set(Instant instant) {
		now = instant;
	}

	public void advance(Duration duration) {
		now = now.plus(duration);
	}

	@Override
	public Instant instant() {
		return now;
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		throw new UnsupportedOperationException("A movable clock is UTC");
	}

}
