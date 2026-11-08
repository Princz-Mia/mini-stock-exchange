package com.mia_princz.mse.execution.trading.application;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * One instrument's newest trades, newest first, as the market snapshot publishes them. The writer keeps it in memory
 * like the book, so a snapshot needs no query: it changes only after a command commits, and a restart rebuilds it from
 * the database.
 */
final class Tape {

	private final int capacity;

	private final Deque<TapeTrade> newestFirst = new ArrayDeque<>();

	Tape(int capacity, List<TapeTrade> newestFirstFromDatabase) {
		if (capacity < 1) {
			throw new IllegalArgumentException("A tape holds at least one trade");
		}
		this.capacity = capacity;
		for (TapeTrade trade : newestFirstFromDatabase) {
			if (newestFirst.size() == capacity) {
				break;
			}
			newestFirst.addLast(trade);
		}
	}

	/** The tape as it will be once trades made by the current command commit, oldest of them first; unchanged. */
	List<TapeTrade> with(List<TapeTrade> newTradesOldestFirst) {
		List<TapeTrade> result = new ArrayList<>(capacity);
		for (int i = newTradesOldestFirst.size() - 1; i >= 0 && result.size() < capacity; i--) {
			result.add(newTradesOldestFirst.get(i));
		}
		for (TapeTrade trade : newestFirst) {
			if (result.size() == capacity) {
				break;
			}
			result.add(trade);
		}
		return List.copyOf(result);
	}

	/** Records committed trades, oldest of them first, dropping the oldest beyond the capacity. */
	void add(List<TapeTrade> newTradesOldestFirst) {
		for (TapeTrade trade : newTradesOldestFirst) {
			newestFirst.addFirst(trade);
			if (newestFirst.size() > capacity) {
				newestFirst.removeLast();
			}
		}
	}

}
