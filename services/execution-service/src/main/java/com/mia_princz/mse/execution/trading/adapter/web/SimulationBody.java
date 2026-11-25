package com.mia_princz.mse.execution.trading.adapter.web;

import java.util.List;

import org.springframework.http.HttpStatus;

import com.mia_princz.mse.execution.trading.domain.InvalidSimulationNameException;
import com.mia_princz.mse.execution.trading.domain.SimulationName;

/** The JSON body of a new simulation. Unknown JSON fields are rejected before this class sees them. */
record SimulationBody(String name) {

	record Valid(SimulationName name) {

		/** Of the name as it is kept, so the same name sent with other spaces around it is the same request. */
		byte[] hash() {
			return CommandCalls.sha256(name.value());
		}

	}

	Valid validate() {
		if (name == null) {
			throw invalid("REQUIRED", "name is required");
		}
		try {
			return new Valid(new SimulationName(name));
		}
		catch (InvalidSimulationNameException ex) {
			throw invalid("INVALID_NAME", ex.getMessage());
		}
	}

	private static ApiException invalid(String code, String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The simulation request is invalid",
				List.of(new ApiException.FieldError("name", code, message)));
	}

}
