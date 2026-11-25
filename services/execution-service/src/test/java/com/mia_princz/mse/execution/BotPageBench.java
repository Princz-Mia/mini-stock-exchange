package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reading the bots of a 20,000-bot run among 40,000 bot accounts, as the simulator does when it takes a run: every
 * page of 1,000 over HTTP with the simulator's token, six times in a row, against PostgreSQL in Testcontainers. The
 * accounts are written straight into the table, with no funding or token, because only the assignment and the
 * account's creation time matter to a page.
 * <p>
 * The report also explains, with buffers, the page after the run's middle bot read two ways: by the creation time
 * kept on each assignment, as the simulator's route reads it, and through a join to the account, as pages were read
 * before the time was kept there. The second shows what the index range saves.
 * <p>
 * Not part of the build; run it on its own:
 * {@code mvnw -pl services/execution-service verify -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false
 * -Dit.test=BotPageBench}. It writes {@code target/bot-pages-<time>.txt}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=bot-page-bench", "mse.dev-users.trader-password=" + BotPageBench.PASSWORD,
				"mse.dev-users.operator-password=" + BotPageBench.PASSWORD,
				"mse.simulator.token=" + BotPageBench.SIMULATOR_TOKEN })
class BotPageBench {

	static final String PASSWORD = "bench-bot-page-password";

	static final String SIMULATOR_TOKEN = "bench-bot-page-simulator";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final int ACCOUNTS = 40_000;

	private static final int BOTS = 20_000;

	private static final int READS = 6;

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PostgreSQLContainer postgres;

	@Test
	void readEveryPageOfATwentyThousandBotRun() throws Exception {
		TestBrowser operator = TestBrowser.loggedIn(port, "operator", PASSWORD);
		TestBrowser simulator = new TestBrowser(port);
		HttpResponse<String> added = operator.post("/api/v1/admin/instruments",
				"{\"symbol\":\"NOVA\",\"referencePrice\":\"100.00\"}", "Idempotency-Key", UUID.randomUUID().toString());
		assertThat(added.statusCode()).as(added.body()).isEqualTo(201);
		// One second apart, so no two accounts share a creation time.
		jdbc.update("""
				INSERT INTO account (id, simulation_id, kind, display_name, bot_strategy, created_at)
				SELECT gen_random_uuid(), s.id, 'BOT', 'bench-' || n,
				    (ARRAY['MARKET_MAKER', 'NOISE', 'MOMENTUM'])[1 + n % 3],
				    timestamptz '2026-01-01 00:00:00+00' + make_interval(secs => n)
				FROM simulation s, generate_series(1, ?) n""", ACCOUNTS);
		analyze();
		assertThat(operator.post("/api/v1/admin/market/open", "", "Idempotency-Key", UUID.randomUUID().toString())
			.statusCode()).isEqualTo(200);
		HttpResponse<String> started = operator.post("/api/v1/admin/bot-runs", """
				{"marketMakers":%d,"noise":%d,"momentum":%d,"commandsPerSecond":"100.00"}""".formatted(BOTS / 5,
				BOTS - 2 * (BOTS / 5), BOTS / 5), "Idempotency-Key", UUID.randomUUID().toString());
		assertThat(started.statusCode()).as(started.body()).isEqualTo(201);
		UUID run = UUID.fromString(JSON.readTree(started.body()).get("runId").asString());
		analyze();

		List<Long> rounds = new ArrayList<>();
		int pages = 0;
		for (int round = 0; round < READS; round++) {
			long began = System.nanoTime();
			int bots = 0;
			pages = 0;
			String cursor = null;
			do {
				HttpResponse<String> page = simulator.get("/internal/simulator/runs/" + run + "/bots?limit=1000"
						+ (cursor == null ? "" : "&cursor=" + cursor), "Authorization", "Bearer " + SIMULATOR_TOKEN);
				assertThat(page.statusCode()).as(page.body()).isEqualTo(200);
				JsonNode body = JSON.readTree(page.body());
				bots += body.get("bots").size();
				pages++;
				cursor = body.get("nextCursor").isNull() ? null : body.get("nextCursor").asString();
			}
			while (cursor != null);
			assertThat(bots).isEqualTo(BOTS);
			rounds.add((System.nanoTime() - began) / 1_000_000);
		}

		StringBuilder report = new StringBuilder();
		report.append(String.format(Locale.ROOT, "Bot pages of a run of %,d bots among %,d bot accounts%n", BOTS,
				ACCOUNTS));
		report.append("PostgreSQL fsync: ").append(jdbc.queryForObject("SHOW fsync", String.class)).append('\n');
		report.append("Assignments the statistics know of: ")
			.append(jdbc.queryForObject("SELECT reltuples::bigint FROM pg_class WHERE relname = 'bot_assignment'",
					Long.class))
			.append('\n');
		report.append(String.format(Locale.ROOT, "All %d pages of 1,000, ms per read, %d reads: %s%n", pages, READS,
				rounds));
		// The page after the run's middle bot, from the position its cursor would carry.
		String middle = jdbc.queryForObject("""
				SELECT format('(%L::timestamptz, %L::uuid)', account_created_at, account_id) FROM bot_assignment
				WHERE run_id = ? ORDER BY account_created_at, account_id OFFSET ? LIMIT 1""", String.class, run,
				BOTS / 2 - 1);
		report.append("\nThe page after the middle bot, by the creation time kept on the assignment:\n");
		explain("""
				SELECT account_id, strategy, account_created_at FROM bot_assignment
				WHERE run_id = '%s' AND (account_created_at, account_id) > %s
				ORDER BY account_created_at, account_id LIMIT 1001""".formatted(run, middle), report);
		report.append("\nThe same page through a join to the account:\n");
		explain("""
				SELECT b.account_id, b.strategy, a.created_at FROM bot_assignment b JOIN account a ON a.id = b.account_id
				WHERE b.run_id = '%s' AND (a.created_at, a.id) > %s
				ORDER BY a.created_at, a.id LIMIT 1001""".formatted(run, middle), report);

		System.out.print(report);
		Path file = Path.of("target", "bot-pages-" + DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
			.withZone(ZoneOffset.UTC)
			.format(Instant.now()) + ".txt");
		Files.writeString(file, report);
	}

	/** As execution_owner: the application's role may not analyze these tables, and its ANALYZE skips them. */
	private void analyze() throws SQLException {
		try (Connection owner = DriverManager.getConnection(TestcontainersConfiguration.jdbcUrl(postgres, "execution_db"),
				"execution_owner", TestcontainersConfiguration.EXECUTION_OWNER_PASSWORD);
				Statement statement = owner.createStatement()) {
			statement.execute("ANALYZE account, bot_assignment");
		}
	}

	private void explain(String sql, StringBuilder report) {
		jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS) " + sql, String.class)
			.forEach((line) -> report.append("  ").append(line).append('\n'));
	}

}
