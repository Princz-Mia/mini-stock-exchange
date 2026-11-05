package com.mia_princz.mse.execution.trading.application;

import java.util.UUID;

/**
 * Issues a bot's API token. The token itself goes once to the simulator's secret store; only its hash comes back, to be
 * stored. Called inside the provisioning transaction before it commits, so if handing the token over fails, nothing is
 * committed.
 */
public interface BotCredentials {

	byte[] issue(UUID accountId);

}
