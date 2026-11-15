package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.json.JsonMapper;

/**
 * A market far past the 1,000 bots it was once limited to: provisioning goes on, and one run of 10,000 bots starts
 * inside a tight command timeout, each strategy run by its oldest bots. The writer assigns each strategy's bots in one
 * statement instead of one insert per bot. Plain rows stand in for most bots; starting a run needs no tokens.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=bot-run-scale", "mse.dev-users.trader-password=" + BotRunScaleIT.PASSWORD,
				"mse.dev-users.operator-password=" + BotRunScaleIT.PASSWORD,
				// Well under the 5 s default: a start that went back to inserting assignments one by one fails here
				// rather than passing slowly (a planted bug confirmed it). It takes about 150 ms on a laptop.
				"mse.trading.command-timeout=2s" })
class BotRunScaleIT {

	static final String PASSWORD = "test-bot-run-scale-password";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final List<String> STRATEGIES = List.of("MARKET_MAKER", "NOISE", "MOMENTUM");

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void tenThousandBotsStartOneRunAndEachStrategyIsRunByItsOldest() throws Exception {
		TestBrowser operator = TestBrowser.loggedIn(port, "operator", PASSWORD);
		HttpResponse<String> added = operator.post("/api/v1/admin/instruments",
				"{\"symbol\":\"NOVA\",\"referencePrice\":\"100.00\"}", "Idempotency-Key", UUID.randomUUID().toString());
		assertThat(added.statusCode()).as(added.body()).isEqualTo(201);

		// 30,000 tagged bots, a second apart and the strategies taking turns, so creation order decides which run;
		// and 100 untagged ones, newest of all, which no run takes.
		insertBots("""
				INSERT INTO account (id, simulation_id, kind, display_name, bot_strategy, created_at)
				SELECT gen_random_uuid(), s.id, 'BOT', 'filler-' || n, (ARRAY['MARKET_MAKER', 'NOISE', 'MOMENTUM'])[1 + n % 3],
				    timestamptz '2026-01-01 00:00:00+00' + make_interval(secs => n)
				FROM simulation s, generate_series(1, 30000) n""", 30_000);
		insertBots("""
				INSERT INTO account (id, simulation_id, kind, display_name, created_at)
				SELECT gen_random_uuid(), s.id, 'BOT', 'untagged-' || n,
				    timestamptz '2026-02-01 00:00:00+00' + make_interval(secs => n)
				FROM simulation s, generate_series(1, 100) n""", 100);

		// Provisioning goes on past every limit there was; names grow a digit after bot-9999.
		HttpResponse<String> provisioned = operator.post("/api/v1/admin/bots", """
				{"count":1,"cash":"10000.00","holdings":[],"strategy":"NOISE"}""", "Idempotency-Key",
				UUID.randomUUID().toString());
		assertThat(provisioned.statusCode()).as(provisioned.body()).isEqualTo(201);
		assertThat(JSON.readTree(provisioned.body()).get("lastBot").asString()).isEqualTo("bot-30101");
		assertThat(JSON.readTree(operator.get("/api/v1/admin/bots").body()).get("population").asInt())
			.isEqualTo(30_101);

		assertThat(operator.post("/api/v1/admin/market/open", "", "Idempotency-Key", UUID.randomUUID().toString())
			.statusCode()).isEqualTo(200);

		// 2,000 market makers, 6,000 noise and 2,000 momentum bots: answered within the command timeout of 2 s set
		// above, or the request would have failed rather than started.
		long began = System.nanoTime();
		HttpResponse<String> started = operator.post("/api/v1/admin/bot-runs",
				"{\"marketMakers\":2000,\"noise\":6000,\"momentum\":2000,\"commandsPerSecond\":\"100.00\"}",
				"Idempotency-Key", UUID.randomUUID().toString());
		long millis = (System.nanoTime() - began) / 1_000_000;
		assertThat(started.statusCode()).as(started.body()).isEqualTo(201);
		System.out.println("A run of 10,000 bots among 30,101 started in " + millis + " ms");
		UUID run = UUID.fromString(JSON.readTree(started.body()).get("runId").asString());

		assertThat(jdbc.queryForObject("SELECT count(*) FROM bot_assignment WHERE run_id = ?", Long.class, run))
			.isEqualTo(10_000);
		int[] wanted = { 2_000, 6_000, 2_000 };
		for (int i = 0; i < STRATEGIES.size(); i++) {
			String strategy = STRATEGIES.get(i);
			List<UUID> oldest = jdbc.queryForList("""
					SELECT id FROM account WHERE kind = 'BOT' AND bot_strategy = ? ORDER BY created_at, id LIMIT ?""",
					UUID.class, strategy, wanted[i]);
			List<UUID> assigned = jdbc.queryForList(
					"SELECT account_id FROM bot_assignment WHERE run_id = ? AND strategy = ?", UUID.class, run,
					strategy);
			assertThat(assigned).as(strategy).hasSize(wanted[i]).containsExactlyInAnyOrderElementsOf(oldest);
		}
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM bot_assignment b JOIN account a ON a.id = b.account_id
				WHERE b.run_id = ? AND a.bot_strategy IS NULL""", Long.class, run)).as("untagged").isZero();
	}

	private void insertBots(String sql, int rows) {
		assertThat(jdbc.update(sql)).isEqualTo(rows);
	}

}
