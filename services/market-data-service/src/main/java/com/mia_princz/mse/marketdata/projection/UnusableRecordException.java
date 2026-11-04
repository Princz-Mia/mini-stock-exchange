package com.mia_princz.mse.marketdata.projection;

/**
 * A record that decodes but contradicts what is already projected, such as a different trade under an existing
 * trade sequence. Like an unreadable record, it pauses the feed at its offset for a person to look at.
 */
public final class UnusableRecordException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public UnusableRecordException(String message) {
		super(message);
	}

}
