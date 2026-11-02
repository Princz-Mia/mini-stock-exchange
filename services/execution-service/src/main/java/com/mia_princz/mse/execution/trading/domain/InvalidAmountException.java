package com.mia_princz.mse.execution.trading.domain;

/** A price, quantity or amount outside the exact representation or its bounds. Nothing was rounded or clamped. */
public class InvalidAmountException extends IllegalArgumentException {

	private static final long serialVersionUID = 1L;

	InvalidAmountException(String message) {
		super(message);
	}

}
