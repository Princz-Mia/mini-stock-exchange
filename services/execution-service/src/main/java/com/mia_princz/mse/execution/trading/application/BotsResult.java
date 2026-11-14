package com.mia_princz.mse.execution.trading.application;

/**
 * The outcome of provisioning bots. Refusals are committed and stored like any result, so a retry with the same key
 * answers the same way. The names are null for a refusal; {@code population} is the number of bots afterwards.
 * {@code strategy} is the tag asked for, null for none and in answers stored before bots had tags.
 */
public record BotsResult(Status status, int created, String firstBot, String lastBot, int population, String strategy,
		long commandSequence) {

	public enum Status {

		CREATED, NOT_IN_SETUP, POPULATION_LIMIT, UNKNOWN_INSTRUMENT

	}

}
