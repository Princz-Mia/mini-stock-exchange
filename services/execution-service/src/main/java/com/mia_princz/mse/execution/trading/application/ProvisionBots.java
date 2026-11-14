package com.mia_princz.mse.execution.trading.application;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * An operator creates funded bot accounts while the market is in SETUP, each with the same endowment and its own API
 * token. Bots are numbered in the order they are created: bot-0001, bot-0002 and so on. They are all tagged with the
 * strategy given.
 */
public record ProvisionBots(int count, long cashCents, List<ProvisionAccount.Holding> holdings, BotStrategy strategy,
		UUID actorUserId) {

	/** Keeps one command well inside the request timeout. */
	public static final int MAX_PER_COMMAND = 100;

	/** The largest population the simulator is planned for. */
	public static final int MAX_POPULATION = 1_000;

	public ProvisionBots {
		Objects.requireNonNull(strategy, "strategy");
		Objects.requireNonNull(actorUserId, "actorUserId");
		holdings = List.copyOf(holdings);
		if (count < 1 || count > MAX_PER_COMMAND) {
			throw new IllegalArgumentException("count must be between 1 and " + MAX_PER_COMMAND);
		}
		if (cashCents < 0) {
			throw new IllegalArgumentException("cashCents must not be negative");
		}
	}

	static String name(int number) {
		return String.format("bot-%04d", number);
	}

}
