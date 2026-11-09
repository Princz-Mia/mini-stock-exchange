package com.mia_princz.mse.execution.operations;

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
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
import com.mia_princz.mse.execution.identity.adapter.ServiceTokenFilter;

/**
 * Three chains, each deny by default: other services by a service token, bots by bearer token, people by session.
 * Routes open only as their slices arrive. Actuator stays off the reverse proxy.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

	private static final String[] TRADING_COMMANDS = { "/api/v1/orders", "/api/v1/orders/*/cancel" };

	private static final String[] TRADING_READS = { "/api/v1/orders", "/api/v1/orders/*", "/api/v1/commands/*",
			"/api/v1/portfolio", "/api/v1/portfolio/trades" };

	/**
	 * Other services, on internal routes the proxy never forwards: stateless, by a token per service from
	 * configuration. Market Data reads the trade export; the simulator reads its assignment and reports its heartbeat,
	 * and can't trade. It runs first, so no session, bot token or trader credential reaches these routes.
	 */
	@Bean
	@Order(0)
	SecurityFilterChain serviceChain(HttpSecurity http, @Value("${mse.exports.token:}") String exportToken,
			@Value("${mse.simulator.token:}") String simulatorToken) throws Exception {
		return http.securityMatcher("/internal/**")
			.authorizeHttpRequests((requests) -> requests.requestMatchers(HttpMethod.GET, "/internal/exports/trades")
				.hasRole("EXPORT")
				.requestMatchers(HttpMethod.GET, "/internal/simulator/assignment")
				.hasRole("SIMULATOR")
				.requestMatchers(HttpMethod.POST, "/internal/simulator/heartbeat")
				.hasRole("SIMULATOR")
				.anyRequest()
				.denyAll())
			.addFilterAt(new ServiceTokenFilter(Map.of("EXPORT", exportToken, "SIMULATOR", simulatorToken)),
					BasicAuthenticationFilter.class)
			.csrf(AbstractHttpConfigurer::disable)
			.sessionManagement((sessions) -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.securityContext((context) -> context.securityContextRepository(new RequestAttributeSecurityContextRepository()))
			.requestCache(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.exceptionHandling((errors) -> errors
				.accessDeniedHandler((request, response, denied) -> response.setStatus(HttpServletResponse.SC_FORBIDDEN)))
			.build();
	}

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
				.requestMatchers(HttpMethod.GET, "/api/v1/admin/accounts", "/api/v1/admin/bots", "/api/v1/admin/bot-runs")
				.hasRole("OPERATOR")
				.requestMatchers(HttpMethod.POST, "/api/v1/admin/market/open", "/api/v1/admin/market/halt",
						"/api/v1/admin/market/resume", "/api/v1/admin/instruments",
						"/api/v1/admin/accounts", "/api/v1/admin/bots", "/api/v1/admin/bot-runs",
						"/api/v1/admin/bot-runs/*/stop", "/api/v1/admin/bots/*/revoke-token")
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
		// bcrypt only. The factory default also matches {noop} plaintext and older encodings, so a password stored
		// any other way would work; here it never matches.
		return new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder()));
	}

	@Bean
	AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder passwordEncoder) {
		DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
		provider.setPasswordEncoder(passwordEncoder);
		return new ProviderManager(provider);
	}

}
