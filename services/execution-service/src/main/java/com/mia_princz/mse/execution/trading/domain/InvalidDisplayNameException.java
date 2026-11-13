package com.mia_princz.mse.execution.trading.domain;

/** A display name that breaks its rules. Nothing was trimmed or replaced. */
public class InvalidDisplayNameException extends IllegalArgumentException {

	private static final long serialVersionUID = 1L;

	InvalidDisplayNameException(String message) {
		super(message);
	}

}
