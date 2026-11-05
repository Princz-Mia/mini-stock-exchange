package com.mia_princz.mse.execution.operations;

import java.util.List;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

/** Deny by default: routes open only as their slices arrive. Actuator stays off the reverse proxy. */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, CsrfTokenRepository csrfTokens,
			SecurityContextRepository contexts) throws Exception {
		return http
			.authorizeHttpRequests((requests) -> requests
				.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus")
				.permitAll()
				.requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf")
				.permitAll()
				.requestMatchers(HttpMethod.POST, "/api/v1/auth/login")
				.permitAll()
				.requestMatchers(HttpMethod.GET, "/api/v1/me")
				.authenticated()
				.requestMatchers(HttpMethod.GET, "/api/v1/market")
				.permitAll()
				.requestMatchers(HttpMethod.POST, "/api/v1/admin/market/open", "/api/v1/admin/market/halt",
						"/api/v1/admin/market/resume")
				.hasRole("OPERATOR")
				.requestMatchers(HttpMethod.POST, "/api/v1/orders", "/api/v1/orders/*/cancel")
				.hasRole("TRADER")
				.requestMatchers(HttpMethod.GET, "/api/v1/orders", "/api/v1/orders/*", "/api/v1/commands/*",
						"/api/v1/portfolio", "/api/v1/portfolio/trades")
				.hasRole("TRADER")
				.anyRequest()
				.denyAll())
			.csrf((csrf) -> csrf.csrfTokenRepository(csrfTokens))
			.securityContext((context) -> context.securityContextRepository(contexts))
			.exceptionHandling((errors) -> errors
				.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
				// setStatus, not sendError: an error dispatch would hit denyAll and turn a 403 into a 401.
				.accessDeniedHandler((request, response, denied) -> response.setStatus(HttpServletResponse.SC_FORBIDDEN)))
			.logout((logout) -> logout.logoutUrl("/api/v1/auth/logout")
				.deleteCookies("SESSION")
				.logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
			.build();
	}

	/** Bound to the server-side session, so no CSRF token is readable from a cookie. */
	@Bean
	CsrfTokenRepository csrfTokenRepository() {
		return new HttpSessionCsrfTokenRepository();
	}

	@Bean
	SecurityContextRepository securityContextRepository() {
		return new DelegatingSecurityContextRepository(new RequestAttributeSecurityContextRepository(),
				new HttpSessionSecurityContextRepository());
	}

	@Bean
	SessionAuthenticationStrategy sessionAuthenticationStrategy(CsrfTokenRepository csrfTokens) {
		return new CompositeSessionAuthenticationStrategy(
				List.of(new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(csrfTokens)));
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}

	@Bean
	AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder passwordEncoder) {
		DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
		provider.setPasswordEncoder(passwordEncoder);
		return new ProviderManager(provider);
	}

}
