package com.mia_princz.mse.execution.identity.adapter;

import java.util.Collection;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

/**
 * A login as signing in reads it: the password hash and, in the same row, the password's version, which rises each
 * time the password changes. A session remembers the version it signed in with, so one started with an old password
 * can be told apart however it was started.
 */
final class VersionedUser extends User {

	private static final long serialVersionUID = 1L;

	private final int passwordVersion;

	VersionedUser(String username, String passwordHash, Collection<? extends GrantedAuthority> authorities,
			int passwordVersion) {
		super(username, passwordHash, authorities);
		this.passwordVersion = passwordVersion;
	}

	int passwordVersion() {
		return passwordVersion;
	}

	@Override
	public boolean equals(Object other) {
		return super.equals(other);
	}

	@Override
	public int hashCode() {
		return super.hashCode();
	}

}
