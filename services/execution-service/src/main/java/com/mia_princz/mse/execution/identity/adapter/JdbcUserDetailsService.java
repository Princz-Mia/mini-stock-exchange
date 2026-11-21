package com.mia_princz.mse.execution.identity.adapter;

import java.util.Arrays;

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
		// old version, however close it ran to a change. One authority per role the user holds; a user with none is
		// unknown, so it can't sign in.
		return jdbc.sql("""
				SELECT u.username, u.password_hash, u.password_version, array_agg(r.role ORDER BY r.role) AS roles
				FROM app_user u JOIN user_role r ON r.user_id = u.id
				WHERE u.username = :username
				GROUP BY u.id""")
			.param("username", username)
			.query((row, index) -> new VersionedUser(row.getString("username"), row.getString("password_hash"),
					Arrays.stream((String[]) row.getArray("roles").getArray())
						.map((role) -> new SimpleGrantedAuthority("ROLE_" + role))
						.toList(),
					row.getInt("password_version")))
			.optional()
			.orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
	}

}
