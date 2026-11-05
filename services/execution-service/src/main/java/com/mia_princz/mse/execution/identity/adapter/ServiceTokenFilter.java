package com.mia_princz.mse.execution.identity.adapter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

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
 * Authenticates another service by one shared bearer token from configuration, for the internal chain only. Only the
 * token's SHA-256 is kept, and the comparison takes the same time whatever the input. With no token configured,
 * every request is refused. A trader's or a bot's credentials never pass here.
 */
public final class ServiceTokenFilter extends OncePerRequestFilter {

	private static final String BEARER = "Bearer ";

	private final byte[] expected;

	private final String role;

	private final SecurityContextHolderStrategy contexts = SecurityContextHolder.getContextHolderStrategy();

	/** @param token the configured token, or blank to refuse everything */
	public ServiceTokenFilter(String token, String role) {
		this.expected = token == null || token.isBlank() ? null : sha256(token.strip());
		this.role = role;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		boolean offered = header != null && header.regionMatches(true, 0, BEARER, 0, BEARER.length());
		if (expected == null || !offered
				|| !MessageDigest.isEqual(expected, sha256(header.substring(BEARER.length()).strip()))) {
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
