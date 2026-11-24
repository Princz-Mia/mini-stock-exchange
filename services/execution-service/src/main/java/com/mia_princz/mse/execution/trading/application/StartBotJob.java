package com.mia_princz.mse.execution.trading.application;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * An operator starts a job that creates bots in the background while the market is in SETUP: how many of each
 * strategy, and the endowment every one of them gets. There is no limit beyond what a count can hold.
 */
public record StartBotJob(UUID jobId, BotJob.Counts requested, long cashCents, List<ProvisionAccount.Holding> holdings,
		UUID actorUserId) {

	public StartBotJob {
		Objects.requireNonNull(jobId, "jobId");
		Objects.requireNonNull(requested, "requested");
		Objects.requireNonNull(actorUserId, "actorUserId");
		holdings = List.copyOf(holdings);
		if (requested.total() < 1) {
			throw new IllegalArgumentException("A job makes at least one bot");
		}
		if (cashCents < 0) {
			throw new IllegalArgumentException("cashCents must not be negative");
		}
	}

}
