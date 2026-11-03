package com.mia_princz.mse.execution;

import static com.mia_princz.mse.execution.SchemaFixtures.CHECK_VIOLATION;
import static com.mia_princz.mse.execution.SchemaFixtures.FOREIGN_KEY_VIOLATION;
import static com.mia_princz.mse.execution.SchemaFixtures.INSUFFICIENT_PRIVILEGE;
import static com.mia_princz.mse.execution.SchemaFixtures.UNIQUE_VIOLATION;
import static com.mia_princz.mse.execution.SchemaFixtures.assertFails;
import static org.assertj.core.api.Assertions.assertThat;

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
class ReferenceDataSchemaIT {

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	PlatformTransactionManager transactionManager;

	SchemaFixtures db;

	@BeforeEach
	void fixtures() {
		db = new SchemaFixtures(jdbcTemplate, transactionManager);
	}

	@Test
	void validReferenceDataIsAccepted() {
		db.inRolledBackTransaction((f) -> {
			UUID simulation = f.simulation();
			f.trader(simulation);
			f.bot(simulation);
			f.issuance(simulation);
			f.instrument(simulation, "NOVA");
			assertThat(f.jdbc.queryForObject("SELECT count(*) FROM account", Long.class)).isEqualTo(3);
		});
	}

	@Test
	void thereIsOnlyOneSimulation() {
		db.inRolledBackTransaction((f) -> {
			f.simulation();
			assertFails(UNIQUE_VIOLATION, f::simulation);
		});
	}

	@Test
	void openedAtMatchesTheState() {
		db.inRolledBackTransaction((f) -> assertFails(CHECK_VIOLATION,
				() -> f.jdbc.update("INSERT INTO simulation (id, state) VALUES (?, 'OPEN')", UUID.randomUUID())));
	}

	@Test
	void onlyTraderAccountsHaveAnOwner() {
		db.inRolledBackTransaction((f) -> {
			UUID simulation = f.simulation();
			assertFails(CHECK_VIOLATION, () -> f.jdbc.update(
					"INSERT INTO account (id, simulation_id, kind, display_name) VALUES (?, ?, 'TRADER', 'No owner')",
					UUID.randomUUID(), simulation));
		});
		db.inRolledBackTransaction((f) -> {
			UUID simulation = f.simulation();
			assertFails(CHECK_VIOLATION, () -> f.jdbc.update(
					"INSERT INTO account (id, simulation_id, kind, owner_user_id, display_name) VALUES (?, ?, 'BOT', ?, 'Owned bot')",
					UUID.randomUUID(), simulation, f.user()));
		});
	}

	@Test
	void thereIsOneIssuanceAccountAndOneAccountPerUser() {
		db.inRolledBackTransaction((f) -> {
			UUID simulation = f.simulation();
			f.issuance(simulation);
			assertFails(UNIQUE_VIOLATION, () -> f.issuance(simulation));
		});
		db.inRolledBackTransaction((f) -> {
			UUID simulation = f.simulation();
			UUID user = f.user();
			String insert = "INSERT INTO account (id, simulation_id, kind, owner_user_id, display_name) VALUES (?, ?, 'TRADER', ?, 'Trader')";
			f.jdbc.update(insert, UUID.randomUUID(), simulation, user);
			assertFails(UNIQUE_VIOLATION, () -> f.jdbc.update(insert, UUID.randomUUID(), simulation, user));
		});
	}

	@Test
	void symbolsAreUniqueUppercaseAndPricesInRange() {
		db.inRolledBackTransaction((f) -> {
			UUID simulation = f.simulation();
			f.instrument(simulation, "NOVA");
			assertFails(UNIQUE_VIOLATION, () -> f.instrument(simulation, "NOVA"));
		});
		db.inRolledBackTransaction((f) -> assertFails(CHECK_VIOLATION, () -> f.instrument(f.simulation(), "nova")));
		db.inRolledBackTransaction((f) -> {
			UUID simulation = f.simulation();
			assertFails(CHECK_VIOLATION, () -> f.jdbc.update(
					"INSERT INTO instrument (id, simulation_id, symbol, reference_price_cents) VALUES (?, ?, 'ORBT', 0)",
					UUID.randomUUID(), simulation));
		});
	}

	@Test
	void accountsNeedAnExistingSimulation() {
		db.inRolledBackTransaction((f) -> assertFails(FOREIGN_KEY_VIOLATION, () -> f.bot(UUID.randomUUID())));
	}

	@Test
	void runtimeRoleCannotRewriteIdentityOrDelete() {
		db.inRolledBackTransaction((f) -> {
			UUID instrument = f.instrument(f.simulation(), "NOVA");
			assertFails(INSUFFICIENT_PRIVILEGE,
					() -> f.jdbc.update("UPDATE instrument SET symbol = 'HELI' WHERE id = ?", instrument));
		});
		db.inRolledBackTransaction((f) -> {
			UUID account = f.bot(f.simulation());
			assertFails(INSUFFICIENT_PRIVILEGE,
					() -> f.jdbc.update("UPDATE account SET kind = 'ISSUANCE' WHERE id = ?", account));
		});
		db.inRolledBackTransaction((f) -> {
			UUID simulation = f.simulation();
			assertFails(INSUFFICIENT_PRIVILEGE,
					() -> f.jdbc.update("DELETE FROM simulation WHERE id = ?", simulation));
		});
	}

	@Test
	void runtimeRoleCanAdvanceTheCountersItOwns() {
		db.inRolledBackTransaction((f) -> {
			UUID simulation = f.simulation();
			UUID instrument = f.instrument(simulation, "NOVA");
			UUID account = f.bot(simulation);
			f.jdbc.update("UPDATE simulation SET last_command_sequence = 1, writer_epoch = 1, state = 'OPEN', opened_at = now() WHERE id = ?",
					simulation);
			f.jdbc.update("UPDATE instrument SET last_trade_sequence = 1 WHERE id = ?", instrument);
			f.jdbc.update("UPDATE account SET version = 1 WHERE id = ?", account);
			assertThat(f.jdbc.queryForObject("SELECT state FROM simulation", String.class)).isEqualTo("OPEN");
		});
	}

}
