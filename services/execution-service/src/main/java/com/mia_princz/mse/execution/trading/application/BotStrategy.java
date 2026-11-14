package com.mia_princz.mse.execution.trading.application;

/**
 * The kind of trader a bot plays in a run: making markets, trading at random, or following the trend. A bot is tagged
 * once, when it is created, and keeps its tag.
 */
public enum BotStrategy {

	MARKET_MAKER, NOISE, MOMENTUM

}
