package com.mia_princz.mse.execution.trading.adapter.web;

import java.time.Clock;
import java.time.Duration;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Configuration(proxyBeanMethods = false)
class AccountEventsConfiguration {

	/**
	 * A session counts as active while the session store still has it unexpired: logging out deletes it, and an
	 * expired one is no longer returned. Each heartbeat checks this for every open stream.
	 */
	@Bean(initMethod = "start", destroyMethod = "close")
	AccountStream accountStream(SessionRepository<? extends Session> sessions, Clock clock, MeterRegistry meters,
			@Value("${mse.account-events.heartbeat:10s}") Duration heartbeat,
			@Value("${mse.account-events.slow-limit:30s}") Duration slowLimit,
			@Value("${mse.account-events.lifetime:30m}") Duration lifetime,
			@Value("${mse.account-events.max-streams:500}") int maxStreams,
			@Value("${mse.account-events.max-per-account:5}") int maxPerAccount) {
		AccountStream stream = new AccountStream(() -> new SseEmitter(lifetime.toMillis()), (sessionId) -> {
			Session session = sessions.findById(sessionId);
			return session != null && !session.isExpired();
		}, new AccountStream.Settings(heartbeat, slowLimit, maxStreams, maxPerAccount), clock);
		Gauge.builder("mse.account.events.streams", stream, AccountStream::openStreams)
			.description("Open private account streams")
			.register(meters);
		return stream;
	}

	/** Logging out closes the session's streams at once, rather than at the next heartbeat. */
	@Bean
	LogoutHandler closeAccountStreams(AccountStream stream) {
		return (request, response, authentication) -> {
			String sessionId = request.getRequestedSessionId();
			if (sessionId != null) {
				stream.closeSession(sessionId);
			}
		};
	}

}
