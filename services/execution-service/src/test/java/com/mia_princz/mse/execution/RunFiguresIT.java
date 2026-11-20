package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.mia_princz.mse.execution.identity.ApiTokens;
import com.mia_princz.mse.execution.trading.adapter.RunFigures;

/**
 * A finished run's figures against an oracle that sees only what the test did: a seeded stream of placements and
 * cancels by five bots and a person across two runs, with refusals, commands after a stop, between runs and after both.
 * The oracle counts each answer it got, and takes each bot's balances itself at every run's edge, so cash and holdings
 * don't come from the trade arithmetic the figures use. The figures are computed only after both runs and more
 * trading, so holdings at an edge must be worked back from today's.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=run-figures", "mse.dev-users.operator-password=" + RunFiguresIT.PASSWORD,
				"mse.simulator.token=" + RunFiguresIT.SIMULATOR_TOKEN, "mse.trading.bot-commands-per-second=1000",
				"mse.trading.human-commands-per-second=1000", "mse.runs.figures-interval=1h" })
class RunFiguresIT {

	static final String PASSWORD = "test-run-figures-password";

	static final String SIMULATOR_TOKEN = "test-run-figures-simulator-token";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String RATE = "\"commandsPerSecond\":\"1.00\"";

	private static final long REFERENCE = 10_000;

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	RunFigures figures;

	private final Random random = new Random(20261120);

	private final List<Actor> actors = new ArrayList<>();

	private final List<String> instruments = new ArrayList<>();

	/** The run being sent, and the oracle's tally for it; null between runs. */
	private Tally tally;

	private record Actor(UUID account, String strategy, String[] auth, List<String> orders) {
	}

	/** What the oracle saw of one run. */
	private static final class Tally {

		String runId;

		final Map<UUID, String> assigned = new HashMap<>();

		final Map<String, long[]> commands = new TreeMap<>();

		final Map<String, Map<String, Long>> refusals = new TreeMap<>();

		final Set<String> orders = new HashSet<>();

		final Map<String, String> orderStrategy = new HashMap<>();

		long start;

		long end;

		Map<UUID, long[]> startBalances;

		/** The run's instruments, by index; all unless it names some. */
		final Set<Integer> traded = new HashSet<>(Set.of(0, 1));

		/** Bots the run assigns that send nothing while it runs. */
		final Set<UUID> silent = new HashSet<>();

		Map<UUID, long[]> endBalances;

	}

	@Test
	void aFinishedRunsFiguresMatchWhatItsBotsDid() throws Exception {
		TestBrowser operator = TestBrowser.loggedIn(port, "operator", PASSWORD);
		TestBrowser simulator = new TestBrowser(port);
		for (String symbol : new String[] { "NOVA", "ZETA" }) {
			instruments.add(post(operator, "/api/v1/admin/instruments",
					"{\"symbol\":\"%s\",\"referencePrice\":\"100.00\"}".formatted(symbol)).get("instrumentId").asString());
		}
		String holdings = "[{\"instrumentId\":\"%s\",\"quantity\":100},{\"instrumentId\":\"%s\",\"quantity\":100}]"
			.formatted(instruments.get(0), instruments.get(1));
		for (String[] tag : new String[][] { { "MARKET_MAKER", "2" }, { "NOISE", "2" }, { "MOMENTUM", "1" } }) {
			post(operator, "/api/v1/admin/bots", "{\"count\":%s,\"cash\":\"10000.00\",\"holdings\":%s,\"strategy\":\"%s\"}"
				.formatted(tag[1], holdings, tag[0]));
		}
		post(operator, "/api/v1/admin/traders", """
				{"username":"people.figures","password":"%s","cash":"100000.00","holdings":%s}"""
			.formatted(PASSWORD, holdings.replace("100}", "500}")));
		post(operator, "/api/v1/admin/market/open", "");
		for (Map<String, Object> bot : jdbc.queryForList(
				"SELECT id, bot_strategy FROM account WHERE kind = 'BOT' ORDER BY created_at, id")) {
			ApiTokens.Issued token = ApiTokens.issue();
			jdbc.update("""
					INSERT INTO api_token (id, account_id, account_kind, token_hash, scope)
					VALUES (?, ?, 'BOT', ?, 'TRADE')""", UUID.randomUUID(), bot.get("id"), token.hash());
			actors.add(new Actor((UUID) bot.get("id"), (String) bot.get("bot_strategy"),
					new String[] { "Authorization", "Bearer " + token.token() }, new ArrayList<>()));
		}
		TestBrowser person = TestBrowser.loggedIn(port, "people.figures", PASSWORD);
		actors.add(new Actor(null, null, null, new ArrayList<>()));
		Map<Actor, TestBrowser> browsers = new IdentityHashMap<>();
		actors.forEach((actor) -> browsers.put(actor, actor.account() == null ? person : new TestBrowser(port)));

		// Run A: every bot, its tail after the stop included; then commands between runs, in no run.
		Tally a = start(operator, "{\"marketMakers\":2,\"noise\":2,\"momentum\":1," + RATE + "}");
		send(browsers, 140);
		String runA = a.runId;
		post(operator, "/api/v1/admin/bot-runs/" + runA + "/stop", "");
		send(browsers, 20);
		finish(simulator, a, runA);
		send(browsers, 15);
		// Run B: the noise bots and a momentum bot that sends nothing, on one instrument, while every bot keeps trading
		// both: trades in the other instrument and by the unassigned bots stay out of its figures, and the silent bot's
		// orders left from run A may still fill in it.
		Tally b = start(operator, "{\"noise\":2,\"momentum\":1,\"instrumentIds\":[\"" + instruments.get(0) + "\"],"
				+ RATE + "}");
		b.traded.retainAll(Set.of(0));
		b.assigned.forEach((account, strategy) -> {
			if ("MOMENTUM".equals(strategy)) {
				b.silent.add(account);
			}
		});
		send(browsers, 100);
		String runB = b.runId;
		post(operator, "/api/v1/admin/bot-runs/" + runB + "/stop", "");
		finish(simulator, b, runB);
		send(browsers, 25);

		assertThat(figures.computeFinished()).as("both runs, once").isEqualTo(2);
		assertThat(figures.computeFinished()).isZero();
		expect(UUID.fromString(runA), a);
		expect(UUID.fromString(runB), b);
		assertThat(b.assigned.values()).containsOnly("NOISE", "MOMENTUM");
		assertThat(b.commands).as("the silent bot sent nothing").doesNotContainKey("MOMENTUM");
		// A run still going has no figures; figures are never rewritten.
		post(operator, "/api/v1/admin/bot-runs", "{\"noise\":1," + RATE + "}");
		assertThat(figures.computeFinished()).isZero();
		assertThatThrownBy(() -> jdbc.update("UPDATE bot_run_strategy_figures SET placements = 0")).rootCause()
			.hasMessageContaining("permission denied");
	}

	private Tally start(TestBrowser operator, String body) throws Exception {
		JsonNode started = post(operator, "/api/v1/admin/bot-runs", body);
		Tally run = new Tally();
		run.start = started.get("commandSequence").asLong();
		run.runId = started.get("runId").asString();
		jdbc.queryForList("SELECT account_id, strategy FROM bot_assignment WHERE run_id = ?",
				UUID.fromString(started.get("runId").asString()))
			.forEach((row) -> run.assigned.put((UUID) row.get("account_id"), (String) row.get("strategy")));
		run.startBalances = balances();
		tally = run;
		return run;
	}

	private void finish(TestBrowser simulator, Tally run, String runId) throws Exception {
		HttpResponse<String> beat = simulator.post("/internal/simulator/heartbeat", """
				{"runId":"%s","workerId":"figures-worker","placed":0,"canceled":0,"rejected":0,"errors":0,"inFlight":0,
				"stopFailures":0,"stopped":true}""".formatted(runId), "Authorization", "Bearer " + SIMULATOR_TOKEN);
		assertThat(beat.statusCode()).as(beat.body()).isEqualTo(200);
		run.end = jdbc.queryForObject("SELECT end_sequence FROM bot_run WHERE id = ?", Long.class,
				UUID.fromString(runId));
		run.endBalances = balances();
		tally = null;
	}

	/** Seeded commands from random actors: mostly small orders around 100.00, some too big, cancels of all kinds. */
	private void send(Map<Actor, TestBrowser> browsers, int commands) throws Exception {
		for (int i = 0; i < commands; i++) {
			Actor actor = actors.get(random.nextInt(actors.size()));
			if (tally != null && tally.silent.contains(actor.account())) {
				continue;
			}
			TestBrowser browser = browsers.get(actor);
			String[] auth = actor.auth() == null ? new String[0] : actor.auth();
			boolean cancel = random.nextInt(10) < 3 && (!actor.orders().isEmpty() || random.nextBoolean());
			HttpResponse<String> answer;
			int placedIn = -1;
			if (cancel) {
				String order = actor.orders().isEmpty() || random.nextInt(8) == 0 ? UUID.randomUUID().toString()
						: actor.orders().get(random.nextInt(actor.orders().size()));
				answer = browser.post("/api/v1/orders/" + order + "/cancel", "", with(auth));
			}
			else {
				boolean buy = random.nextBoolean();
				long quantity = random.nextInt(25) == 0 ? (buy ? 200 : 1000) : 1 + random.nextInt(5);
				String price = "%d.%02d".formatted(buy ? 99 + random.nextInt(2) : 100, random.nextInt(4) * 2 + (buy ? 92 : 0));
				int instrument = random.nextInt(2);
				answer = browser.post("/api/v1/orders", """
						{"instrumentId":"%s","side":"%s","type":"LIMIT","timeInForce":"GTC","quantity":%d,
						"limitPrice":"%s"}""".formatted(instruments.get(instrument), buy ? "BUY" : "SELL", quantity, price),
						with(auth));
				placedIn = instrument;
				if (answer.statusCode() == 201) {
					String id = JSON.readTree(answer.body()).get("orderId").asString();
					actor.orders().add(id);
					if (tally != null && tally.assigned.containsKey(actor.account()) && tally.traded.contains(placedIn)) {
						tally.orders.add(id);
						tally.orderStrategy.put(id, actor.strategy());
						tally.commands.computeIfAbsent(actor.strategy(), (s) -> new long[3])[2] += quantity;
					}
				}
			}
			assertThat(answer.statusCode()).as(answer.body()).isIn(200, 201, 404, 409, 422);
			if (tally != null && tally.assigned.containsKey(actor.account())) {
				tally.commands.computeIfAbsent(actor.strategy(), (s) -> new long[3])[cancel ? 1 : 0]++;
				if (answer.statusCode() >= 400) {
					tally.refusals.computeIfAbsent(actor.strategy(), (s) -> new TreeMap<>())
						.merge(JSON.readTree(answer.body()).get("code").asString(), 1L, Long::sum);
				}
			}
		}
	}

	/** Each bot's total cash and holdings of the two instruments, as the oracle reads them itself. */
	private Map<UUID, long[]> balances() {
		Map<UUID, long[]> balances = new HashMap<>();
		for (Actor actor : actors) {
			if (actor.account() == null) {
				continue;
			}
			long cash = jdbc.queryForObject("SELECT total_cents FROM cash_balance WHERE account_id = ?", Long.class,
					actor.account());
			long[] held = new long[] { cash, 0, 0 };
			for (int i = 0; i < 2; i++) {
				List<Long> quantity = jdbc.queryForList(
						"SELECT total_quantity FROM position WHERE account_id = ? AND instrument_id = ?", Long.class,
						actor.account(), UUID.fromString(instruments.get(i)));
				held[i + 1] = quantity.isEmpty() ? 0 : quantity.getFirst();
			}
			balances.put(actor.account(), held);
		}
		return balances;
	}

	/** Whether the instrument had traded at or before the sequence. */
	private boolean traded(int instrument, long sequence) {
		return jdbc.queryForObject("SELECT count(*) FROM trades WHERE instrument_id = ? AND command_sequence <= ?",
				Long.class, UUID.fromString(instruments.get(instrument)), sequence) > 0;
	}

	/** The newest trade's price at or before the sequence, or the reference price. */
	private long mark(int instrument, long sequence) {
		List<Long> price = jdbc.queryForList("""
				SELECT price_cents FROM trades WHERE instrument_id = ? AND command_sequence <= ?
				ORDER BY trade_sequence DESC LIMIT 1""", Long.class, UUID.fromString(instruments.get(instrument)), sequence);
		return price.isEmpty() ? REFERENCE : price.getFirst();
	}

	private void expect(UUID run, Tally oracle) {
		List<Map<String, Object>> trades = jdbc.queryForList("""
				SELECT instrument_id, quantity, price_cents, buyer_account_id, seller_account_id, maker_order_id,
				    taker_order_id FROM trades WHERE command_sequence BETWEEN ? AND ?""", oracle.start, oracle.end);
		trades.removeIf((trade) -> !oracle.traded.contains(instruments.indexOf(trade.get("instrument_id").toString())));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM bot_run_instrument_figures WHERE run_id = ?", Long.class,
				run)).isEqualTo(oracle.traded.size());
		for (int i : oracle.traded) {
			UUID instrument = UUID.fromString(instruments.get(i));
			long count = 0;
			long volume = 0;
			BigDecimal notional = BigDecimal.ZERO;
			for (Map<String, Object> trade : trades) {
				if (trade.get("instrument_id").equals(instrument)) {
					count++;
					volume += (Long) trade.get("quantity");
					notional = notional.add(BigDecimal.valueOf((Long) trade.get("quantity") * (Long) trade.get("price_cents")));
				}
			}
			long start = mark(i, oracle.start);
			long end = mark(i, oracle.end);
			assertThat(jdbc.queryForMap("""
					SELECT trades, volume, notional_cents, start_mark_cents, start_mark_source, end_mark_cents,
					    end_mark_source FROM bot_run_instrument_figures WHERE run_id = ? AND instrument_id = ?""", run,
					instrument).values().stream().map(String::valueOf).toList())
				.as("instrument %d", i)
				.containsExactly(Long.toString(count), Long.toString(volume), notional.toString(), Long.toString(start),
						traded(i, oracle.start) ? "LAST_TRADE" : "REFERENCE", Long.toString(end),
						traded(i, oracle.end) ? "LAST_TRADE" : "REFERENCE");
		}
		Map<String, List<String>> expected = new TreeMap<>();
		Map<String, Long> bots = new TreeMap<>();
		oracle.assigned.values().forEach((strategy) -> bots.merge(strategy, 1L, Long::sum));
		for (String strategy : bots.keySet()) {
			long[] commands = oracle.commands.getOrDefault(strategy, new long[3]);
			long filled = 0;
			long sides = 0;
			long volume = 0;
			BigDecimal notional = BigDecimal.ZERO;
			for (Map<String, Object> trade : trades) {
				for (String order : new String[] { "maker_order_id", "taker_order_id" }) {
					String id = trade.get(order).toString();
					if (oracle.orders.contains(id) && strategy.equals(oracle.orderStrategy.get(id))) {
						filled += (Long) trade.get("quantity");
					}
				}
				for (String side : new String[] { "buyer_account_id", "seller_account_id" }) {
					if (strategy.equals(oracle.assigned.get((UUID) trade.get(side)))) {
						sides++;
						volume += (Long) trade.get("quantity");
						notional = notional
							.add(BigDecimal.valueOf((Long) trade.get("quantity") * (Long) trade.get("price_cents")));
					}
				}
			}
			long cash = 0;
			for (Map<String, Object> trade : trades) {
				long amount = (Long) trade.get("quantity") * (Long) trade.get("price_cents");
				if (strategy.equals(oracle.assigned.get((UUID) trade.get("buyer_account_id")))) {
					cash -= amount;
				}
				if (strategy.equals(oracle.assigned.get((UUID) trade.get("seller_account_id")))) {
					cash += amount;
				}
			}
			long snapshotCash = 0;
			BigDecimal holdingsStart = BigDecimal.ZERO;
			BigDecimal holdingsEnd = BigDecimal.ZERO;
			for (Map.Entry<UUID, String> bot : oracle.assigned.entrySet()) {
				if (!bot.getValue().equals(strategy)) {
					continue;
				}
				long[] before = oracle.startBalances.get(bot.getKey());
				long[] after = oracle.endBalances.get(bot.getKey());
				snapshotCash += after[0] - before[0];
				for (int i : oracle.traded) {
					holdingsStart = holdingsStart.add(BigDecimal.valueOf(before[i + 1] * mark(i, oracle.start)));
					holdingsEnd = holdingsEnd.add(BigDecimal.valueOf(after[i + 1] * mark(i, oracle.end)));
				}
			}
			if (oracle.traded.size() == 2) {
				assertThat(snapshotCash).as("the balances moved by the trades alone").isEqualTo(cash);
			}
			expected.put(strategy, List.of(Long.toString(bots.get(strategy)), Long.toString(commands[0]),
					Long.toString(commands[1]), Long.toString(commands[2]), Long.toString(filled), Long.toString(sides),
					Long.toString(volume), notional.toString(), Long.toString(cash), holdingsStart.toString(),
					holdingsEnd.toString(), BigDecimal.valueOf(cash).add(holdingsEnd).subtract(holdingsStart).toString()));
		}
		Map<String, List<String>> stored = new TreeMap<>();
		jdbc.queryForList("""
				SELECT strategy, bots, placements, cancels, placed_quantity, filled_quantity, trades, volume,
				    notional_cents, cash_change_cents, holdings_start_cents, holdings_end_cents, pnl_cents
				FROM bot_run_strategy_figures WHERE run_id = ?""", run)
			.forEach((row) -> stored.put((String) row.get("strategy"),
					row.values().stream().skip(1).map(String::valueOf).toList()));
		assertThat(stored).isEqualTo(expected);
		Map<String, Map<String, Long>> refusals = new TreeMap<>();
		jdbc.queryForList("SELECT strategy, code, commands FROM bot_run_refusals WHERE run_id = ?", run)
			.forEach((row) -> refusals.computeIfAbsent((String) row.get("strategy"), (s) -> new TreeMap<>())
				.put((String) row.get("code"), (Long) row.get("commands")));
		assertThat(refusals).isEqualTo(oracle.refusals);
		assertThat(oracle.refusals).as("the stream refuses some").isNotEmpty();
	}

	private static String[] with(String[] auth) {
		String[] headers = new String[auth.length + 2];
		System.arraycopy(auth, 0, headers, 0, auth.length);
		headers[auth.length] = "Idempotency-Key";
		headers[auth.length + 1] = UUID.randomUUID().toString();
		return headers;
	}

	private static JsonNode post(TestBrowser browser, String path, String body) throws Exception {
		HttpResponse<String> sent = browser.post(path, body, "Idempotency-Key", UUID.randomUUID().toString());
		assertThat(sent.statusCode()).as(path + ": " + sent.body()).isBetween(200, 201);
		return JSON.readTree(sent.body());
	}

}
