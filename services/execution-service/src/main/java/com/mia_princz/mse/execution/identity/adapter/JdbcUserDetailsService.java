package com.mia_princz.mse.execution.identity.adapter;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
class JdbcUserDetailsService implements UserDetailsService {

	private final JdbcClient jdbc;

	JdbcUserDetailsService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public UserDetails loadUserByUsername(String username) {
		return jdbc.sql("SELECT username, password_hash, role FROM app_user WHERE username = :username")
			.param("username", username)
			.query((row, index) -> User.withUsername(row.getString("username"))
				.password(row.getString("password_hash"))
				.roles(row.getString("role"))
				.build())
			.optional()
			.orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
	}

}
