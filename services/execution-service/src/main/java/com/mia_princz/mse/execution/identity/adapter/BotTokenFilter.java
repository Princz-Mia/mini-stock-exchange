package com.mia_princz.mse.execution.identity.adapter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import com.mia_princz.mse.execution.identity.ApiTokens;
import com.mia_princz.mse.execution.identity.BotPrincipal;

/**
 * Authenticates a request by its bot token, for the stateless bot chain only. A malformed, unknown or revoked token is
 * answered with 401 here, so the request never falls back to a session cookie. The token is never logged.
 */
public final class BotTokenFilter extends OncePerRequestFilter {

	private static final String BEARER = "Bearer ";

	private final JdbcClient jdbc;

	private final SecurityContextHolderStrategy contexts = SecurityContextHolder.getContextHolderStrategy();

	public BotTokenFilter(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Any request that offers a bearer credential belongs to the bot chain, whether or not the token is valid. */
	public static boolean offersBearer(HttpServletRequest request) {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		return header != null && header.regionMatches(true, 0, BEARER, 0, BEARER.length());
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Optional<BotPrincipal> bot = offersBearer(request)
				? find(request.getHeader(HttpHeaders.AUTHORIZATION).substring(BEARER.length()).strip())
				: Optional.empty();
		if (bot.isEmpty()) {
			response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			return;
		}
		SecurityContext context = contexts.createEmptyContext();
		context.setAuthentication(new PreAuthenticatedAuthenticationToken(bot.get(), null,
				List.of(new SimpleGrantedAuthority("ROLE_BOT"))));
		contexts.setContext(context);
		chain.doFilter(request, response);
	}

	private Optional<BotPrincipal> find(String token) {
		if (!ApiTokens.wellFormed(token)) {
			return Optional.empty();
		}
		return jdbc.sql("""
				SELECT id, account_id FROM api_token
				WHERE token_hash = ? AND scope = 'TRADE' AND revoked_at IS NULL""")
			.param(ApiTokens.hash(token))
			.query((row, index) -> new BotPrincipal(row.getObject("id", UUID.class),
					row.getObject("account_id", UUID.class)))
			.optional();
	}

}
