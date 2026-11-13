package com.mia_princz.mse.execution;

import static com.mia_princz.mse.execution.SchemaFixtures.CHECK_VIOLATION;
import static com.mia_princz.mse.execution.SchemaFixtures.FOREIGN_KEY_VIOLATION;
import static com.mia_princz.mse.execution.SchemaFixtures.INSUFFICIENT_PRIVILEGE;
import static com.mia_princz.mse.execution.SchemaFixtures.NOT_IN_PREREQUISITE_STATE;
import static com.mia_princz.mse.execution.SchemaFixtures.UNIQUE_VIOLATION;
import static com.mia_princz.mse.execution.SchemaFixtures.assertFails;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
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

	@Test
	void referencePricesChangeOnlyInSetup() {
		db.inRolledBackTransaction((f) -> {
			UUID simulation = f.simulation();
			UUID instrument = f.instrument(simulation, "NOVA");
			assertThat(f.jdbc.update("UPDATE instrument SET reference_price_cents = 12050 WHERE id = ?", instrument))
				.isEqualTo(1);
			// The price it already has: the trigger checks the state, not whether the value changed.
			assertThat(f.jdbc.update("UPDATE instrument SET reference_price_cents = 12050 WHERE id = ?", instrument))
				.isEqualTo(1);
			assertFails(CHECK_VIOLATION,
					() -> f.jdbc.update("UPDATE instrument SET reference_price_cents = 0 WHERE id = ?", instrument));
		});
		for (String state : new String[] { "OPEN", "HALTED" }) {
			db.inRolledBackTransaction((f) -> {
				UUID simulation = f.simulation();
				UUID instrument = f.instrument(simulation, "NOVA");
				f.jdbc.update("UPDATE simulation SET state = ?, opened_at = now() WHERE id = ?", state, simulation);
				// Trading still advances the instrument's own counter.
				f.jdbc.update("UPDATE instrument SET last_trade_sequence = 1 WHERE id = ?", instrument);
				assertFails(NOT_IN_PREREQUISITE_STATE, () -> f.jdbc
					.update("UPDATE instrument SET reference_price_cents = 10000 WHERE id = ?", instrument));
			});
		}
		db.inRolledBackTransaction((f) -> {
			UUID instrument = f.instrument(f.simulation(), "NOVA");
			assertFails(INSUFFICIENT_PRIVILEGE,
					() -> f.jdbc.update("UPDATE instrument SET tick_cents = 5 WHERE id = ?", instrument));
		});
	}

	/** The runtime role may delete an instrument in SETUP that nothing refers to; the database refuses anything else. */
	@Test
	void instrumentsAreDeletedOnlyInSetupAndOnlyWhenUnused() {
		db.inRolledBackTransaction((f) -> {
			UUID simulation = f.simulation();
			UUID unused = f.instrument(simulation, "NOVA");
			UUID held = f.instrument(simulation, "ORBT");
			f.jdbc.update("""
					INSERT INTO position (account_id, account_kind, instrument_id, total_quantity, reserved_quantity)
					VALUES (?, 'BOT', ?, 10, 0)""", f.bot(simulation), held);
			assertThat(f.jdbc.update("DELETE FROM instrument WHERE id = ?", unused)).isEqualTo(1);
			assertFails(FOREIGN_KEY_VIOLATION, () -> f.jdbc.update("DELETE FROM instrument WHERE id = ?", held));
		});
		for (String state : new String[] { "OPEN", "HALTED" }) {
			db.inRolledBackTransaction((f) -> {
				UUID simulation = f.simulation();
				UUID instrument = f.instrument(simulation, "NOVA");
				f.jdbc.update("UPDATE simulation SET state = ?, opened_at = now() WHERE id = ?", state, simulation);
				assertFails(NOT_IN_PREREQUISITE_STATE,
						() -> f.jdbc.update("DELETE FROM instrument WHERE id = ?", instrument));
			});
		}
	}

	@Test
	void onlyBotAccountsHoldTokensAndOnlyAsHashes() {
		db.inRolledBackTransaction((f) -> {
			UUID bot = f.bot(f.simulation());
			token(f, bot, "BOT", 32, 1);
			assertFails(UNIQUE_VIOLATION, () -> token(f, bot, "BOT", 32, 1));
		});
		db.inRolledBackTransaction((f) -> assertFails(FOREIGN_KEY_VIOLATION,
				() -> token(f, f.trader(f.simulation()), "BOT", 32, 1)));
		db.inRolledBackTransaction((f) -> assertFails(CHECK_VIOLATION,
				() -> token(f, f.trader(f.simulation()), "TRADER", 32, 1)));
		db.inRolledBackTransaction(
				(f) -> assertFails(CHECK_VIOLATION, () -> token(f, f.bot(f.simulation()), "BOT", 31, 1)));
	}

	@Test
	void aRevokedTokenStaysRevokedAndNothingElseChanges() {
		db.inRolledBackTransaction((f) -> {
			UUID token = token(f, f.bot(f.simulation()), "BOT", 32, 2);
			assertThat(f.jdbc.update("UPDATE api_token SET revoked_at = now() WHERE id = ?", token)).isEqualTo(1);
			assertFails(CHECK_VIOLATION, () -> f.jdbc.update("UPDATE api_token SET revoked_at = NULL WHERE id = ?", token));
		});
		db.inRolledBackTransaction((f) -> {
			UUID token = token(f, f.bot(f.simulation()), "BOT", 32, 3);
			assertFails(INSUFFICIENT_PRIVILEGE,
					() -> f.jdbc.update("UPDATE api_token SET token_hash = ? WHERE id = ?", new byte[32], token));
		});
		db.inRolledBackTransaction((f) -> {
			UUID token = token(f, f.bot(f.simulation()), "BOT", 32, 4);
			assertFails(INSUFFICIENT_PRIVILEGE, () -> f.jdbc.update("DELETE FROM api_token WHERE id = ?", token));
		});
	}

	private static UUID token(SchemaFixtures f, UUID account, String kind, int hashLength, int fill) {
		UUID id = UUID.randomUUID();
		byte[] hash = new byte[hashLength];
		Arrays.fill(hash, (byte) fill);
		f.jdbc.update("INSERT INTO api_token (id, account_id, account_kind, token_hash, scope) VALUES (?, ?, ?, ?, 'TRADE')",
				id, account, kind, hash);
		return id;
	}

}
