package com.mia_princz.mse.execution.trading.application;

import java.util.Set;
import java.util.UUID;

/**
 * Told which accounts a command changed, once it has committed and the book has been updated: the moment private
 * data may be read fresh. Called on the writer thread, so it must only record the change and never block.
 */
@FunctionalInterface
public interface AccountChanges {

	AccountChanges NONE = (accountIds, commandSequence) -> {
	};

	void changed(Set<UUID> accountIds, long commandSequence);

}
