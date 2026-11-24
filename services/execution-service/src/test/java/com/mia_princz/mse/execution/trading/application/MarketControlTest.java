package com.mia_princz.mse.execution.trading.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

	/** Only an opening sets a close time, and only one still to come. */
	@Test
	void onlyAnOpeningHasADuration() {
		UUID operator = UUID.randomUUID();
		assertThat(new MarketControl(Action.OPEN, operator, Duration.ofMinutes(5)).duration())
			.isEqualTo(Duration.ofMinutes(5));
		assertThat(new MarketControl(Action.OPEN, operator).duration()).isNull();
		for (Action action : new Action[] { Action.HALT, Action.RESUME, Action.CLOSE }) {
			assertThatThrownBy(() -> new MarketControl(action, operator, Duration.ofMinutes(5)))
				.isInstanceOf(IllegalArgumentException.class);
		}
		assertThatThrownBy(() -> new MarketControl(Action.OPEN, operator, Duration.ZERO))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void theAuditLogNamesTheAction() {
		assertThat(Action.CLOSE.auditName()).isEqualTo("MARKET_CLOSE");
		assertThat(Action.RESUME.auditName()).isEqualTo("MARKET_RESUME");
	}

}
