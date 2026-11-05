package com.mia_princz.mse.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.mia_princz.mse.contracts.events.RetainedWriters;

/**
 * Every retained writer schema, encoded as its producer would and decoded with this module's own runtime and Avro.
 * Market Data rebuilds from retained topics, so it must read every writer still on them.
 */
class RetainedWritersDecodeTest {

	@Test
	void everyRetainedWriterDecodesHere() {
		assertThat(RetainedWriters.verifyEveryWriterDecodes()).isEqualTo(RetainedWriters.all().size()).isPositive();
	}

}
