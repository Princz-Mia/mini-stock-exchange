package com.mia_princz.mse.execution.identity.adapter;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the demo logins when their passwords are configured, normally from the generated .env. With no password
 * configured, nothing is created, so a deployment cannot end up with a known default login.
 */
@Component
class DevelopmentUsers implements ApplicationRunner {

	private final JdbcClient jdbc;

	private final PasswordEncoder passwordEncoder;

	private final String operatorPassword;

	private final String traderPassword;

	DevelopmentUsers(JdbcClient jdbc, PasswordEncoder passwordEncoder,
			@Value("${mse.dev-users.operator-password:}") String operatorPassword,
			@Value("${mse.dev-users.trader-password:}") String traderPassword) {
		this.jdbc = jdbc;
		this.passwordEncoder = passwordEncoder;
		this.operatorPassword = operatorPassword;
		this.traderPassword = traderPassword;
	}

	@Override
	public void run(ApplicationArguments args) {
		// The operator also trades, once it has funded its own trader account in setup.
		seed("operator", operatorPassword, "OPERATOR", "TRADER");
		seed("trader", traderPassword, "TRADER");
	}

	/**
	 * Adds the login if it is missing, and each role it lacks: an existing login keeps its password and gains a role
	 * added to the configuration at the next start. Roles are never removed.
	 */
	private void seed(String username, String password, String... roles) {
		if (password.isBlank()) {
			return;
		}
		jdbc.sql("""
				INSERT INTO app_user (id, username, password_hash)
				VALUES (:id, :username, :hash)
				ON CONFLICT (username) DO NOTHING
				""")
			.param("id", UUID.randomUUID())
			.param("username", username)
			.param("hash", passwordEncoder.encode(password))
			.update();
		for (String role : roles) {
			jdbc.sql("""
					INSERT INTO user_role (user_id, role)
					SELECT id, :role FROM app_user WHERE username = :username
					ON CONFLICT DO NOTHING
					""")
				.param("role", role)
				.param("username", username)
				.update();
		}
	}

}
