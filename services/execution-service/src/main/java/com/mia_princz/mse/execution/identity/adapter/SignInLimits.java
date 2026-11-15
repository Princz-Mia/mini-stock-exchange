package com.mia_princz.mse.execution.identity.adapter;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One limiter for every password check a person makes: signing in, and a trader confirming their current password to
 * change it. Guessing through either counts against the same username and address.
 */
@Configuration(proxyBeanMethods = false)
class SignInLimits {

	@Bean
	FailedLoginLimiter failedLoginLimiter(@Value("${mse.login.failures-per-user}") int failuresPerUser,
			@Value("${mse.login.user-failure-interval}") Duration userInterval,
			@Value("${mse.login.failures-per-address}") int failuresPerAddress,
			@Value("${mse.login.address-failure-interval}") Duration addressInterval) {
		return new FailedLoginLimiter(new FailedLoginLimiter.Limit(failuresPerUser, userInterval.toNanos()),
				new FailedLoginLimiter.Limit(failuresPerAddress, addressInterval.toNanos()), System::nanoTime);
	}

}
