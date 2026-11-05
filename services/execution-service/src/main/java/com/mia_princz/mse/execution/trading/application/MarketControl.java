package com.mia_princz.mse.execution.trading.application;

import java.util.Objects;
import java.util.UUID;

/** An operator's market transition, sequenced on the writer like any trading command. */
public record MarketControl(Action action, UUID actorUserId) {

	/** Each transition is legal from exactly one state: SETUP → OPEN ↔ HALTED. */
	public enum Action {

		OPEN("SETUP", "OPEN"), HALT("OPEN", "HALTED"), RESUME("HALTED", "OPEN");

		private final String from;

		private final String to;

		Action(String from, String to) {
			this.from = from;
			this.to = to;
		}

		public String from() {
			return from;
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
	}

}
