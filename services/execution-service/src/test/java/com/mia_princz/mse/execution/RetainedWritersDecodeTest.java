package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.mia_princz.mse.contracts.events.RetainedWriters;

/**
 * Every retained writer schema, encoded as its producer would and decoded with this module's own runtime and Avro.
 * Execution publishes trades and snapshots from them and must read whatever is on its own topics after an upgrade.
 */
class RetainedWritersDecodeTest {

	@Test
	void everyRetainedWriterDecodesHere() {
		assertThat(RetainedWriters.verifyEveryWriterDecodes()).isEqualTo(RetainedWriters.all().size()).isPositive();
	}

}
