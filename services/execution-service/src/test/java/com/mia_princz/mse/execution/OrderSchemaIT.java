package com.mia_princz.mse.execution;

import static com.mia_princz.mse.execution.SchemaFixtures.CHECK_VIOLATION;
import static com.mia_princz.mse.execution.SchemaFixtures.FOREIGN_KEY_VIOLATION;
import static com.mia_princz.mse.execution.SchemaFixtures.INSUFFICIENT_PRIVILEGE;
import static com.mia_princz.mse.execution.SchemaFixtures.UNIQUE_VIOLATION;
import static com.mia_princz.mse.execution.SchemaFixtures.assertFails;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class OrderSchemaIT {

	private static final String INSERT_ORDER = """
			INSERT INTO orders (id, simulation_id, account_id, instrument_id, side, type, time_in_force, bound_cents,
			    requested_quantity, filled_quantity, working_quantity, canceled_quantity, state, reason, accepted_sequence)
			VALUES (?, ?, ?, ?, 'BUY', ?, ?, 10100, ?, ?, ?, ?, ?, ?, ?)""";

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	PlatformTransactionManager transactionManager;

	SchemaFixtures db;

	private long sequence;

	@BeforeEach
	void fixtures() {
		db = new SchemaFixtures(jdbcTemplate, transactionManager);
	}

	@Test
	void everyLegalOrderStateIsAccepted() {
		db.inRolledBackTransaction((f) -> {
			Market m = Market.create(f);
			limit(f, m, 10, 0, 10, 0, "OPEN", null);
			limit(f, m, 10, 6, 4, 0, "PARTIALLY_FILLED", null);
			limit(f, m, 10, 10, 0, 0, "FILLED", null);
			limit(f, m, 10, 6, 0, 4, "CANCELED", "CANCELED_BY_USER");
			order(f, m, "MARKET_PROTECTED", "IOC", 10, 6, 0, 4, "CANCELED", "IOC_REMAINDER_CANCELED");
			rejected(f, m, "INSUFFICIENT_CASH");
			assertThat(f.jdbc.queryForObject("SELECT count(*) FROM orders", Long.class)).isEqualTo(6);
		});
	}

	@Test
	void quantitiesMustAddUpForAcceptedOrders() {
		db.inRolledBackTransaction((f) -> assertFails(CHECK_VIOLATION,
				() -> limit(f, Market.create(f), 10, 6, 3, 0, "PARTIALLY_FILLED", null)));
	}

	@Test
	void stateMustMatchQuantities() {
		String[][] illegal = { { "OPEN", "6", "4", "0" }, { "PARTIALLY_FILLED", "0", "10", "0" },
				{ "FILLED", "6", "4", "0" }, { "FILLED", "6", "0", "4" }, { "CANCELED", "10", "0", "0" },
				{ "CANCELED", "6", "4", "0" } };
		for (String[] row : illegal) {
			db.inRolledBackTransaction((f) -> assertFails(CHECK_VIOLATION, () -> limit(f, Market.create(f), 10,
					Long.parseLong(row[1]), Long.parseLong(row[2]), Long.parseLong(row[3]), row[0], "X")));
		}
	}

	@Test
	void typeAndTimeInForceMustMatchAndMarketOrdersNeverRest() {
		db.inRolledBackTransaction((f) -> assertFails(CHECK_VIOLATION,
				() -> order(f, Market.create(f), "LIMIT", "IOC", 10, 0, 10, 0, "OPEN", null)));
		db.inRolledBackTransaction((f) -> assertFails(CHECK_VIOLATION,
				() -> order(f, Market.create(f), "MARKET_PROTECTED", "IOC", 10, 0, 10, 0, "OPEN", null)));
	}

	@Test
	void rejectedOrdersHaveAReasonAndNothingAccepted() {
		db.inRolledBackTransaction((f) -> assertFails(CHECK_VIOLATION, () -> rejected(f, Market.create(f), null)));
		db.inRolledBackTransaction((f) -> {
			Market m = Market.create(f);
			assertFails(CHECK_VIOLATION, () -> f.jdbc.update(INSERT_ORDER, UUID.randomUUID(), m.simulation, m.account,
					m.instrument, "LIMIT", "GTC", 10, 0, 10, 0, "REJECTED", "INSUFFICIENT_CASH", null));
		});
		db.inRolledBackTransaction((f) -> {
			Market m = Market.create(f);
			assertFails(CHECK_VIOLATION, () -> f.jdbc.update(INSERT_ORDER, UUID.randomUUID(), m.simulation, m.account,
					m.instrument, "LIMIT", "GTC", 10, 0, 0, 0, "REJECTED", "INSUFFICIENT_CASH", 99L));
		});
	}

	@Test
	void acceptedSequencesAreUnique() {
		db.inRolledBackTransaction((f) -> {
			Market m = Market.create(f);
			String insert = INSERT_ORDER;
			f.jdbc.update(insert, UUID.randomUUID(), m.simulation, m.account, m.instrument, "LIMIT", "GTC", 10, 0, 10, 0,
					"OPEN", null, 7L);
			assertFails(UNIQUE_VIOLATION, () -> f.jdbc.update(insert, UUID.randomUUID(), m.simulation, m.account,
					m.instrument, "LIMIT", "GTC", 10, 0, 10, 0, "OPEN", null, 7L));
		});
	}

	@Test
	void ordersCannotPointAcrossSimulationsOrDisappear() {
		db.inRolledBackTransaction((f) -> {
			Market m = Market.create(f);
			assertFails(FOREIGN_KEY_VIOLATION, () -> f.jdbc.update(INSERT_ORDER, UUID.randomUUID(), m.simulation,
					UUID.randomUUID(), m.instrument, "LIMIT", "GTC", 10, 0, 10, 0, "OPEN", null, ++sequence));
		});
		db.inRolledBackTransaction((f) -> {
			Market m = Market.create(f);
			UUID order = limit(f, m, 10, 0, 10, 0, "OPEN", null);
			assertFails(INSUFFICIENT_PRIVILEGE, () -> f.jdbc.update("DELETE FROM orders WHERE id = ?", order));
		});
		db.inRolledBackTransaction((f) -> {
			Market m = Market.create(f);
			UUID order = limit(f, m, 10, 0, 10, 0, "OPEN", null);
			assertFails(INSUFFICIENT_PRIVILEGE, () -> f.jdbc.update("UPDATE orders SET bound_cents = 1 WHERE id = ?", order));
		});
	}

	@Test
	void runtimeRoleCanProgressAnOrder() {
		db.inRolledBackTransaction((f) -> {
			Market m = Market.create(f);
			UUID order = limit(f, m, 10, 0, 10, 0, "OPEN", null);
			f.jdbc.update("""
					UPDATE orders SET filled_quantity = 6, working_quantity = 4, state = 'PARTIALLY_FILLED',
					    version = version + 1, updated_at = now() WHERE id = ?""", order);
			assertThat(f.jdbc.queryForObject("SELECT version FROM orders WHERE id = ?", Long.class, order)).isEqualTo(2);
		});
	}

	@Test
	void tradesAreValidUniquePerInstrumentAndAppendOnly() {
		db.inRolledBackTransaction((f) -> {
			Market m = Market.create(f);
			UUID seller = f.bot(m.simulation);
			UUID trade = trade(f, m, seller, 1);
			assertFails(UNIQUE_VIOLATION, () -> trade(f, m, seller, 1));
			assertThat(trade).isNotNull();
		});
		db.inRolledBackTransaction((f) -> {
			Market m = Market.create(f);
			UUID trade = trade(f, m, f.bot(m.simulation), 1);
			assertFails(INSUFFICIENT_PRIVILEGE, () -> f.jdbc.update("UPDATE trades SET price_cents = 1 WHERE id = ?", trade));
		});
		db.inRolledBackTransaction((f) -> {
			Market m = Market.create(f);
			assertFails(CHECK_VIOLATION, () -> trade(f, m, m.account, 1));
		});
	}

	@Test
	void onlyTradeBatchesPointAtATrade() {
		db.inRolledBackTransaction((f) -> {
			Market m = Market.create(f);
			assertFails(CHECK_VIOLATION, () -> f.jdbc.update(
					"INSERT INTO journal_batch (id, simulation_id, cause, command_sequence) VALUES (?, ?, 'TRADE', 1)",
					UUID.randomUUID(), m.simulation));
		});
		db.inRolledBackTransaction((f) -> {
			Market m = Market.create(f);
			UUID trade = trade(f, m, f.bot(m.simulation), 1);
			f.jdbc.update(
					"INSERT INTO journal_batch (id, simulation_id, cause, command_sequence, trade_id) VALUES (?, ?, 'TRADE', 1, ?)",
					UUID.randomUUID(), m.simulation, trade);
		});
	}

	private UUID limit(SchemaFixtures f, Market m, long requested, long filled, long working, long canceled, String state,
			String reason) {
		return order(f, m, "LIMIT", "GTC", requested, filled, working, canceled, state, reason);
	}

	private UUID order(SchemaFixtures f, Market m, String type, String timeInForce, long requested, long filled,
			long working, long canceled, String state, String reason) {
		UUID id = UUID.randomUUID();
		f.jdbc.update(INSERT_ORDER, id, m.simulation, m.account, m.instrument, type, timeInForce, requested, filled,
				working, canceled, state, reason, ++sequence);
		return id;
	}

	private UUID rejected(SchemaFixtures f, Market m, String reason) {
		UUID id = UUID.randomUUID();
		f.jdbc.update(INSERT_ORDER, id, m.simulation, m.account, m.instrument, "LIMIT", "GTC", 10, 0, 0, 0, "REJECTED",
				reason, null);
		return id;
	}

	private UUID trade(SchemaFixtures f, Market m, UUID seller, long tradeSequence) {
		UUID maker = limit(f, m, 10, 10, 0, 0, "FILLED", null);
		UUID taker = limit(f, m, 10, 10, 0, 0, "FILLED", null);
		UUID id = UUID.randomUUID();
		f.jdbc.update("""
				INSERT INTO trades (id, simulation_id, instrument_id, trade_sequence, maker_order_id, taker_order_id,
				    buyer_account_id, seller_account_id, price_cents, quantity, executed_at, command_sequence)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, 10000, 10, ?, 1)""", id, m.simulation, m.instrument, tradeSequence, maker,
				taker, m.account, seller, Timestamp.from(Instant.now()));
		return id;
	}

	private record Market(UUID simulation, UUID account, UUID instrument) {

		static Market create(SchemaFixtures f) {
			UUID simulation = f.simulation();
			return new Market(simulation, f.trader(simulation), f.instrument(simulation, "NOVA"));
		}

	}

}
