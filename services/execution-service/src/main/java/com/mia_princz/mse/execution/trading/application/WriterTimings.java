package com.mia_princz.mse.execution.trading.application;

/**
 * How long each part of a command takes on the writer, so the time a command spends can be split: waiting in the
 * queue, opening its transaction (a connection from the pool included), its statements, its commit, and applying it to
 * the books afterwards. {@code command} is {@code place}, {@code cancel} or {@code other}; a transaction that several
 * orders and cancels entered together is timed once as {@code run}, though each of them waits in the queue as itself,
 * so queue waits are never {@code run}. A transaction is timed only once it has committed: one that fails, in its
 * statements or at commit, records none of its parts, so the begin, work and commit timers count committed
 * transactions only. Applying is timed only for a transaction that changed a book.
 */
public interface WriterTimings {

	enum Phase {

		QUEUE_WAIT, BEGIN, WORK, COMMIT, APPLY

	}

	void record(Phase phase, String command, long nanos);

	/** A run of this many commands failed without halting the writer, and its commands ran again one by one. */
	default void runFellBack(int commands) {
	}

	WriterTimings NONE = (phase, command, nanos) -> {
	};

}
