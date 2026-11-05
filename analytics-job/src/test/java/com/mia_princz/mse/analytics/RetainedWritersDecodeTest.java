package com.mia_princz.mse.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.mia_princz.mse.contracts.events.RetainedWriters;

/**
 * Every retained writer schema, encoded as its producer would and decoded with this module's own runtime and Avro.
 * The job reads retained trades on JDK 17, through the copy of the contracts shaded into it.
 */
class RetainedWritersDecodeTest {

	@Test
	void everyRetainedWriterDecodesHere() {
		assertThat(RetainedWriters.verifyEveryWriterDecodes()).isEqualTo(RetainedWriters.all().size()).isPositive();
	}

}
