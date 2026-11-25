package com.mia_princz.mse.execution.trading.application;

import java.util.Objects;
import java.util.UUID;

/** An operator stops a running bot job: the bots made so far stay, and the rest are not made. */
public record StopBotJob(UUID jobId, UUID actorUserId) {

	public StopBotJob {
		Objects.requireNonNull(jobId, "jobId");
		Objects.requireNonNull(actorUserId, "actorUserId");
	}

}
