package com.mia_princz.mse.execution.trading.domain;

/** The time in force follows from the type: a limit order is good till cancelled, a protected market order is IOC. */
public enum OrderType {

	/** Rests in the book with any unfilled remainder. The bound is the limit price. */
	LIMIT,

	/** Executes immediately within the bound and cancels any remainder; it never rests. */
	MARKET_PROTECTED

}
