package com.mia_princz.mse.execution.trading.adapter.web;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mia_princz.mse.execution.outbox.OutboxAdmission;
import com.mia_princz.mse.execution.outbox.OutboxRelay;
import com.mia_princz.mse.execution.trading.application.CommandWriter;

/**
 * How trading is keeping up, for the operator's health panel: the writer and its queue, the last minute of commands,
 * and the publication outbox. The same figures as the dashboard's, read in-process, so polling it every two seconds
 * costs nothing; the admission check reads the backlog at most once per refresh interval, shared with order
 * placement, so a quiet market does not leave a stale "paused". Operator only, by the security configuration.
 */
@RestController
@RequestMapping("/api/v1/admin/health")
class HealthController {

	record Writer(boolean available, int queueDepth, int queueCapacity) {
	}

	record LastMinute(long committed, long failed, long withinBudget, long refused) {
	}

	/** {@code pendingEvents} and {@code oldestPendingMillis} are null where the relay does not run. */
	record Outbox(boolean admitting, Long pendingEvents, Long oldestPendingMillis) {
	}

	record Summary(Writer writer, LastMinute lastMinute, Outbox outbox) {
	}

	private final CommandWriter writer;

	private final CommandActivity activity;

	private final OutboxAdmission admission;

	private final ObjectProvider<OutboxRelay> relay;

	HealthController(CommandWriter writer, CommandActivity activity, OutboxAdmission admission,
			ObjectProvider<OutboxRelay> relay) {
		this.writer = writer;
		this.activity = activity;
		this.admission = admission;
		this.relay = relay;
	}

	@GetMapping
	Summary summary() {
		CommandActivity.Window window = activity.lastMinute();
		OutboxRelay outbox = relay.getIfAvailable();
		return new Summary(new Writer(writer.isAvailable(), writer.queueDepth(), writer.queueCapacity()),
				new LastMinute(window.committed(), window.failed(), window.withinBudget(), window.refused()),
				new Outbox(admission.admitsNewOrders(), outbox == null ? null : outbox.pendingEvents(),
						outbox == null ? null : outbox.oldestPendingAge().toMillis()));
	}

}
