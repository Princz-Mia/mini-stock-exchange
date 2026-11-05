package com.mia_princz.mse.execution.identity.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;

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
