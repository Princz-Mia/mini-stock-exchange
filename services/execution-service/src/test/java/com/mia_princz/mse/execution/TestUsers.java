package com.mia_princz.mse.execution;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcOperations;

/** Inserts a login with its roles, as configuration or adding a trader login would. */
final class TestUsers {

	private TestUsers() {
	}

	static void insert(JdbcOperations jdbc, UUID id, String username, String passwordHash, String... roles) {
		jdbc.update("INSERT INTO app_user (id, username, password_hash) VALUES (?, ?, ?)", id, username, passwordHash);
		for (String role : roles) {
			jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, ?)", id, role);
		}
	}

}
