package com.mia_princz.mse.execution.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

class OutboxAdmissionTest {

	private static final OutboxAdmission.Limits LIMITS = new OutboxAdmission.Limits(100, 10_000, Duration.ofSeconds(1));

	private final FakeOutbox outbox = new FakeOutbox();

	private final MutableClock clock = new MutableClock();

	private final OutboxAdmission admission = new OutboxAdmission(outbox, LIMITS, clock);

	@Test
	void admitsWhileBothLimitsHold() {
		outbox.backlog = new Outbox.Backlog(99, 9_999, Instant.EPOCH);

		assertThat(admission.admitsNewOrders()).isTrue();
	}

	@Test
	void eitherLimitStopsNewOrders() {
		outbox.backlog = new Outbox.Backlog(100, 1, Instant.EPOCH);
		assertThat(admission.admitsNewOrders()).as("event limit").isFalse();

		clock.advance(Duration.ofSeconds(1));
		outbox.backlog = new Outbox.Backlog(1, 10_000, Instant.EPOCH);
		assertThat(admission.admitsNewOrders()).as("byte limit").isFalse();
		assertThat(admission.isOpen()).isFalse();
	}

	@Test
	void readsTheBacklogAtMostOncePerInterval() {
		admission.admitsNewOrders();
		outbox.backlog = new Outbox.Backlog(1_000, 0, Instant.EPOCH);

		assertThat(admission.admitsNewOrders()).as("still the first reading").isTrue();
		clock.advance(Duration.ofMillis(999));
		assertThat(admission.admitsNewOrders()).isTrue();
		assertThat(outbox.reads).isEqualTo(1);

		clock.advance(Duration.ofMillis(1));
		assertThat(admission.admitsNewOrders()).isFalse();
		assertThat(outbox.reads).isEqualTo(2);
	}

	@Test
	void reopensOnceTheBacklogDrains() {
		outbox.backlog = new Outbox.Backlog(500, 0, Instant.EPOCH);
		assertThat(admission.admitsNewOrders()).isFalse();

		outbox.backlog = new Outbox.Backlog(0, 0, null);
		clock.advance(Duration.ofSeconds(1));

		assertThat(admission.admitsNewOrders()).isTrue();
	}

	private static final class FakeOutbox implements Outbox {

		Backlog backlog = Backlog.EMPTY;

		int reads;

		@Override
		public List<PendingEvent> pending(int limit) {
			return List.of();
		}

		@Override
		public void markPublished(List<Long> ids) {
		}

		@Override
		public Backlog backlog() {
			reads++;
			return backlog;
		}

	}

	private static final class MutableClock extends Clock {

		private Instant now = Instant.parse("2026-11-03T12:00:00Z");

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

	}

}
