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
		// No sum: with no limit on a run, counts near the int maximum would overflow one.
		if (marketMakers < 0 || noise < 0 || momentum < 0 || (marketMakers == 0 && noise == 0 && momentum == 0)) {
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

	/** The bots in a run that started; each count was then at most the bots tagged with it, so this cannot overflow. */
	public int bots() {
		return Math.addExact(Math.addExact(marketMakers, noise), momentum);
	}

}
