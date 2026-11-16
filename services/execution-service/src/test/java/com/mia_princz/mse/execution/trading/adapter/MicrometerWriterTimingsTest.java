package com.mia_princz.mse.execution.trading.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import com.mia_princz.mse.execution.trading.application.WriterTimings.Phase;

/** The writer's timings land in their own timer, by phase and command, and nothing else does. */
class MicrometerWriterTimingsTest {

	@Test
	void eachPhaseAndCommandHasItsOwnTimerFromTheStart() {
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		new MicrometerWriterTimings(meters);

		for (String name : new String[] { "mse.writer.queue.wait", "mse.writer.begin", "mse.writer.work",
				"mse.writer.commit", "mse.writer.apply" }) {
			for (String command : new String[] { "place", "cancel", "other" }) {
				assertThat(meters.find(name).tag("command", command).timer()).as(name + " " + command).isNotNull();
			}
		}
	}

	@Test
	void aTimingIsRecordedWhereItBelongs() {
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		MicrometerWriterTimings timings = new MicrometerWriterTimings(meters);

		timings.record(Phase.COMMIT, "place", 3_000_000);
		timings.record(Phase.COMMIT, "place", 1_000_000);
		timings.record(Phase.WORK, "cancel", 2_000_000);

		Timer commit = meters.get("mse.writer.commit").tag("command", "place").timer();
		assertThat(commit.count()).isEqualTo(2);
		assertThat(commit.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(4.0);
		assertThat(meters.get("mse.writer.work").tag("command", "cancel").timer().count()).isEqualTo(1);
		assertThat(meters.get("mse.writer.work").tag("command", "place").timer().count()).isZero();
		assertThat(meters.get("mse.writer.commit").tag("command", "cancel").timer().count()).isZero();
	}

	@Test
	void anUnknownCommandOrANegativeTimeIsIgnored() {
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		MicrometerWriterTimings timings = new MicrometerWriterTimings(meters);

		timings.record(Phase.WORK, "something else", 1_000);
		timings.record(Phase.WORK, "place", -1);

		assertThat(meters.find("mse.writer.work").tag("command", "something else").timer()).isNull();
		assertThat(meters.get("mse.writer.work").tag("command", "place").timer().count()).isZero();
	}

}
