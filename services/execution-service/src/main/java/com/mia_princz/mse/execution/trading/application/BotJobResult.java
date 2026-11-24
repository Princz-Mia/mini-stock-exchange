package com.mia_princz.mse.execution.trading.application;

/**
 * The outcome of a command about a bot job: starting one, stopping one, one of its parts, or recording that it failed.
 * Refusals are committed and stored like any result. {@code job} is the job as the command left it: the job started,
 * stopped or after the part, or the running job a start was refused for; null when there is no job to show.
 */
public record BotJobResult(Status status, BotJob job, long commandSequence) {

	public enum Status {

		/** A job was started. */
		STARTED,

		/** A part of the job made its bots. */
		PART_CREATED,

		/** The job was stopped by an operator. */
		STOPPED,

		/** The job was recorded as failed. */
		FAILED,

		/** Bots are created only while the market is in SETUP. */
		NOT_IN_SETUP,

		/** Another job of this simulation is still running; {@code job} is that one. */
		JOB_RUNNING,

		/** The endowment names an instrument the market does not have. */
		UNKNOWN_INSTRUMENT,

		/** The job is not running: done, stopped or failed already. */
		NOT_RUNNING,

		/** No such job in this simulation. */
		NOT_FOUND

	}

}
