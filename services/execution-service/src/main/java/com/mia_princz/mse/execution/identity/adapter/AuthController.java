package com.mia_princz.mse.execution.identity.adapter;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class AuthController {

	private final AuthenticationManager authenticationManager;

	private final SessionAuthenticationStrategy sessionStrategy;

	private final SecurityContextRepository contextRepository;

	AuthController(AuthenticationManager authenticationManager, SessionAuthenticationStrategy sessionStrategy,
			SecurityContextRepository contextRepository) {
		this.authenticationManager = authenticationManager;
		this.sessionStrategy = sessionStrategy;
		this.contextRepository = contextRepository;
	}

	@GetMapping("/auth/csrf")
	CsrfResponse csrf(CsrfToken token) {
		return new CsrfResponse(token.getHeaderName(), token.getToken());
	}

	/** The session ID and CSRF token both rotate here, so the client fetches a new token after logging in. */
	@PostMapping("/auth/login")
	IdentityResponse login(@Valid @RequestBody LoginRequest login, HttpServletRequest request,
			HttpServletResponse response) {
		Authentication authentication = authenticationManager
			.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(login.username(), login.password()));
		sessionStrategy.onAuthentication(authentication, request, response);
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(authentication);
		SecurityContextHolder.setContext(context);
		contextRepository.saveContext(context, request, response);
		return IdentityResponse.of(authentication);
	}

	@GetMapping("/me")
	IdentityResponse me(Authentication authentication) {
		return IdentityResponse.of(authentication);
	}

	/** One answer for a wrong password and an unknown user, so usernames cannot be discovered. */
	@ExceptionHandler(AuthenticationException.class)
	ProblemDetail authenticationFailed() {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid username or password.");
		problem.setProperty("code", "INVALID_CREDENTIALS");
		return problem;
	}

	record CsrfResponse(String headerName, String token) {
	}

	record LoginRequest(@NotBlank @Size(max = 100) String username, @NotBlank @Size(max = 200) String password) {

		@Override
		public String toString() {
			return "LoginRequest[username=" + username + "]";
		}

	}

	record IdentityResponse(String username, List<String> roles) {

		private static final String ROLE_PREFIX = "ROLE_";

		/** Authorities also hold non-roles: Spring Security 7 adds a marker such as FACTOR_PASSWORD on login. */
		static IdentityResponse of(Authentication authentication) {
			List<String> roles = authentication.getAuthorities()
				.stream()
				.map(GrantedAuthority::getAuthority)
				.filter((authority) -> authority.startsWith(ROLE_PREFIX))
				.map((authority) -> authority.substring(ROLE_PREFIX.length()))
				.sorted()
				.toList();
			return new IdentityResponse(authentication.getName(), roles);
		}

	}

}
