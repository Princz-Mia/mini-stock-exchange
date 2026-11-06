package com.mia_princz.mse.execution.identity.adapter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates other services by bearer tokens from configuration, one token per role, for the internal chain only.
 * Only each token's SHA-256 is kept, and every configured token is compared, each in the same time whatever the input.
 * A role without a token is refused; two roles can't share one. A trader's or a bot's credentials never pass here.
 */
public final class ServiceTokenFilter extends OncePerRequestFilter {

	private static final String BEARER = "Bearer ";

	private record Expected(byte[] hash, String role) {
	}

	private final List<Expected> expected = new ArrayList<>();

	private final SecurityContextHolderStrategy contexts = SecurityContextHolder.getContextHolderStrategy();

	/** @param token the configured token, or blank to refuse everything */
	public ServiceTokenFilter(String token, String role) {
		this(token == null ? Map.of() : Map.of(role, token));
	}

	/** @param tokensByRole each role's configured token; a blank token leaves that role refused */
	public ServiceTokenFilter(Map<String, String> tokensByRole) {
		Set<String> seen = new HashSet<>();
		tokensByRole.forEach((role, token) -> {
			if (token == null || token.isBlank()) {
				return;
			}
			if (!seen.add(token.strip())) {
				throw new IllegalArgumentException("Two service roles share one token");
			}
			expected.add(new Expected(sha256(token.strip()), role));
		});
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		boolean offered = header != null && header.regionMatches(true, 0, BEARER, 0, BEARER.length());
		String role = null;
		if (offered) {
			byte[] hash = sha256(header.substring(BEARER.length()).strip());
			for (Expected candidate : expected) {
				if (MessageDigest.isEqual(candidate.hash(), hash)) {
					role = candidate.role();
				}
			}
		}
		if (role == null) {
			response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			return;
		}
		SecurityContext context = contexts.createEmptyContext();
		context.setAuthentication(
				new PreAuthenticatedAuthenticationToken(role, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
		contexts.setContext(context);
		chain.doFilter(request, response);
	}

	private static byte[] sha256(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
