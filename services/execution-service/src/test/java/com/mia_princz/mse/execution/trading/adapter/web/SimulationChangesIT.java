package com.mia_princz.mse.execution.trading.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;

import com.mia_princz.mse.execution.TestcontainersConfiguration;
import com.mia_princz.mse.execution.trading.application.AddInstrument;
import com.mia_princz.mse.execution.trading.application.AddTrader;
import com.mia_princz.mse.execution.trading.application.CommandKey;
import com.mia_princz.mse.execution.trading.application.MarketControl;
import com.mia_princz.mse.execution.trading.application.PlaceOrder;
import com.mia_princz.mse.execution.trading.application.PlacementResult;
import com.mia_princz.mse.execution.trading.application.ProvisionAccount;
import com.mia_princz.mse.execution.trading.application.SimulationStart;
import com.mia_princz.mse.execution.trading.application.StartSimulation;
import com.mia_princz.mse.execution.trading.application.TradingEngine;
import com.mia_princz.mse.execution.trading.domain.OrderType;
import com.mia_princz.mse.execution.trading.domain.Price;
import com.mia_princz.mse.execution.trading.domain.Quantity;
import com.mia_princz.mse.execution.trading.domain.Side;
import com.mia_princz.mse.execution.trading.domain.SimulationName;

/**
 * The application as it is wired: its own engine, its own rate limits and its own account streams. What those two
 * keep per account, they keep through a close, since the closed market's people still read it; they drop all of it
 * when the next simulation starts, because the engine tells them. The story over HTTP is in
 * {@code SimulationStartIT}; what each of them does when told is in {@link SimulationChangesTest}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "mse.test.database=simulation-changes")
class SimulationChangesIT {

	@Autowired
	TradingEngine engine;

	@Autowired
	AccountRateLimit rateLimit;

	@Autowired
	AccountStream stream;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void theRateLimitsAndTheStreamsForgetTheAccountsOfTheSimulationBefore() throws Exception {
		UUID operator = UUID.randomUUID();
		jdbc.update("INSERT INTO app_user (id, username, password_hash) VALUES (?, 'changes.operator', 'unused')", operator);
		jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, 'OPERATOR')", operator);
		started("Before", operator);
		UUID instrument = UUID.randomUUID();
		engine.addInstrument(key(operator), new AddInstrument(instrument, "NOVA", null, Price.parse("100.00"), operator))
			.get(5, TimeUnit.SECONDS);
		List<ProvisionAccount.Holding> holdings = List.of(new ProvisionAccount.Holding(instrument, 10));
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		for (UUID account : List.of(first, second)) {
			engine.addTrader(key(operator), new AddTrader(UUID.randomUUID(), account,
					"changes-" + account.toString().substring(0, 8), "unused", 100_000, holdings, operator))
				.get(5, TimeUnit.SECONDS);
		}
		engine.control(key(operator), new MarketControl(MarketControl.Action.OPEN, operator)).get(5, TimeUnit.SECONDS);
		TestingAuthenticationToken person = new TestingAuthenticationToken("someone", "n/a");
		rateLimit.check(person, first);
		rateLimit.check(person, second);
		stream.subscribe(first, "a-session");
		PlacementResult resting = engine
			.place(new CommandKey(second, UUID.randomUUID(), "POST /api/v1/orders", new byte[32]), new PlaceOrder(
					UUID.randomUUID(), second, instrument, Side.SELL, OrderType.LIMIT, Price.parse("101.00"), new Quantity(1)))
			.get(5, TimeUnit.SECONDS);
		assertThat(resting.state()).isEqualTo(PlacementResult.OrderState.OPEN);
		assertThat(rateLimit.accountsKept()).isEqualTo(2);
		assertThat(stream.accountsKept()).as("a stream for one account, a version for the other").isEqualTo(2);
		assertThat(stream.openStreams()).isEqualTo(1);

		engine.close(key(operator), new MarketControl(MarketControl.Action.CLOSE, operator)).get(5, TimeUnit.SECONDS);
		assertThat(rateLimit.accountsKept()).as("a close forgets nothing: its market is still read").isEqualTo(2);
		assertThat(stream.openStreams()).isEqualTo(1);

		started("After", operator);

		assertThat(rateLimit.accountsKept()).isZero();
		assertThat(stream.accountsKept()).isZero();
		assertThat(stream.openStreams()).isZero();
	}

	private void started(String name, UUID operator) throws Exception {
		SimulationStart started = engine
			.startSimulation(key(operator), new StartSimulation(UUID.randomUUID(), new SimulationName(name), operator))
			.get(5, TimeUnit.SECONDS);
		assertThat(started.status()).as(name).isEqualTo(SimulationStart.Status.STARTED);
	}

	private static CommandKey key(UUID operator) {
		return new CommandKey(operator, UUID.randomUUID(), "test", new byte[32]);
	}

}
