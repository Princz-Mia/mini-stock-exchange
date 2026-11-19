package com.mia_princz.mse.execution;

/**
 * Every scenario of {@link TradingEngineIT} with the class's writers taking runs of up to 32 orders and cancels; a few
 * scenarios build writers of their own. A command sent alone is a run of one either way, so only the scenarios that
 * queue several commands at once, the concurrent ones above all, form runs that share one transaction.
 */
class TradingEngineInRunsIT extends TradingEngineIT {

	@Override
	int maxRun() {
		return 32;
	}

}
