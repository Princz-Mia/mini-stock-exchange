package com.mia_princz.mse.execution.trading.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import com.mia_princz.mse.execution.TestcontainersConfiguration;

/**
 * Startup reconciliation on a ledger the size of a restored development database: 10,000 accounts and about 3.1
 * million batches and 6.3 million entries, generated from a fixed seed ({@link GeneratedLedger}). Each of the two
 * checks that read the whole ledger is timed as the planner runs it unaided and as the store runs it, in one pass,
 * twice each, alternating; then the whole reconciliation is timed.
 *
 * <p>
 * Testcontainers runs PostgreSQL with its default 128 MB of shared buffers, where Compose gives it 512 MB; the report
 * says which. Both ways read through the operating system's cache, so on a machine whose memory holds the ledger, the
 * unaided plan is far faster than it is on one that must read it from disk.
 *
 * <p>
 * Not part of the build; run it on its own:
 * {@code mvnw -pl services/execution-service verify -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false
 * -Dit.test=LedgerReconciliationBench}. It writes {@code target/ledger-reconciliation-<time>.txt}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "mse.test.database=ledger-reconciliation-bench")
class LedgerReconciliationBench {

	private static final long SEED = 20261122L;

	@Autowired
	DataSource dataSource;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	PostgreSQLContainer postgres;

	@Test
	void reconcile() throws Exception {
		JdbcClient jdbc = JdbcClient.create(dataSource);
		JdbcTradingStore store = new JdbcTradingStore(jdbc, JsonMapper.builder().build());
		StringBuilder report = new StringBuilder();
		long began = System.nanoTime();
		GeneratedLedger.Ledger ledger;
		try (Connection owner = DriverManager.getConnection(TestcontainersConfiguration.jdbcUrl(postgres, "execution_db"),
				"execution_owner", TestcontainersConfiguration.EXECUTION_OWNER_PASSWORD)) {
			ledger = GeneratedLedger.write(owner, SEED, 10_000, 5, 1_550_000);
		}
		report.append(String.format(Locale.ROOT, "Ledger: %,d batches, %,d entries, generated in %,d s%n", ledger.batches(),
				ledger.entries(), (System.nanoTime() - began) / 1_000_000_000));
		report.append(String.format(Locale.ROOT, "PostgreSQL: shared_buffers=%s, work_mem=%s, effective_cache_size=%s; tables %s%n%n",
				setting(jdbc, "shared_buffers"), setting(jdbc, "work_mem"), setting(jdbc, "effective_cache_size"),
				jdbc.sql("""
						SELECT 'ledger_entry ' || pg_size_pretty(pg_total_relation_size('ledger_entry'))
						    || ', journal_batch ' || pg_size_pretty(pg_total_relation_size('journal_batch'))""")
					.query(String.class)
					.single()));

		Supplier<List<UUID>> balances = () -> jdbc.sql(JdbcTradingStore.LEDGER_AGAINST_BALANCES)
			.param("simulation", ledger.simulation())
			.param("cash", new UUID(0, 0))
			.query((row, index) -> row.getObject("account_id", UUID.class))
			.list();
		Supplier<List<UUID>> batches = () -> jdbc.sql(JdbcTradingStore.UNBALANCED_BATCHES)
			.param(ledger.simulation())
			.query((row, index) -> row.getObject("batch_id", UUID.class))
			.list();
		report.append("Check                     Unaided ms   One pass ms\n");
		for (int round = 1; round <= 2; round++) {
			for (var check : List.of(new Check("Balances against ledger", balances), new Check("Unbalanced batches", batches))) {
				long unaided = millis(() -> inTransaction(check.statement()));
				long onePass = millis(() -> inTransaction(() -> store.readingWholeLedger(check.statement())));
				report.append(String.format(Locale.ROOT, "%-25s %10d %13d%n", check.name(), unaided, onePass));
			}
		}
		long whole = System.nanoTime();
		List<String> problems = inTransaction(() -> store.reconciliationProblems(ledger.simulation()));
		report.append(String.format(Locale.ROOT, "%nWhole reconciliation: %,d ms, %d problems%n", (System.nanoTime() - whole) / 1_000_000,
				problems.size()));

		System.out.println(report);
		Path out = Path.of("target", "ledger-reconciliation-" + Instant.now().toString().replace(':', '-') + ".txt");
		Files.writeString(out, report);
		assertThat(problems).isEmpty();
	}

	private record Check(String name, Supplier<List<UUID>> statement) {
	}

	private static String setting(JdbcClient jdbc, String name) {
		return jdbc.sql("SELECT current_setting(?)").param(name).query(String.class).single();
	}

	private long millis(Runnable work) {
		long began = System.nanoTime();
		work.run();
		return (System.nanoTime() - began) / 1_000_000;
	}

	private <T> T inTransaction(Supplier<T> work) {
		return new TransactionTemplate(transactionManager).execute((status) -> {
			status.setRollbackOnly();
			return work.get();
		});
	}

}
