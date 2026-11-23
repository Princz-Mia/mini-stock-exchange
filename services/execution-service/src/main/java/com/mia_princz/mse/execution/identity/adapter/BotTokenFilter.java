package com.mia_princz.mse.execution.identity.adapter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

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
import com.mia_princz.mse.execution.trading.adapter.CurrentSimulation;

/**
 * Authenticates a request by its bot token, for the stateless bot chain only. A malformed, unknown or revoked token is
 * answered with 401 here, so the request never falls back to a session cookie; so is the token of an account in a
 * closed simulation, revoked or not. The token is never logged.
 */
public final class BotTokenFilter extends OncePerRequestFilter {

	private static final String BEARER = "Bearer ";

	private static final String TOKEN = """
			SELECT t.id, t.account_id FROM api_token t JOIN account a ON a.id = t.account_id
			WHERE t.token_hash = ? AND t.scope = 'TRADE' AND t.revoked_at IS NULL AND a.simulation_id =\s""";

	private final JdbcClient jdbc;

	private final Supplier<UUID> writerSimulation;

	private final SecurityContextHolderStrategy contexts = SecurityContextHolder.getContextHolderStrategy();

	/** {@code writerSimulation} is the active simulation's ID as the writer holds it in memory, or null. */
	public BotTokenFilter(JdbcClient jdbc, Supplier<UUID> writerSimulation) {
		this.jdbc = jdbc;
		this.writerSimulation = writerSimulation;
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
		// The writer rewrites the simulation's row with every run, and while a long snapshot (a backup's) is open the
		// old versions stay and every read of the row walks them all. So a request never reads it: the writer's own ID
		// is compared. Only while the writer has none (no market yet, or startup still rebuilding it) is the row read.
		UUID simulation = writerSimulation.get();
		byte[] hash = ApiTokens.hash(token);
		return (simulation != null ? jdbc.sql(TOKEN + "?").params(hash, simulation)
				: jdbc.sql(TOKEN + CurrentSimulation.ID).param(hash))
			.query((row, index) -> new BotPrincipal(row.getObject("id", UUID.class),
					row.getObject("account_id", UUID.class)))
			.optional();
	}

}
