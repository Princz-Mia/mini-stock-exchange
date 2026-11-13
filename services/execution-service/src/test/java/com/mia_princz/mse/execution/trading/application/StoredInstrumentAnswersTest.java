package com.mia_princz.mse.execution.trading.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/** Answers stored before instruments had display names still read back, with no name, as the store reads them. */
class StoredInstrumentAnswersTest {

	private static final JsonMapper STORE = JsonMapper.builder().build();

	@Test
	void anAddStoredWithoutANameReadsBackWithNone() {
		InstrumentResult result = STORE.readValue("""
				{"status":"CREATED","instrumentId":"00000000-0000-0000-0000-000000000001","symbol":"NOVA",
				 "referencePriceCents":10000,"commandSequence":3}""", InstrumentResult.class);
		assertThat(result.displayName()).isNull();
		assertThat(result.referencePriceCents()).isEqualTo(10_000);
	}

	@Test
	void anEditStoredWithoutANameReadsBackWithNone() {
		InstrumentEdit result = STORE.readValue("""
				{"status":"UPDATED","instrumentId":"00000000-0000-0000-0000-000000000001","symbol":"NOVA",
				 "referencePriceCents":12050,"commandSequence":9}""", InstrumentEdit.class);
		assertThat(result.displayName()).isNull();
		assertThat(result.commandSequence()).isEqualTo(9);
	}

}
