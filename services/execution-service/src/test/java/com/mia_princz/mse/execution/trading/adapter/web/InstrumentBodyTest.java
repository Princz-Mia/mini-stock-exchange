package com.mia_princz.mse.execution.trading.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Request hashes for instruments. Keys stored before display names existed must still match their retries, so without
 * a name the hash is exactly the earlier one: the demo seed resumes with fixed keys.
 */
class InstrumentBodyTest {

	@Test
	void anAddWithoutANameHashesAsBeforeNamesExisted() {
		InstrumentBody.Valid plain = new InstrumentBody("NOVA", "100.00", null).validate();
		assertThat(plain.hash()).isEqualTo(CommandCalls.sha256("NOVA|100.00"));
		InstrumentBody.Valid named = new InstrumentBody("NOVA", "100.00", "Nova Dynamics").validate();
		assertThat(named.hash()).isEqualTo(CommandCalls.sha256("NOVA|100.00|Nova Dynamics"));
		assertThat(named.hash()).isNotEqualTo(new InstrumentBody("NOVA", "100.00", "Nova").validate().hash());
	}

	@Test
	void anEditWithoutANameHashesAsBeforeNamesExisted() {
		UUID instrument = UUID.fromString("00000000-0000-0000-0000-000000000001");
		assertThat(new InstrumentEditBody("120.50", null).validate(instrument).hash())
			.isEqualTo(CommandCalls.sha256(instrument + "|120.50"));
		assertThat(new InstrumentEditBody("120.50", "Nova").validate(instrument).hash())
			.isEqualTo(CommandCalls.sha256(instrument + "|120.50|Nova"));
	}

	@Test
	void anInvalidNameIsAFieldErrorNextToTheOthers() {
		assertThatThrownBy(() -> new InstrumentBody("nova", "100.00", " Nova").validate())
			.isInstanceOfSatisfying(ApiException.class, (refused) -> assertThat(errors(refused))
				.containsExactlyInAnyOrder("symbol INVALID_SYMBOL", "displayName INVALID_DISPLAY_NAME"));
		assertThatThrownBy(() -> new InstrumentEditBody(null, "Nova  Dynamics").validate(UUID.randomUUID()))
			.isInstanceOfSatisfying(ApiException.class, (refused) -> assertThat(errors(refused))
				.containsExactlyInAnyOrder("referencePrice REQUIRED", "displayName INVALID_DISPLAY_NAME"));
	}

	@SuppressWarnings("unchecked")
	private static List<String> errors(ApiException refused) {
		Object errors = refused.toProblem().getProperties().get("errors");
		return ((List<ApiException.FieldError>) errors).stream().map((error) -> error.field() + " " + error.code()).toList();
	}

}
