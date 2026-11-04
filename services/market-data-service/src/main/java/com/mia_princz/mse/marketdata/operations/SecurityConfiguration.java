package com.mia_princz.mse.marketdata.operations;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/** Deny by default: public market routes open as their slices arrive. Actuator stays off the reverse proxy. */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		return http
			.authorizeHttpRequests((requests) -> requests
				.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus")
				.permitAll()
				// Public market data: anyone may read it, nobody may change it here.
				.requestMatchers(HttpMethod.GET, "/api/v1/instruments", "/api/v1/instruments/*/book",
						"/api/v1/instruments/*/trades", "/api/v1/market-events")
				.permitAll()
				.anyRequest()
				.denyAll())
			.build();
	}

	/** Market Data has no login; this stops Boot from generating and logging a default password. */
	@Bean
	UserDetailsService userDetailsService() {
		return new InMemoryUserDetailsManager();
	}

}
