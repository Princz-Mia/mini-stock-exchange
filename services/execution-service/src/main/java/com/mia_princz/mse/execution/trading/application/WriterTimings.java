package com.mia_princz.mse.execution.trading.application;

/**
 * How long each part of a command takes on the writer, so the time a command spends can be split: waiting in the
 * queue, opening its transaction (a connection from the pool included), its statements, its commit, and applying it to
 * the book afterwards. {@code command} is {@code place}, {@code cancel} or {@code other}. A transaction is timed only
 * once it has committed: one that fails, in its statements or at commit, records none of its parts, so the begin, work
 * and commit timers count committed transactions only.
 */
public interface WriterTimings {

	enum Phase {

		QUEUE_WAIT, BEGIN, WORK, COMMIT, APPLY

	}

	void record(Phase phase, String command, long nanos);

	WriterTimings NONE = (phase, command, nanos) -> {
	};

}
