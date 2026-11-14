package com.mia_princz.mse.execution.trading.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The request hash for creating bots. The demo seed resumes with fixed keys, so without a strategy the hash is exactly
 * the one keys had before bots had tags.
 */
class BotsBodyTest {

	@Test
	void withoutAStrategyTheHashIsTheEarlierOne() {
		BotsBody.Valid plain = new BotsBody(3, "500.00", List.of(), null).validate();
		assertThat(plain.hash()).isEqualTo(CommandCalls.sha256("3|50000|"));
	}

	@Test
	void aStrategyIsAppendedLastAndTellsBatchesApart() {
		BotsBody.Valid noise = new BotsBody(3, "500.00", List.of(), "NOISE").validate();
		assertThat(noise.hash()).isEqualTo(CommandCalls.sha256("3|50000||NOISE"));
		assertThat(noise.hash()).isNotEqualTo(new BotsBody(3, "500.00", List.of(), "MOMENTUM").validate().hash());
	}

}
