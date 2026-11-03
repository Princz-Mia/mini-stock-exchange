package com.mia_princz.mse.execution;

import static com.mia_princz.mse.execution.SchemaFixtures.CHECK_VIOLATION;
import static com.mia_princz.mse.execution.SchemaFixtures.FOREIGN_KEY_VIOLATION;
import static com.mia_princz.mse.execution.SchemaFixtures.INSUFFICIENT_PRIVILEGE;
import static com.mia_princz.mse.execution.SchemaFixtures.UNIQUE_VIOLATION;
import static com.mia_princz.mse.execution.SchemaFixtures.assertFails;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LedgerSchemaIT {

	private static final String POST = "INSERT INTO ledger_entry (batch_id, account_id, instrument_id, bucket, delta) VALUES (?, ?, ?, ?, ?)";

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	PostgreSQLContainer postgres;

	SchemaFixtures db;

	@BeforeEach
	void fixtures() {
		db = new SchemaFixtures(jdbcTemplate, transactionManager);
	}

	/** Genesis moves cash and shares from issuance to a trader; each asset sums to zero. */
	@Test
	void balancedGenesisIsAccepted() {
		db.inRolledBackTransaction((f) -> {
			Accounts a = Accounts.create(f);
			UUID batch = batch(f, a.simulation, "GENESIS");
			post(f, batch, a.issuance, null, "AVAILABLE", -10_000_000);
			post(f, batch, a.trader, null, "AVAILABLE", 10_000_000);
			post(f, batch, a.issuance, a.instrument, "AVAILABLE", -1_000);
			post(f, batch, a.trader, a.instrument, "AVAILABLE", 1_000);
			f.jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
			assertThat(f.jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE batch_id = ?", Long.class, batch))
				.isEqualTo(4);
		});
	}

	@Test
	void unbalancedBatchIsRejectedAtCommit() {
		db.inRolledBackTransaction((f) -> {
			Accounts a = Accounts.create(f);
			UUID batch = batch(f, a.simulation, "GENESIS");
			post(f, batch, a.issuance, null, "AVAILABLE", -100);
			post(f, batch, a.trader, null, "AVAILABLE", 99);
			assertFails(CHECK_VIOLATION, () -> f.jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE"));
		});
	}

	@Test
	void eachAssetMustBalanceSeparately() {
		db.inRolledBackTransaction((f) -> {
			Accounts a = Accounts.create(f);
			UUID batch = batch(f, a.simulation, "GENESIS");
			// Cash and shares each off by 100 in opposite directions: the total is zero, each asset is not.
			post(f, batch, a.issuance, null, "AVAILABLE", -100);
			post(f, batch, a.trader, null, "AVAILABLE", 200);
			post(f, batch, a.issuance, a.instrument, "AVAILABLE", -200);
			post(f, batch, a.trader, a.instrument, "AVAILABLE", 100);
			assertFails(CHECK_VIOLATION, () -> f.jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE"));
		});
	}

	@Test
	void balanceIsCheckedAtTheEndNotAfterEachRow() {
		db.inRolledBackTransaction((f) -> {
			Accounts a = Accounts.create(f);
			UUID batch = batch(f, a.simulation, "RESERVE");
			post(f, batch, a.trader, null, "AVAILABLE", -500);
			post(f, batch, a.trader, null, "RESERVED", 500);
			f.jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
		});
	}

	@Test
	void zeroPostingsAreRejected() {
		db.inRolledBackTransaction((f) -> {
			Accounts a = Accounts.create(f);
			UUID batch = batch(f, a.simulation, "GENESIS");
			assertFails(CHECK_VIOLATION, () -> post(f, batch, a.trader, null, "AVAILABLE", 0));
		});
	}

	@Test
	void runtimeRoleCannotChangeOrDeleteTheJournal() {
		db.inRolledBackTransaction((f) -> {
			UUID batch = balancedBatch(f);
			assertFails(INSUFFICIENT_PRIVILEGE, () -> f.jdbc.update("UPDATE ledger_entry SET delta = 1 WHERE batch_id = ?", batch));
		});
		db.inRolledBackTransaction((f) -> {
			UUID batch = balancedBatch(f);
			assertFails(INSUFFICIENT_PRIVILEGE, () -> f.jdbc.update("DELETE FROM ledger_entry WHERE batch_id = ?", batch));
		});
		db.inRolledBackTransaction((f) -> {
			UUID batch = balancedBatch(f);
			assertFails(INSUFFICIENT_PRIVILEGE, () -> f.jdbc.update("DELETE FROM journal_batch WHERE id = ?", batch));
		});
	}

	/** Even the owner role, which has every grant, is stopped by the triggers. */
	@Test
	void ownerCannotChangeOrTruncateTheJournalEither() throws SQLException {
		String url = TestcontainersConfiguration.jdbcUrl(postgres, "execution_db");
		try (Connection owner = DriverManager.getConnection(url, "execution_owner",
				TestcontainersConfiguration.EXECUTION_OWNER_PASSWORD); Statement statement = owner.createStatement()) {
			owner.setAutoCommit(false);
			// Row triggers only fire on existing rows, so the journal needs a real batch first.
			UUID simulation = UUID.randomUUID();
			UUID batch = UUID.randomUUID();
			UUID issuance = UUID.randomUUID();
			UUID bot = UUID.randomUUID();
			statement.execute("INSERT INTO simulation (id, state) VALUES ('" + simulation + "', 'SETUP')");
			statement.execute("INSERT INTO account (id, simulation_id, kind, display_name) VALUES ('" + issuance + "', '"
					+ simulation + "', 'ISSUANCE', 'Issuance'), ('" + bot + "', '" + simulation + "', 'BOT', 'Bot')");
			statement.execute("INSERT INTO journal_batch (id, simulation_id, cause, command_sequence) VALUES ('" + batch
					+ "', '" + simulation + "', 'GENESIS', 1)");
			statement.execute("INSERT INTO ledger_entry (batch_id, account_id, bucket, delta) VALUES ('" + batch + "', '"
					+ issuance + "', 'AVAILABLE', -5), ('" + batch + "', '" + bot + "', 'AVAILABLE', 5)");
			// Run the deferred balance check now; a pending one would block TRUNCATE before the trigger is reached.
			statement.execute("SET CONSTRAINTS ALL IMMEDIATE");
			for (String sql : new String[] { "UPDATE ledger_entry SET delta = delta", "DELETE FROM ledger_entry",
					"TRUNCATE ledger_entry CASCADE", "UPDATE journal_batch SET cause = cause" }) {
				statement.execute("SAVEPOINT attempt");
				assertThatThrownBy(() -> statement.execute(sql)).as(sql)
					.isInstanceOfSatisfying(SQLException.class,
							(ex) -> assertThat(ex.getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));
				statement.execute("ROLLBACK TO SAVEPOINT attempt");
			}
			owner.rollback();
		}
	}

	@Test
	void customerBalancesCannotGoNegativeOrOverReserve() {
		db.inRolledBackTransaction((f) -> {
			Accounts a = Accounts.create(f);
			assertFails(CHECK_VIOLATION, () -> cash(f, a.trader, -1, 0));
		});
		db.inRolledBackTransaction((f) -> {
			Accounts a = Accounts.create(f);
			assertFails(CHECK_VIOLATION, () -> cash(f, a.trader, 100, 101));
		});
		db.inRolledBackTransaction((f) -> {
			Accounts a = Accounts.create(f);
			cash(f, a.trader, 100, 0);
			assertFails(CHECK_VIOLATION,
					() -> f.jdbc.update("UPDATE cash_balance SET reserved_cents = 200 WHERE account_id = ?", a.trader));
		});
		db.inRolledBackTransaction((f) -> {
			Accounts a = Accounts.create(f);
			assertFails(CHECK_VIOLATION, () -> f.jdbc.update(
					"INSERT INTO position (account_id, account_kind, instrument_id, total_quantity, reserved_quantity) VALUES (?, 'TRADER', ?, 5, 6)",
					a.trader, a.instrument));
		});
	}

	@Test
	void issuanceAccountCannotHaveABalanceRow() {
		db.inRolledBackTransaction((f) -> {
			Accounts a = Accounts.create(f);
			assertFails(CHECK_VIOLATION, () -> f.jdbc.update(
					"INSERT INTO cash_balance (account_id, account_kind, total_cents, reserved_cents) VALUES (?, 'ISSUANCE', 0, 0)",
					a.issuance));
		});
		db.inRolledBackTransaction((f) -> {
			Accounts a = Accounts.create(f);
			assertFails(FOREIGN_KEY_VIOLATION, () -> f.jdbc.update(
					"INSERT INTO cash_balance (account_id, account_kind, total_cents, reserved_cents) VALUES (?, 'TRADER', 0, 0)",
					a.issuance));
		});
	}

	@Test
	void eachAccountHasOneAllocationPerAsset() {
		db.inRolledBackTransaction((f) -> {
			Accounts a = Accounts.create(f);
			UUID batch = balancedBatch(f, a);
			String insert = "INSERT INTO initial_allocation (account_id, instrument_id, amount, journal_batch_id) VALUES (?, ?, 100, ?)";
			f.jdbc.update(insert, a.trader, null, batch);
			assertFails(UNIQUE_VIOLATION, () -> f.jdbc.update(insert, a.trader, null, batch));
		});
	}

	private static UUID balancedBatch(SchemaFixtures f) {
		return balancedBatch(f, Accounts.create(f));
	}

	private static UUID balancedBatch(SchemaFixtures f, Accounts a) {
		UUID batch = batch(f, a.simulation, "GENESIS");
		post(f, batch, a.issuance, null, "AVAILABLE", -100);
		post(f, batch, a.trader, null, "AVAILABLE", 100);
		return batch;
	}

	private static UUID batch(SchemaFixtures f, UUID simulation, String cause) {
		UUID id = UUID.randomUUID();
		f.jdbc.update("INSERT INTO journal_batch (id, simulation_id, cause, command_sequence) VALUES (?, ?, ?, 1)", id,
				simulation, cause);
		return id;
	}

	private static void post(SchemaFixtures f, UUID batch, UUID account, UUID instrument, String bucket, long delta) {
		f.jdbc.update(POST, batch, account, instrument, bucket, delta);
	}

	private static void cash(SchemaFixtures f, UUID account, long total, long reserved) {
		f.jdbc.update(
				"INSERT INTO cash_balance (account_id, account_kind, total_cents, reserved_cents) VALUES (?, 'TRADER', ?, ?)",
				account, total, reserved);
	}

	private record Accounts(UUID simulation, UUID trader, UUID issuance, UUID instrument) {

		static Accounts create(SchemaFixtures f) {
			UUID simulation = f.simulation();
			return new Accounts(simulation, f.trader(simulation), f.issuance(simulation), f.instrument(simulation, "NOVA"));
		}

	}

}
