package com.mia_princz.mse.execution.trading.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import com.mia_princz.mse.execution.TestcontainersConfiguration;

/**
 * Startup reconciliation on a ledger of a million batches and two million entries, generated from a fixed seed. The
 * two checks that read the whole ledger must read each table in one pass, never walk an index per batch or per
 * account, and still find what they found before. Each planted fault is written in a transaction that is rolled back,
 * so the commit-time balance trigger never runs on it, and is looked for in that same transaction, as startup would.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "mse.test.database=ledger-reconciliation")
@TestInstance(Lifecycle.PER_CLASS)
class LedgerReconciliationIT {

	private static final long SEED = 20261122L;

	private static final int ACCOUNTS = 2_000;

	private static final int INSTRUMENTS = 5;

	private static final int RESERVATIONS = 500_000;

	@Autowired
	DataSource dataSource;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	PostgreSQLContainer postgres;

	private JdbcClient jdbc;

	private JdbcTradingStore store;

	private GeneratedLedger.Ledger ledger;

	@BeforeAll
	void writeLedger() throws SQLException {
		jdbc = JdbcClient.create(dataSource);
		store = new JdbcTradingStore(jdbc, JsonMapper.builder().build());
		long began = System.nanoTime();
		try (Connection owner = DriverManager.getConnection(TestcontainersConfiguration.jdbcUrl(postgres, "execution_db"),
				"execution_owner", TestcontainersConfiguration.EXECUTION_OWNER_PASSWORD)) {
			ledger = GeneratedLedger.write(owner, SEED, ACCOUNTS, INSTRUMENTS, RESERVATIONS);
		}
		System.out.printf(Locale.ROOT, "Generated %,d batches and %,d entries in %,d ms%n", ledger.batches(), ledger.entries(),
				(System.nanoTime() - began) / 1_000_000);
	}

	@Test
	void aBalancedLedgerReconciles() {
		assertThat(ledger.entries()).isGreaterThan(2_000_000);
		long began = System.nanoTime();
		List<String> problems = inRolledBackTransaction(() -> store.reconciliationProblems(ledger.simulation()));
		System.out.printf(Locale.ROOT, "Reconciled in %,d ms%n", (System.nanoTime() - began) / 1_000_000);
		assertThat(problems).isEmpty();
	}

	@Test
	void theWholeLedgerChecksReadEachTableInOnePass() {
		for (String plan : inRolledBackTransaction(() -> store.readingWholeLedger(this::wholeLedgerPlans))) {
			System.out.println(plan);
			assertThat(plan).contains("Seq Scan on ledger_entry")
				.doesNotContain("Nested Loop")
				.doesNotContain("Index")
				.doesNotContain("Bitmap");
		}
	}

	/** The same statements as the planner chooses for them unaided: an index walked in random order. */
	@Test
	void unaidedThePlannerWalksTheLedgerByIndex() {
		List<String> plans = inRolledBackTransaction(this::wholeLedgerPlans);
		plans.forEach(System.out::println);
		assertThat(plans.get(0)).contains("Index Scan using ledger_entry_account");
		assertThat(plans.get(1)).contains("Index Scan using ledger_entry_batch");
	}

	/**
	 * Reconciliation as the store runs it: each statement it prepares is recorded with the planner settings in force on
	 * its connection at that moment. The two that read the ledger run with index and bitmap scans off; the holds check,
	 * between them, runs with both on again.
	 */
	@Test
	void onlyTheLedgerChecksRunWithIndexScansOff() {
		List<String[]> prepared = new CopyOnWriteArrayList<>();
		DataSource recording = recording(dataSource, prepared);
		JdbcTradingStore recorded = new JdbcTradingStore(JdbcClient.create(recording), JsonMapper.builder().build());
		TransactionTemplate transactions = new TransactionTemplate(new JdbcTransactionManager(recording));
		List<String> problems = transactions.execute((status) -> {
			status.setRollbackOnly();
			return recorded.reconciliationProblems(ledger.simulation());
		});
		assertThat(problems).isEmpty();
		assertThat(prepared).filteredOn((statement) -> statement[0].contains("ledger_entry"))
			.extracting((statement) -> statement[1])
			.containsExactly("off off", "off off");
		assertThat(prepared).filteredOn((statement) -> statement[0].contains("FROM orders"))
			.extracting((statement) -> statement[1])
			.containsExactly("on on");
	}

	@Test
	void thePlannerSettingsAreRestoredAfterTheChecks() {
		List<String> after = inRolledBackTransaction(() -> {
			store.reconciliationProblems(ledger.simulation());
			return jdbc.sql("SELECT current_setting('enable_indexscan') || ' ' || current_setting('enable_bitmapscan')")
				.query(String.class)
				.list();
		});
		assertThat(after).containsExactly("on on");
	}

	/**
	 * Cash leaves an account and the same number of shares arrive: the batch sums to zero, and only the check per asset
	 * finds it.
	 */
	@Test
	void aBatchUnbalancedInOneAssetIsFound() {
		UUID account = ledger.accounts().get(17);
		UUID instrument = ledger.instruments().get(2);
		UUID batch = UUID.randomUUID();
		List<String> problems = inRolledBackTransaction(() -> {
			jdbc.sql("INSERT INTO journal_batch (id, simulation_id, cause, command_sequence) VALUES (?, ?, 'RESERVE', ?)")
				.params(batch, ledger.simulation(), ledger.batches() + 1)
				.update();
			jdbc.sql("""
					INSERT INTO ledger_entry (batch_id, account_id, instrument_id, bucket, delta)
					VALUES (?, ?, NULL, 'AVAILABLE', -500), (?, ?, ?, 'AVAILABLE', 500)""")
				.params(batch, account, batch, account, instrument)
				.update();
			return store.reconciliationProblems(ledger.simulation());
		});
		assertThat(problems).contains("Journal batch " + batch + " does not balance");
	}

	@Test
	void aBalanceThatDisagreesWithTheLedgerIsFound() {
		UUID account = ledger.accounts().get(1_234);
		List<String> problems = inRolledBackTransaction(() -> {
			jdbc.sql("UPDATE cash_balance SET total_cents = total_cents + 1 WHERE account_id = ?").param(account).update();
			return store.reconciliationProblems(ledger.simulation());
		});
		assertThat(problems).singleElement()
			.asString()
			.startsWith("Balance of account " + account + " in cash is ")
			.contains("but the ledger says");
	}

	/** The balances check, then the batch check, each explained with analysis, as the store sends them. */
	private List<String> wholeLedgerPlans() {
		String balances = String.join("\n", jdbc.sql("EXPLAIN (ANALYZE, BUFFERS) " + JdbcTradingStore.LEDGER_AGAINST_BALANCES)
			.param("simulation", ledger.simulation())
			.param("cash", new UUID(0, 0))
			.query(String.class)
			.list());
		String batches = String.join("\n", jdbc.sql("EXPLAIN (ANALYZE, BUFFERS) " + JdbcTradingStore.UNBALANCED_BATCHES)
			.param(ledger.simulation())
			.query(String.class)
			.list());
		return List.of(balances, batches);
	}

	/** Hands out connections that record each statement prepared on them, with the planner's settings at that moment. */
	private static DataSource recording(DataSource target, List<String[]> prepared) {
		return proxy(DataSource.class, (method, args) -> {
			Object result = invoke(method, target, args);
			return method.getName().equals("getConnection") ? recording((Connection) result, prepared) : result;
		});
	}

	private static Connection recording(Connection target, List<String[]> prepared) {
		return proxy(Connection.class, (method, args) -> {
			if (method.getName().equals("prepareStatement")) {
				try (Statement statement = target.createStatement();
						ResultSet settings = statement.executeQuery(
								"SELECT current_setting('enable_indexscan') || ' ' || current_setting('enable_bitmapscan')")) {
					settings.next();
					prepared.add(new String[] { (String) args[0], settings.getString(1) });
				}
			}
			return invoke(method, target, args);
		});
	}

	private interface Handler {

		Object handle(Method method, Object[] args) throws Throwable;

	}

	/** Compares by identity, as a pool's own wrappers do, and passes every other call to {@code handler}. */
	private static <T> T proxy(Class<T> type, Handler handler) {
		return type.cast(Proxy.newProxyInstance(LedgerReconciliationIT.class.getClassLoader(), new Class<?>[] { type },
				(proxy, method, args) -> switch (method.getName()) {
					case "equals" -> proxy == args[0];
					case "hashCode" -> System.identityHashCode(proxy);
					default -> handler.handle(method, args);
				}));
	}

	private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
		try {
			return method.invoke(target, args);
		}
		catch (InvocationTargetException ex) {
			throw ex.getCause();
		}
	}

	private <T> T inRolledBackTransaction(Supplier<T> work) {
		return new TransactionTemplate(transactionManager).execute((status) -> {
			status.setRollbackOnly();
			return work.get();
		});
	}

}
