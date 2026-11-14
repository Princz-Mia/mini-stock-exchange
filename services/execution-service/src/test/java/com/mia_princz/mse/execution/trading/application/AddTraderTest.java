package com.mia_princz.mse.execution.trading.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** A logged command never carries the password hash; a trader without an account is not funded. */
class AddTraderTest {

	@Test
	void theCommandsTextLeavesTheHashOut() {
		AddTrader funded = new AddTrader(UUID.randomUUID(), UUID.randomUUID(), "maria", "{bcrypt}$2a$10$secret-hash",
				100, List.of(), UUID.randomUUID());
		assertThat(funded.toString()).contains("maria").doesNotContain("secret-hash").doesNotContain("bcrypt");
		assertThat(funded.funded()).isTrue();
		assertThat(new AddTrader(UUID.randomUUID(), null, "omar", "{bcrypt}x", 0, List.of(), UUID.randomUUID())
			.funded()).isFalse();
	}

}
