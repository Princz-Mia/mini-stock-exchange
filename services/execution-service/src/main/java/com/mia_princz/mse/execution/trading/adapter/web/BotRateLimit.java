package com.mia_princz.mse.execution.trading.adapter.web;

import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import com.mia_princz.mse.execution.identity.BotPrincipal;

/**
 * Each bot account's trading commands per second, checked before a command reaches the writer. A refused command is
 * never queued and creates nothing, so the bot can send the same key again after Retry-After.
 */
@Component
class BotRateLimit {

	private final RateLimiter limiter;

	private final Counter refused;

	BotRateLimit(@Value("${mse.trading.bot-commands-per-second}") int perSecond, MeterRegistry meters) {
		this.limiter = new RateLimiter(perSecond, System::nanoTime);
		this.refused = Counter.builder("mse.commands.rate.limited")
			.description("Trading commands refused by a per-account rate limit")
			.tag("account", "bot")
			.register(meters);
	}

	void check(Authentication authentication) {
		if (!(authentication.getPrincipal() instanceof BotPrincipal bot)) {
			return;
		}
		long wait = limiter.acquire(bot.accountId());
		if (wait > 0) {
			refused.increment();
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
					"This bot account sent too many trading commands; nothing was queued, retry the same key later")
				.retryAfter((int) Math.max(1, TimeUnit.NANOSECONDS.toSeconds(wait + TimeUnit.SECONDS.toNanos(1) - 1)));
		}
	}

}
