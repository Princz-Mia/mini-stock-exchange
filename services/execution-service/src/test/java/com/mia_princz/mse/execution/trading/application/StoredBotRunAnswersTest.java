package com.mia_princz.mse.execution.trading.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/** Answers to starting a run read back as the store reads them, including ones stored before runs assigned by tag. */
class StoredBotRunAnswersTest {

	private static final JsonMapper STORED = JsonMapper.builder().build();

	@Test
	void aRefusalStoredBeforeRunsAssignedByTagReadsBackWithNoShortfall() {
		BotRunResult result = STORED.readValue("""
				{"status":"NOT_ENOUGH_BOTS","runId":null,"bots":0,"commandSequence":5}""", BotRunResult.class);
		assertThat(result.status()).isEqualTo(BotRunResult.Status.NOT_ENOUGH_BOTS);
		assertThat(result.shortfall()).isNull();
	}

	@Test
	void aShortfallIsStoredAndReadBack() {
		BotRunResult refused = new BotRunResult(BotRunResult.Status.NOT_ENOUGH_BOTS, null, 0, 9,
				new BotRunResult.Shortfall("MOMENTUM", 3, 1));
		assertThat(STORED.readValue(STORED.writeValueAsString(refused), BotRunResult.class)).isEqualTo(refused);
	}

}
