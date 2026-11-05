package com.mia_princz.mse.execution.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Bot API tokens: 256 random bits behind a recognisable prefix. A token has far too much entropy to guess, so a plain
 * SHA-256 hash is enough to store it; a slow password hash would only slow down every bot request.
 */
public final class ApiTokens {

	public static final String PREFIX = "mse_bot_";

	private static final int RANDOM_BYTES = 32;

	/** Base64url without padding encodes 32 bytes in 43 characters. */
	private static final int LENGTH = PREFIX.length() + 43;

	private static final SecureRandom RANDOM = new SecureRandom();

	private ApiTokens() {
	}

	/** A token and the hash to store. The token is never stored and must leave the service only once. */
	public record Issued(String token, byte[] hash) {

		@Override
		public String toString() {
			return "Issued[token=<hidden>]";
		}

	}

	public static Issued issue() {
		byte[] random = new byte[RANDOM_BYTES];
		RANDOM.nextBytes(random);
		String token = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
		return new Issued(token, hash(token));
	}

	/** Rejects anything that cannot be a token before it costs a database lookup. */
	public static boolean wellFormed(String token) {
		if (token == null || token.length() != LENGTH || !token.startsWith(PREFIX)) {
			return false;
		}
		for (int i = PREFIX.length(); i < LENGTH; i++) {
			char c = token.charAt(i);
			boolean base64url = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-'
					|| c == '_';
			if (!base64url) {
				return false;
			}
		}
		return true;
	}

	public static byte[] hash(String token) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
