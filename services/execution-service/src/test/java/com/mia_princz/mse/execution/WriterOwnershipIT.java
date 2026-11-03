package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.mia_princz.mse.execution.trading.adapter.TradingStartup;
import com.mia_princz.mse.execution.trading.adapter.WriterOwnership;
import com.mia_princz.mse.execution.trading.application.CommandWriter;
import com.mia_princz.mse.execution.trading.application.TradingEngine;
import com.mia_princz.mse.execution.trading.application.TradingStore;

/** The running application already owns the market here, exactly as a second process would find it. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class WriterOwnershipIT {

	@Autowired
	DataSource dataSource;

	@Autowired
	WriterOwnership applicationOwnership;

	@Autowired
	TradingStartup trading;

	@Autowired
	TradingStore store;

	@Autowired
	TradingEngine engine;

	@Autowired
	CommandWriter writer;

	@Test
	void theRunningApplicationOwnsTheMarketAndIsReady() {
		assertThat(applicationOwnership.isHeld()).isTrue();
		assertThat(trading.health().getStatus()).isEqualTo(Status.UP);
	}

	@Test
	void aSecondInstanceCannotTakeOwnershipAndRefusesToStart() throws Exception {
		try (WriterOwnership second = new WriterOwnership(dataSource)) {
			assertThat(second.tryAcquire()).isFalse();
			assertThat(second.isHeld()).isFalse();

			TradingStartup secondStartup = new TradingStartup(second, store, engine, writer);
			assertThatThrownBy(() -> secondStartup.run(null)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("owns the market");
		}
	}

	@Test
	void losingOwnershipFailsReadinessAndLetsAnotherProcessTakeOver() throws Exception {
		try {
			applicationOwnership.close();
			assertThat(trading.health().getStatus()).isEqualTo(Status.DOWN);

			try (WriterOwnership next = new WriterOwnership(dataSource)) {
				assertThat(next.tryAcquire()).as("the lock is free once released").isTrue();
				assertThat(next.isHeld()).isTrue();
			}
		}
		finally {
			assertThat(applicationOwnership.tryAcquire()).isTrue();
		}
		assertThat(trading.health().getStatus()).isEqualTo(Status.UP);
	}

}
