package com.mia_princz.mse.execution.operations;

import java.util.List;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
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

import com.mia_princz.mse.execution.identity.adapter.BotTokenFilter;

/**
 * Two chains, both deny by default: bots by bearer token, people by session. Routes open only as their slices arrive.
 * Actuator stays off the reverse proxy.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

	private static final String[] TRADING_COMMANDS = { "/api/v1/orders", "/api/v1/orders/*/cancel" };

	private static final String[] TRADING_READS = { "/api/v1/orders", "/api/v1/orders/*", "/api/v1/commands/*",
			"/api/v1/portfolio", "/api/v1/portfolio/trades" };

	/**
	 * Bots: stateless, by bearer token only, and only the trading routes. It takes every request that offers a bearer
	 * token, so an invalid token is refused instead of falling through to the session chain, and a session cookie sent
	 * along is ignored. That is also why it needs no CSRF token: a browser never adds a bearer token on its own.
	 */
	@Bean
	@Order(1)
	SecurityFilterChain botChain(HttpSecurity http, JdbcClient jdbc) throws Exception {
		return http.securityMatcher(BotTokenFilter::offersBearer)
			.authorizeHttpRequests((requests) -> requests.requestMatchers(HttpMethod.GET, "/api/v1/market")
				.permitAll()
				.requestMatchers(HttpMethod.POST, TRADING_COMMANDS)
				.hasRole("BOT")
				.requestMatchers(HttpMethod.GET, TRADING_READS)
				.hasRole("BOT")
				.anyRequest()
				.denyAll())
			.addFilterAt(new BotTokenFilter(jdbc), BasicAuthenticationFilter.class)
			.csrf(AbstractHttpConfigurer::disable)
			.sessionManagement((sessions) -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.securityContext((context) -> context.securityContextRepository(new RequestAttributeSecurityContextRepository()))
			.requestCache(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.exceptionHandling((errors) -> errors
				.accessDeniedHandler((request, response, denied) -> response.setStatus(HttpServletResponse.SC_FORBIDDEN)))
			.build();
	}

	@Bean
	@Order(2)
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
				.requestMatchers(HttpMethod.GET, "/api/v1/admin/accounts")
				.hasRole("OPERATOR")
				.requestMatchers(HttpMethod.POST, "/api/v1/admin/market/open", "/api/v1/admin/market/halt",
						"/api/v1/admin/market/resume", "/api/v1/admin/instruments",
						"/api/v1/admin/accounts", "/api/v1/admin/bots")
				.hasRole("OPERATOR")
				.requestMatchers(HttpMethod.POST, TRADING_COMMANDS)
				.hasRole("TRADER")
				.requestMatchers(HttpMethod.GET, TRADING_READS)
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
