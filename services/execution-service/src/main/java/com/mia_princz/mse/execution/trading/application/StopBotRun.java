package com.mia_princz.mse.execution.trading.application;

import java.util.Objects;
import java.util.UUID;

/** An operator asks a run to stop. Its worker stops placing orders, settles what is in flight and cancels quotes. */
public record StopBotRun(UUID runId, UUID actorUserId) {

	public StopBotRun {
		Objects.requireNonNull(runId, "runId");
		Objects.requireNonNull(actorUserId, "actorUserId");
	}

}
