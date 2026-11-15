package com.mia_princz.mse.execution.identity.adapter;

import java.io.IOException;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ends a session whose password has changed since it signed in. Each session keeps the password version it signed in
 * with; when the login's version has moved on, the session is invalidated and the request goes on signed out, so a
 * session started with the old password ends on its next request, even one whose sign-in was still running while the
 * password changed. A session from before versions existed counts as version 0, the version every password starts at.
 */
public final class PasswordVersionFilter extends OncePerRequestFilter {

	/** The session attribute holding the password version a session signed in with. */
	static final String SESSION_ATTRIBUTE = "mse.passwordVersion";

	private final JdbcClient jdbc;

	public PasswordVersionFilter(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		HttpSession session = request.getSession(false);
		if (session != null && authentication instanceof UsernamePasswordAuthenticationToken
				&& authentication.isAuthenticated()) {
			Object remembered = session.getAttribute(SESSION_ATTRIBUTE);
			int signedInWith = remembered instanceof Integer version ? version : 0;
			Optional<Integer> current = jdbc.sql("SELECT password_version FROM app_user WHERE username = ?")
				.param(authentication.getName())
				.query(Integer.class)
				.optional();
			if (current.isEmpty() || current.get() != signedInWith) {
				session.invalidate();
				SecurityContextHolder.clearContext();
			}
		}
		chain.doFilter(request, response);
	}

}
