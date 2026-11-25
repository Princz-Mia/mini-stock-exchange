package com.mia_princz.mse.execution.trading.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.mia_princz.mse.execution.identity.BotPrincipal;

/**
 * What is kept per account is dropped when another simulation starts: an account belongs to one simulation, so none
 * kept before is ever asked about again, and without this each simulation would leave its accounts behind for the
 * life of the process.
 */
class SimulationChangesTest {

	/** People and bots have a limit each, and most accounts are bots': both are forgotten. */
	@Test
	void theRateLimitsForgetEveryAccountOfPeopleAndOfBots() {
		AccountRateLimit limit = new AccountRateLimit(1, 1, new SimpleMeterRegistry());
		TestingAuthenticationToken person = new TestingAuthenticationToken("trader", "n/a");
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		UUID botAccount = UUID.randomUUID();
		PreAuthenticatedAuthenticationToken bot = new PreAuthenticatedAuthenticationToken(
				new BotPrincipal(UUID.randomUUID(), botAccount), null);
		limit.check(person, first);
		limit.check(person, second);
		assertThat(limit.accountsKept()).isEqualTo(2);
		limit.check(bot, botAccount);
		assertThat(limit.accountsKept()).as("two people's accounts and a bot's").isEqualTo(3);
		assertThatThrownBy(() -> limit.check(person, first)).as("one command a second")
			.isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> limit.check(bot, botAccount)).as("and one for a bot")
			.isInstanceOf(ApiException.class);

		limit.started(UUID.randomUUID(), UUID.randomUUID());

		assertThat(limit.accountsKept()).isZero();
		// Nothing of the old schedule is left: each account's next command goes at once, as its first did.
		limit.check(person, first);
		assertThat(limit.accountsKept()).isEqualTo(1);
		limit.check(bot, botAccount);
		assertThat(limit.accountsKept()).isEqualTo(2);
	}

	@Test
	void theStreamsOfTheSimulationBeforeAreClosedAndItsVersionsForgotten() throws Exception {
		List<SseEmitter> emitters = new java.util.concurrent.CopyOnWriteArrayList<>();
		try (AccountStream stream = new AccountStream(() -> {
			SseEmitter emitter = new SseEmitter(60_000L);
			emitters.add(emitter);
			return emitter;
		}, (session) -> true, new AccountStream.Settings(Duration.ofSeconds(10), Duration.ofSeconds(30), 10, 5),
				Clock.systemUTC())) {
			UUID watched = UUID.randomUUID();
			UUID silent = UUID.randomUUID();
			stream.subscribe(watched, "session-a");
			stream.subscribe(watched, "session-b");
			stream.changed(Set.of(watched, silent), 42);
			assertThat(stream.openStreams()).isEqualTo(2);
			assertThat(stream.accountsKept()).as("one with streams and a version, one with a version only").isEqualTo(2);

			stream.started(UUID.randomUUID(), UUID.randomUUID());

			assertThat(stream.openStreams()).isZero();
			assertThat(stream.accountsKept()).isZero();
			// An account of the new simulation that reuses nothing starts from nothing, and streams work as before.
			UUID next = UUID.randomUUID();
			stream.subscribe(next, "session-a");
			assertThat(stream.openStreams()).isEqualTo(1);
			assertThat(stream.accountsKept()).isEqualTo(1);
		}
		assertThat(emitters).hasSize(3);
	}

}
