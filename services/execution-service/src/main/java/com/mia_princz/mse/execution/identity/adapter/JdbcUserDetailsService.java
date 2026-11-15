package com.mia_princz.mse.execution.identity.adapter;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
		// The version comes from the same row as the hash, so a sign-in checked against an old password carries the
		// old version, however close it ran to a change.
		return jdbc
			.sql("SELECT username, password_hash, role, password_version FROM app_user WHERE username = :username")
			.param("username", username)
			.query((row, index) -> new VersionedUser(row.getString("username"), row.getString("password_hash"),
					List.of(new SimpleGrantedAuthority("ROLE_" + row.getString("role"))),
					row.getInt("password_version")))
			.optional()
			.orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
	}

}
