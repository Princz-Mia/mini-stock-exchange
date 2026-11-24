package com.mia_princz.mse.execution.trading.application;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * An operator's market transition, sequenced on the writer like any trading command. An opening may carry a duration,
 * after which the exchange closes the market itself; null for a market that stays open until an operator closes it.
 */
public record MarketControl(Action action, UUID actorUserId, Duration duration) {

	/**
	 * SETUP → OPEN ↔ HALTED → CLOSED. Opening, halting and resuming are each legal from one state; closing is legal
	 * from both states a market that has opened can be in. CLOSED is final, and a market still in SETUP is not
	 * closed: nothing has happened in it yet.
	 */
	public enum Action {

		OPEN("OPEN", "SETUP"), HALT("HALTED", "OPEN"), RESUME("OPEN", "HALTED"), CLOSE("CLOSED", "OPEN", "HALTED");

		private final String to;

		private final Set<String> from;

		Action(String to, String... from) {
			this.to = to;
			this.from = Set.of(from);
		}

		/** Whether the transition is legal from {@code state}. */
		public boolean allowedFrom(String state) {
			return from.contains(state);
		}

		public String to() {
			return to;
		}

		/** The name the audit log records. */
		public String auditName() {
			return "MARKET_" + name();
		}

	}

	public MarketControl {
		Objects.requireNonNull(action, "action");
		Objects.requireNonNull(actorUserId, "actorUserId");
		if (duration != null && (action != Action.OPEN || duration.isNegative() || duration.isZero())) {
			throw new IllegalArgumentException("Only an opening has a duration, and a positive one: " + action + " "
					+ duration);
		}
	}

	/** A transition with no duration: every one but an opening that sets a close time. */
	public MarketControl(Action action, UUID actorUserId) {
		this(action, actorUserId, null);
	}

}
