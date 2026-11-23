package com.mia_princz.mse.execution.trading.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The body of a new simulation: what is refused, and what counts as the same request. */
class SimulationBodyTest {

	/** A retry is the same request whatever white space its client put around the name, and another for another name. */
	@Test
	void theRequestIsTheNameAsItIsKept() {
		SimulationBody.Valid plain = new SimulationBody("Thursday seminar").validate();
		assertThat(plain.name().value()).isEqualTo("Thursday seminar");
		assertThat(plain.hash()).isEqualTo(CommandCalls.sha256("Thursday seminar"));
		assertThat(new SimulationBody("  Thursday seminar\n").validate().hash()).isEqualTo(plain.hash());
		assertThat(new SimulationBody("thursday seminar").validate().hash()).isNotEqualTo(plain.hash());
	}

	@Test
	void aMissingOrBrokenNameIsAFieldError() {
		assertThatThrownBy(() -> new SimulationBody(null).validate())
			.isInstanceOfSatisfying(ApiException.class, (refused) -> assertThat(errors(refused)).containsExactly("name REQUIRED"));
		for (String name : List.of("", "   ", "x".repeat(81), "two\nlines", "zero​width")) {
			assertThatThrownBy(() -> new SimulationBody(name).validate()).as("'%s'", name)
				.isInstanceOfSatisfying(ApiException.class, (refused) -> {
					assertThat(refused.status().value()).isEqualTo(400);
					assertThat(errors(refused)).containsExactly("name INVALID_NAME");
				});
		}
	}

	@SuppressWarnings("unchecked")
	private static List<String> errors(ApiException refused) {
		Object errors = refused.toProblem().getProperties().get("errors");
		return ((List<ApiException.FieldError>) errors).stream().map((error) -> error.field() + " " + error.code()).toList();
	}

}
