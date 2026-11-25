package com.mia_princz.mse.execution.trading.domain;

/**
 * Why an order was rejected. A rejection is a durable result: the order is stored, with no fills and no hold.
 * {@code MARKET_NOT_OPEN} may pass (the market is still being set up, or is halted); {@code MARKET_CLOSED} is final.
 */
public enum RejectionCode {

	MARKET_NOT_OPEN, MARKET_CLOSED, INSUFFICIENT_CASH, INSUFFICIENT_SHARES, ORDER_LIMIT_EXCEEDED,
	EXECUTION_LIMIT_EXCEEDED

}
