package com.mia_princz.mse.execution.trading.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The request for creating bots. The strategy is required, and the hash appends it last, as it did while it was
 * optional, so tagged requests stored then still replay.
 */
class BotsBodyTest {

	@Test
	void aStrategyIsRequired() {
		assertThatThrownBy(() -> new BotsBody(3, "500.00", List.of(), null).validate())
			.isInstanceOfSatisfying(ApiException.class,
					(refused) -> assertThat(refused.toProblem().getProperties().get("errors"))
						.isEqualTo(List.of(new ApiException.FieldError("strategy", "REQUIRED",
								"strategy is required: MARKET_MAKER, NOISE or MOMENTUM"))));
	}

	@Test
	void aStrategyIsAppendedLastAndTellsBatchesApart() {
		BotsBody.Valid noise = new BotsBody(3, "500.00", List.of(), "NOISE").validate();
		assertThat(noise.hash()).isEqualTo(CommandCalls.sha256("3|50000||NOISE"));
		assertThat(noise.hash()).isNotEqualTo(new BotsBody(3, "500.00", List.of(), "MOMENTUM").validate().hash());
	}

}
