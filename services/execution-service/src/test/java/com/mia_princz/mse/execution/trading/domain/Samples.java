package com.mia_princz.mse.execution.trading.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generated inputs for property-style tests: boundary values plus random values from a fixed seed, so a failure repeats
 * exactly. Random longs get a random shift, because uniform longs are almost never small.
 */
final class Samples {

	private static final long SEED = 20261102L;

	private static final int RANDOM_COUNT = 2_000;

	private static final List<Long> EDGES = List.of(Long.MIN_VALUE, Long.MIN_VALUE + 1, -100L, -1L, 0L, 1L, 99L, 100L,
			Long.MAX_VALUE - 1, Long.MAX_VALUE);

	private Samples() {
	}

	static List<Long> longs() {
		Random random = new Random(SEED);
		List<Long> values = new ArrayList<>(EDGES);
		for (int i = 0; i < RANDOM_COUNT; i++) {
			values.add(anyMagnitude(random));
		}
		return values;
	}

	static List<long[]> longPairs() {
		Random random = new Random(SEED);
		List<long[]> pairs = new ArrayList<>();
		for (long left : EDGES) {
			for (long right : EDGES) {
				pairs.add(new long[] { left, right });
			}
		}
		for (int i = 0; i < RANDOM_COUNT; i++) {
			pairs.add(new long[] { anyMagnitude(random), anyMagnitude(random) });
		}
		return pairs;
	}

	static List<Long> between(long min, long max) {
		Random random = new Random(SEED);
		List<Long> values = new ArrayList<>(List.of(min, min + 1, max - 1, max));
		for (int i = 0; i < RANDOM_COUNT; i++) {
			values.add(random.nextLong(min, max + 1));
		}
		return values;
	}

	static List<Long> outside(long min, long max) {
		List<Long> values = new ArrayList<>(List.of(min - 1, max + 1));
		longs().stream().filter((value) -> value < min || value > max).forEach(values::add);
		return values;
	}

	private static long anyMagnitude(Random random) {
		return random.nextLong() >> random.nextInt(64);
	}

}
