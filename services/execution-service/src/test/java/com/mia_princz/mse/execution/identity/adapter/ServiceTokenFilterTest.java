package com.mia_princz.mse.execution.identity.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class ServiceTokenFilterTest {

	@Test
	void withoutAConfiguredTokenEverythingIsRefused() throws Exception {
		for (String configured : new String[] { null, "", "  " }) {
			for (String offered : new String[] { null, "Bearer ", "Bearer  ", "Bearer anything" }) {
				assertThat(passes(new ServiceTokenFilter(configured, "EXPORT"), offered))
					.as("configured %s, offered %s", configured, offered)
					.isFalse();
			}
		}
	}

	@Test
	void onlyTheConfiguredTokenPasses() throws Exception {
		ServiceTokenFilter filter = new ServiceTokenFilter("secret-token", "EXPORT");
		assertThat(passes(filter, "Bearer secret-token")).isTrue();
		assertThat(passes(filter, "bearer secret-token")).as("the scheme is case-insensitive").isTrue();
		assertThat(passes(filter, "Bearer secret-token2")).isFalse();
		assertThat(passes(filter, "Basic secret-token")).isFalse();
		assertThat(passes(filter, null)).isFalse();
	}

	@Test
	void eachTokenOpensOnlyItsOwnRole() throws Exception {
		ServiceTokenFilter filter = new ServiceTokenFilter(Map.of("EXPORT", "export-token", "SIMULATOR", "sim-token"));
		assertThat(role(filter, "Bearer export-token")).isEqualTo("[ROLE_EXPORT]");
		assertThat(role(filter, "Bearer sim-token")).isEqualTo("[ROLE_SIMULATOR]");
		assertThat(role(filter, "Bearer sim-token2")).isNull();
		ServiceTokenFilter onlyExport = new ServiceTokenFilter(Map.of("EXPORT", "export-token", "SIMULATOR", " "));
		assertThat(role(onlyExport, "Bearer export-token")).isEqualTo("[ROLE_EXPORT]");
		assertThat(role(onlyExport, "Bearer ")).as("a blank token opens nothing").isNull();
		assertThat(role(onlyExport, "Bearer  ")).isNull();
	}

	@Test
	void twoRolesCannotShareAToken() {
		assertThatThrownBy(() -> new ServiceTokenFilter(Map.of("EXPORT", "same", "SIMULATOR", " same ")))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private static String role(ServiceTokenFilter filter, String authorization) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/simulator/assignment");
		request.addHeader("Authorization", authorization);
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicReference<String> role = new AtomicReference<>();
		filter.doFilter(request, response, (req, res) -> role
			.set(SecurityContextHolder.getContext().getAuthentication().getAuthorities().toString()));
		SecurityContextHolder.clearContext();
		if (role.get() == null) {
			assertThat(response.getStatus()).isEqualTo(401);
		}
		return role.get();
	}

	private static boolean passes(ServiceTokenFilter filter, String authorization) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/exports/trades");
		if (authorization != null) {
			request.addHeader("Authorization", authorization);
		}
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicBoolean reached = new AtomicBoolean();
		filter.doFilter(request, response, (req, res) -> reached.set(
				SecurityContextHolder.getContext().getAuthentication().getAuthorities().toString().contains("ROLE_EXPORT")));
		SecurityContextHolder.clearContext();
		if (!reached.get()) {
			assertThat(response.getStatus()).isEqualTo(401);
		}
		return reached.get();
	}

}
