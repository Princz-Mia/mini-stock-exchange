package com.mia_princz.mse.execution.trading.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.mia_princz.mse.execution.outbox.OutboxAdmission;
import com.mia_princz.mse.execution.outbox.OutboxRelay;
import com.mia_princz.mse.execution.trading.application.CommandWriter;

/** Each figure of the summary comes from its own source, unswapped, with the relay's figures null where it is absent. */
class HealthControllerTest {

	private final AtomicLong nanos = new AtomicLong();

	private final CommandActivity activity = new CommandActivity(nanos::get, Duration.ofMillis(150));

	@SuppressWarnings("unchecked")
	private final ObjectProvider<OutboxRelay> relay = mock(ObjectProvider.class);

	private final OutboxAdmission admission = mock(OutboxAdmission.class);

	@Test
	void reportsEachFigureFromItsSource() throws Exception {
		for (int i = 0; i < 4; i++) {
			activity.finished(true, Duration.ofMillis(10));
		}
		activity.finished(true, Duration.ofMillis(400));
		activity.finished(false, Duration.ofMillis(20));
		activity.finished(false, Duration.ofMillis(900));
		for (int i = 0; i < 3; i++) {
			activity.refused();
		}
		OutboxRelay outbox = mock(OutboxRelay.class);
		when(outbox.pendingEvents()).thenReturn(12L);
		when(outbox.oldestPendingAge()).thenReturn(Duration.ofMillis(3_400));
		when(relay.getIfAvailable()).thenReturn(outbox);
		when(admission.admitsNewOrders()).thenReturn(false);

		CountDownLatch release = new CountDownLatch(1);
		try (CommandWriter writer = new CommandWriter("health-test", 8)) {
			CountDownLatch running = new CountDownLatch(1);
			writer.submit(() -> {
				running.countDown();
				return release.await(5, TimeUnit.SECONDS);
			});
			running.await();
			CompletableFuture<String> queued = writer.submit(() -> "queued");
			writer.submit(() -> "queued too");

			HealthController.Summary summary = new HealthController(writer, activity, admission, relay).summary();

			assertThat(summary.writer()).isEqualTo(new HealthController.Writer(true, 2, 8));
			assertThat(summary.lastMinute()).isEqualTo(new HealthController.LastMinute(5, 2, 5, 3));
			assertThat(summary.outbox()).isEqualTo(new HealthController.Outbox(false, 12L, 3_400L));
			release.countDown();
			assertThat(queued.get(5, TimeUnit.SECONDS)).isEqualTo("queued");
		}
	}

	@Test
	void hasNoRelayFiguresWhereTheRelayDoesNotRun() throws Exception {
		when(relay.getIfAvailable()).thenReturn(null);
		when(admission.admitsNewOrders()).thenReturn(true);
		try (CommandWriter writer = new CommandWriter("health-test", 4)) {
			HealthController.Summary summary = new HealthController(writer, activity, admission, relay).summary();

			assertThat(summary.outbox()).isEqualTo(new HealthController.Outbox(true, null, null));
			assertThat(summary.writer()).isEqualTo(new HealthController.Writer(true, 0, 4));
			assertThat(summary.lastMinute()).isEqualTo(new HealthController.LastMinute(0, 0, 0, 0));
		}
	}

}
