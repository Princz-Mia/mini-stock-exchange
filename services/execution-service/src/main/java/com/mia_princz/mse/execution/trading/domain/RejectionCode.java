package com.mia_princz.mse.execution.trading.domain;

/** Why an order was rejected. A rejection is a durable result: the order is stored, with no fills and no hold. */
public enum RejectionCode {

	MARKET_NOT_OPEN, INSUFFICIENT_CASH, INSUFFICIENT_SHARES, ORDER_LIMIT_EXCEEDED, EXECUTION_LIMIT_EXCEEDED

}
