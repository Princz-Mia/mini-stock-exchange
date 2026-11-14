package com.mia_princz.mse.execution.trading.application;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * An operator starts a bot run: how many bots run each strategy, the seed, the pace and limits, and for how long.
 * Each strategy is run by bots tagged with it, oldest first; untagged bots are never assigned.
 *
 * @param commandsPerSecondHundredths the run's trading commands per second across all its bots, in hundredths
 * @param duration null to run until stopped
 * @param instrumentIds empty for every listed instrument
 */
public record StartBotRun(UUID runId, int marketMakers, int noise, int momentum, long seed,
		long commandsPerSecondHundredths, int maxOrderQuantity, int maxPosition, Duration duration,
		List<UUID> instrumentIds, UUID actorUserId) {

	public StartBotRun {
		Objects.requireNonNull(runId, "runId");
		Objects.requireNonNull(actorUserId, "actorUserId");
		instrumentIds = List.copyOf(instrumentIds);
		if (marketMakers < 0 || noise < 0 || momentum < 0 || marketMakers + noise + momentum < 1) {
			throw new IllegalArgumentException("a run needs at least one bot");
		}
	}

	/** How many bots the run asks for with this strategy. */
	public int count(BotStrategy strategy) {
		return switch (strategy) {
			case MARKET_MAKER -> marketMakers;
			case NOISE -> noise;
			case MOMENTUM -> momentum;
		};
	}

	public int bots() {
		return marketMakers + noise + momentum;
	}

}
