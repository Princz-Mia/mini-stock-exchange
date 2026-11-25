package com.mia_princz.mse.execution.trading.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.mia_princz.mse.execution.trading.application.MarketControl.Action;

class MarketControlTest {

	/** SETUP → OPEN ↔ HALTED → CLOSED, and nothing else: every action against every state. */
	@Test
	void eachTransitionIsLegalOnlyFromItsStates() {
		List<String> legal = new ArrayList<>();
		for (Action action : Action.values()) {
			for (String state : new String[] { "SETUP", "OPEN", "HALTED", "CLOSED" }) {
				if (action.allowedFrom(state)) {
					legal.add(state + " " + action + " " + action.to());
				}
			}
		}
		assertThat(legal).containsExactlyInAnyOrder("SETUP OPEN OPEN", "OPEN HALT HALTED", "HALTED RESUME OPEN",
				"OPEN CLOSE CLOSED", "HALTED CLOSE CLOSED");
	}

	@Test
	void theAuditLogNamesTheAction() {
		assertThat(Action.CLOSE.auditName()).isEqualTo("MARKET_CLOSE");
		assertThat(Action.RESUME.auditName()).isEqualTo("MARKET_RESUME");
	}

}
