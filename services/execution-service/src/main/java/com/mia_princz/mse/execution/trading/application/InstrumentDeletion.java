package com.mia_princz.mse.execution.trading.application;

import java.util.List;
import java.util.UUID;

/**
 * The outcome of deleting an instrument. Refusals are committed and stored like any result, so a retry with the same
 * key answers the same way. The symbol is null when the market has no such instrument; the usage is set only when the
 * instrument is in use.
 */
public record InstrumentDeletion(Status status, UUID instrumentId, String symbol, Usage usage, long commandSequence) {

	/** At most this many holders are named; the count says how many there are. */
	public static final int HOLDERS_NAMED = 20;

	public enum Status {

		DELETED, NOT_IN_SETUP, UNKNOWN_INSTRUMENT, IN_USE

	}

	/**
	 * What keeps an instrument from being deleted: the accounts given its shares, by name (a trader's username, or a bot's), the first
	 * {@link #HOLDERS_NAMED} of them in name order, and the orders sent for it, refused ones included. Allocations are
	 * never removed and accounts never deleted, so a replayed list can only be short of later holders, never wrong.
	 */
	public record Usage(List<String> holders, long holderCount, long orderCount) {

		public Usage {
			holders = List.copyOf(holders);
		}

	}

}
