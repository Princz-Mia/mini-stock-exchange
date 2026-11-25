package com.mia_princz.mse.execution.trading.application;

/**
 * Makes a running bot job's parts, one after another, off the writer, each as a command of its own: told by the engine
 * when a job has started, and at startup when one was running.
 */
@FunctionalInterface
public interface BotJobRunner {

	/** Runs nothing: a job started then stays RUNNING until a process that runs jobs takes it up. */
	BotJobRunner NONE = (job) -> {
	};

	/** Goes on with {@code job}, as it was last committed, until it is no longer running. */
	void run(BotJob job);

}
