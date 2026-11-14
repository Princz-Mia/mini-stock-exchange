package com.mia_princz.mse.execution.trading.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/** An answer to creating bots stored before bots had tags still reads back, with no tag, as the store reads it. */
class StoredBotAnswersTest {

	@Test
	void aBatchStoredWithoutATagReadsBackWithNone() {
		BotsResult result = JsonMapper.builder().build().readValue("""
				{"status":"CREATED","created":3,"firstBot":"bot-0001","lastBot":"bot-0003","population":3,
				 "commandSequence":5}""", BotsResult.class);
		assertThat(result.strategy()).isNull();
		assertThat(result.population()).isEqualTo(3);
	}

}
