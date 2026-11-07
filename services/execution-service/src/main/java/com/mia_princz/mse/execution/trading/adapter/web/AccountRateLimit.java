package com.mia_princz.mse.execution.trading.adapter.web;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import com.mia_princz.mse.execution.identity.BotPrincipal;

/**
 * Each account's trading commands per second, checked before a command reaches the writer: one rate for bots, a higher
 * one for people. A refused command is never queued and creates nothing, so the same key can be sent again after
 * Retry-After.
 */
@Component
class AccountRateLimit {

	private final Limit bots;

	private final Limit people;

	AccountRateLimit(@Value("${mse.trading.bot-commands-per-second}") int botsPerSecond,
			@Value("${mse.trading.human-commands-per-second}") int peoplePerSecond, MeterRegistry meters) {
		this.bots = new Limit(botsPerSecond, "bot", meters);
		this.people = new Limit(peoplePerSecond, "human", meters);
	}

	/** Takes one command for the account the credential trades for, or refuses it with a 429. */
	void check(Authentication authentication, UUID accountId) {
		Limit limit = (authentication.getPrincipal() instanceof BotPrincipal) ? bots : people;
		long wait = limit.limiter.acquire(accountId);
		if (wait > 0) {
			limit.refused.increment();
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
					"This account sent too many trading commands; nothing was queued, retry the same key in a moment")
				.retryAfter((int) Math.max(1, TimeUnit.NANOSECONDS.toSeconds(wait + TimeUnit.SECONDS.toNanos(1) - 1)));
		}
	}

	private record Limit(RateLimiter limiter, Counter refused) {

		Limit(int perSecond, String account, MeterRegistry meters) {
			this(new RateLimiter(perSecond, System::nanoTime), Counter.builder("mse.commands.rate.limited")
				.description("Trading commands refused by a per-account rate limit")
				.tag("account", account)
				.register(meters));
		}

	}

}
