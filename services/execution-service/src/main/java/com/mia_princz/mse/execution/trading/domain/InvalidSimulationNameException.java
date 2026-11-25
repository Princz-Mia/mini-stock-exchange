package com.mia_princz.mse.execution.trading.domain;

/** A simulation name that breaks its rules, after the spaces around it were dropped. */
public class InvalidSimulationNameException extends IllegalArgumentException {

	private static final long serialVersionUID = 1L;

	InvalidSimulationNameException(String message) {
		super(message);
	}

}
