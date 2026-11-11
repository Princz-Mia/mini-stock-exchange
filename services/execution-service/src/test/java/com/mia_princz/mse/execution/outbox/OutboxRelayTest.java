package com.mia_princz.mse.execution.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;

import com.mia_princz.mse.execution.outbox.OutboxRelay.OutboxPublishException;

/**
 * Per-key order on Kafka, without a broker and without timing. The fake producer acknowledges a send only when the
 * relay waits for it, and it assumes the worst of a send: anything handed to it may reach Kafka, even if the relay
 * never sees the acknowledgement. So a later event handed over while an earlier one of its key is still unconfirmed
 * shows up on Kafka ahead of that earlier event's retry.
 */
class OutboxRelayTest {

	private static final OutboxRelay.Settings SETTINGS = new OutboxRelay.Settings(50, Duration.ofMillis(20),
			Duration.ofSeconds(1), Duration.ofMillis(200));

	private final MemoryOutbox outbox = new MemoryOutbox();

	private final FakeKafka kafka = new FakeKafka();

	private final OutboxRelay relay = new OutboxRelay(outbox, kafka, () -> true, SETTINGS, Clock.systemUTC());

	@Test
	void aFailedEventHoldsBackTheLaterEventsOfItsKey() throws Exception {
		long first = outbox.add("trades", "k", "first");
		long second = outbox.add("trades", "k", "second");
		kafka.failOnce("first");

		assertThatThrownBy(relay::relayOnce).isInstanceOf(OutboxPublishException.class)
			.hasMessageContaining("outbox event " + first + " ");
		assertThat(kafka.handedOver()).as("second is not handed over while first is unconfirmed")
			.containsExactly("first");
		assertThat(outbox.pendingIds()).containsExactly(first, second);

		assertThat(relay.relayOnce()).isEqualTo(2);
		assertThat(kafka.onTopic("trades", "k")).containsExactly("first", "second");
		assertThat(outbox.pendingIds()).isEmpty();
	}

	@Test
	void theNextEventOfAKeyWaitsForTheAcknowledgementNotOnlyForTheSend() throws Exception {
		outbox.add("trades", "k", "first");
		outbox.add("trades", "k", "second");

		assertThat(relay.relayOnce()).isEqualTo(2);
		assertThat(kafka.log()).containsExactly("send first", "ack first", "send second", "ack second");
	}

	@Test
	void otherKeysAndTopicsGoOutTogether() throws Exception {
		outbox.add("trades", "a", "a1");
		outbox.add("trades", "b", "b1");
		outbox.add("market", "a", "snapshot");
		outbox.add("trades", "a", "a2");
		outbox.add("trades", "b", "b2");

		assertThat(relay.relayOnce()).isEqualTo(5);
		assertThat(kafka.log()).containsExactly("send a1", "send b1", "send snapshot", "ack a1", "ack b1",
				"ack snapshot", "send a2", "send b2", "ack a2", "ack b2");
	}

	@Test
	void afterAFailureOnlyTheAcknowledgedPrefixIsMarkedAndTheFailedEventIsNamed() throws Exception {
		long a1 = outbox.add("trades", "a", "a1");
		long a2 = outbox.add("trades", "a", "a2");
		long b1 = outbox.add("trades", "b", "b1");
		long b2 = outbox.add("trades", "b", "b2");
		kafka.failOnce("a2");

		// a2 fails in the second round. b1 was acknowledged in the first, but its id is after a2's, so it stays pending.
		assertThatThrownBy(relay::relayOnce).hasMessageContaining("outbox event " + a2 + " ");
		assertThat(outbox.pendingIds()).doesNotContain(a1).containsExactly(a2, b1, b2);
		assertThat(relay.publishedCount()).isEqualTo(1);

		assertThat(relay.relayOnce()).isEqualTo(3);
		assertThat(kafka.onTopic("trades", "a")).containsExactly("a1", "a2");
		assertThat(kafka.onTopic("trades", "b")).as("resent in order: duplicates, never reordered")
			.containsExactly("b1", "b2", "b1", "b2");
		assertThat(outbox.pendingIds()).isEmpty();
	}

	@Test
	void aFailureStopsThePassBeforeTheNextRoundOfAnyKey() throws Exception {
		long a1 = outbox.add("trades", "a", "a1");
		outbox.add("trades", "a", "a2");
		long b1 = outbox.add("trades", "b", "b1");
		kafka.failOnce("b1");

		// The first unconfirmed event by id is a2, which was never sent; the error names the one that failed.
		assertThatThrownBy(relay::relayOnce).hasMessageContaining("outbox event " + b1 + " ");
		assertThat(kafka.handedOver()).containsExactly("a1", "b1");
		assertThat(outbox.pendingIds()).doesNotContain(a1).hasSize(2);
	}

	/** Pending rows in id order, as the table returns them. */
	private static final class MemoryOutbox implements Outbox {

		private final TreeMap<Long, PendingEvent> pending = new TreeMap<>();

		private long nextId = 1;

		long add(String topic, String key, String value) {
			long id = nextId++;
			pending.put(id, new PendingEvent(id, UUID.randomUUID(), topic, key, value.getBytes(StandardCharsets.UTF_8)));
			return id;
		}

		List<Long> pendingIds() {
			return List.copyOf(pending.keySet());
		}

		@Override
		public List<PendingEvent> pending(int limit) {
			return pending.values().stream().limit(limit).toList();
		}

		@Override
		public void markPublished(List<Long> ids) {
			for (long id : ids) {
				assertThat(pending.remove(id)).as("marked once").isNotNull();
			}
		}

		@Override
		public Backlog backlog() {
			return new Backlog(pending.size(), 0, null);
		}

	}

	/** Producers that share one log and one topic; each failing value fails on its first send only. */
	private static final class FakeKafka implements Supplier<Producer<String, byte[]>> {

		private final List<String> log = new ArrayList<>();

		private final List<ProducerRecord<String, byte[]>> topic = new ArrayList<>();

		private final Set<String> failing = new HashSet<>();

		void failOnce(String value) {
			failing.add(value);
		}

		List<String> log() {
			return List.copyOf(log);
		}

		List<String> handedOver() {
			return log.stream().filter((line) -> line.startsWith("send ")).map((line) -> line.substring(5)).toList();
		}

		List<String> onTopic(String name, String key) {
			return topic.stream()
				.filter((record) -> record.topic().equals(name) && record.key().equals(key))
				.map(FakeKafka::value)
				.toList();
		}

		@Override
		@SuppressWarnings("unchecked")
		public Producer<String, byte[]> get() {
			return (Producer<String, byte[]>) Proxy.newProxyInstance(Producer.class.getClassLoader(),
					new Class<?>[] { Producer.class }, (proxy, method, args) -> switch (method.getName()) {
						case "send" -> send((ProducerRecord<String, byte[]>) args[0]);
						case "close" -> null;
						case "toString" -> "FakeKafka producer";
						default -> throw new UnsupportedOperationException(method.getName());
					});
		}

		private Future<RecordMetadata> send(ProducerRecord<String, byte[]> record) {
			String value = value(record);
			log.add("send " + value);
			boolean fails = failing.remove(value);
			if (!fails) {
				topic.add(record);
			}
			return new AckOnWait(value, fails);
		}

		private static String value(ProducerRecord<String, byte[]> record) {
			return new String(record.value(), StandardCharsets.UTF_8);
		}

		/** Unresolved until someone waits for it, so a relay that does not wait cannot see it either way. */
		private final class AckOnWait implements Future<RecordMetadata> {

			private final String value;

			private final boolean fails;

			private boolean done;

			AckOnWait(String value, boolean fails) {
				this.value = value;
				this.fails = fails;
			}

			@Override
			public RecordMetadata get(long timeout, TimeUnit unit) throws ExecutionException {
				return get();
			}

			@Override
			public RecordMetadata get() throws ExecutionException {
				if (!done) {
					done = true;
					log.add((fails ? "fail " : "ack ") + value);
				}
				if (fails) {
					throw new ExecutionException(new TimeoutException("simulated lost acknowledgement"));
				}
				return null;
			}

			@Override
			public boolean isDone() {
				return done;
			}

			@Override
			public boolean cancel(boolean mayInterruptIfRunning) {
				return false;
			}

			@Override
			public boolean isCancelled() {
				return false;
			}

		}

	}

}
