package com.mia_princz.mse.execution.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ApiTokensTest {

	@Test
	void issuedTokensAreWellFormedDistinctAndHashedToTheirStoredForm() {
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < 1_000; i++) {
			ApiTokens.Issued issued = ApiTokens.issue();
			assertThat(ApiTokens.wellFormed(issued.token())).as(issued.token()).isTrue();
			assertThat(seen.add(issued.token())).isTrue();
			assertThat(issued.hash()).hasSize(32).isEqualTo(ApiTokens.hash(issued.token()));
		}
	}

	@Test
	void theTokenNeverAppearsInToString() {
		ApiTokens.Issued issued = ApiTokens.issue();
		assertThat(issued.toString()).doesNotContain(issued.token().substring(ApiTokens.PREFIX.length()));
	}

	@Test
	void anythingElseIsNotWellFormed() {
		String token = ApiTokens.issue().token();
		String body = token.substring(ApiTokens.PREFIX.length());
		assertThat(ApiTokens.wellFormed(null)).isFalse();
		assertThat(ApiTokens.wellFormed("")).isFalse();
		assertThat(ApiTokens.wellFormed(token.substring(1))).isFalse();
		assertThat(ApiTokens.wellFormed(token + "A")).isFalse();
		assertThat(ApiTokens.wellFormed("mse_box_" + body)).isFalse();
		assertThat(ApiTokens.wellFormed(ApiTokens.PREFIX + body.substring(1) + "=")).isFalse();
		assertThat(ApiTokens.wellFormed(ApiTokens.PREFIX + body.substring(1) + "+")).isFalse();
		assertThat(ApiTokens.wellFormed(ApiTokens.PREFIX + body.substring(1) + "é")).isFalse();
	}

}
