package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.mia_princz.mse.contracts.events.BookLevel;
import com.mia_princz.mse.contracts.events.MarketSnapshot;
import com.mia_princz.mse.contracts.events.VenueState;
import com.mia_princz.mse.contracts.events.WireEvents;
import com.mia_princz.mse.execution.identity.ApiTokens;
import com.mia_princz.mse.execution.trading.adapter.RunFigures;
import com.mia_princz.mse.execution.trading.application.TradingEngine;
import com.mia_princz.mse.execution.trading.application.TradingStore;
import com.mia_princz.mse.execution.trading.domain.Side;

/**
 * Closing an open market, over real HTTP and PostgreSQL. The market is set up, opened and traded through the API by
 * two traders and three bots, with a bot run going and eight orders resting on two of its three instruments, one of
 * them partially filled; then the operator closes it, once, and every test reads what that left. A close here cancels
 * three orders per transaction, so this one takes three: the close itself and two more parts.
 * <p>
 * What a close does when its process stops between two parts, and a close while halted, are in
 * {@link MarketCloseSweepIT}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=market-close", "mse.market.close-chunk-orders=3",
				"mse.bots.token-directory=${java.io.tmpdir}/mse-market-close-it-tokens",
				"mse.dev-users.trader-password=" + MarketCloseIT.PASSWORD,
				"mse.dev-users.operator-password=" + MarketCloseIT.PASSWORD,
				"mse.simulator.token=" + MarketCloseIT.SIMULATOR_TOKEN,
				// Figures are computed when the test asks, not behind its back.
				"mse.runs.figures-interval=1h",
				// A few accounts send commands back to back. AccountRateLimitIT covers pacing.
				"mse.trading.human-commands-per-second=1000", "mse.trading.bot-commands-per-second=1000" })
class MarketCloseIT {

	static final String PASSWORD = "test-market-close-password";

	static final String SIMULATOR_TOKEN = "test-market-close-simulator-token";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final OpenApiContract CONTRACT = OpenApiContract.get();

	private static final String CLOSE = "/api/v1/admin/market/close";

	private static final String PLACE = "/api/v1/orders";

	/** An account's totals and what of them is held: cash, then each instrument's shares by symbol. */
	private record Balances(long cash, long cashReserved, Map<String, Long> shares, Map<String, Long> sharesReserved) {
	}

	/** Everything the one close of this class left for the tests, and what was there before it. */
	private record Story(UUID simulation, UUID operatorId, Map<String, String> instruments, String earlyClose,
			String setupClose, UUID setupCloseKey, String stateInSetup, Map<String, UUID> accounts,
			Map<String, String> botTokens, UUID firstRun, UUID run, List<UUID> resting, Map<UUID, Long> restingQuantity,
			UUID ownerCanceled, UUID filled, UUID keptKey, String keptBody, String keptAnswer,
			Map<UUID, Balances> before, long sequenceBefore, UUID closeKey, HttpResponse<String> closed,
			long sequenceAfter, List<Path> tokenFilesBefore) {
	}

	private static Story story;

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	TradingEngine engine;

	@Autowired
	TradingStore store;

	@Autowired
	RunFigures figures;

	@Value("${mse.bots.token-directory}")
	Path tokenDirectory;

	private TestBrowser operator;

	private TestBrowser trader;

	private TestBrowser second;

	@BeforeEach
	void closedMarket() throws Exception {
		operator = TestBrowser.loggedIn(port, "operator", PASSWORD);
		synchronized (MarketCloseIT.class) {
			if (story == null) {
				story = tradeAndClose();
			}
		}
		trader = TestBrowser.loggedIn(port, "trader", PASSWORD);
		second = TestBrowser.loggedIn(port, "second", PASSWORD);
	}

	@Test
	void aMarketThatNeverOpenedIsNotClosed() {
		// Before any market exists nothing is committed, so nothing is audited either.
		assertThat(story.earlyClose()).isEqualTo("MARKET_NOT_SET_UP");
		// In SETUP the refusal is committed with the state, and the market stays as it was: not opened, not closed.
		assertThat(story.setupClose()).isEqualTo("MARKET_TRANSITION_NOT_ALLOWED SETUP");
		assertThat(story.stateInSetup()).isEqualTo("SETUP opened null closed null");
		assertThat(jdbc.queryForObject("SELECT action || ' ' || outcome FROM audit_log WHERE correlation_id = ?",
				String.class, story.setupCloseKey())).isEqualTo("MARKET_CLOSE NOT_ALLOWED");
	}

	@Test
	void onlyAnOperatorClosesAndOnlyWithAKey() throws Exception {
		assertThat(trader.post(CLOSE, "", "Idempotency-Key", UUID.randomUUID().toString()).statusCode()).isEqualTo(403);
		assertThat(new TestBrowser(port).post(CLOSE, "", "Idempotency-Key", UUID.randomUUID().toString()).statusCode())
			.isIn(401, 403);
		HttpResponse<String> keyless = operator.post(CLOSE, "");
		assertThat(problem(keyless, 400)).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
		CONTRACT.assertResponse("POST", CLOSE, 400, contentType(keyless), keyless.body());
	}

	@Test
	void theCloseAnswersWithWhatItDidAndItsKeyRepeatsTheAnswer() throws Exception {
		HttpResponse<String> closed = story.closed();
		assertThat(closed.statusCode()).as(closed.body()).isEqualTo(200);
		CONTRACT.assertResponse("POST", CLOSE, 200, contentType(closed), closed.body());
		JsonNode answer = JSON.readTree(closed.body());
		long sequence = answer.get("commandSequence").asLong();
		assertThat(answer.get("state").asString()).isEqualTo("CLOSED");
		assertThat(sequence).isEqualTo(story.sequenceBefore() + 1);
		assertThat(answer.get("canceledOrders").asInt()).isEqualTo(8);
		assertThat(answer.get("endedRuns").asInt()).isEqualTo(1);

		long before = sequence();
		HttpResponse<String> again = operator.post(CLOSE, "", "Idempotency-Key", story.closeKey().toString());
		assertThat(again.statusCode()).isEqualTo(200);
		assertThat(again.body()).isEqualTo(closed.body());
		assertThat(sequence()).as("a repeated key takes no sequence").isEqualTo(before);

		JsonNode market = JSON.readTree(new TestBrowser(port).get("/api/v1/market").body());
		assertThat(market.get("state").asString()).isEqualTo("CLOSED");
		assertThat(market.get("simulationId").asString()).isEqualTo(story.simulation().toString());
		assertThat(market.get("openedAt").isNull()).isFalse();
		assertThat(market.get("closedAt").isNull()).isFalse();
		assertThat(jdbc.queryForObject("SELECT state || ' ' || (closed_at >= opened_at) FROM simulation WHERE id = ?",
				String.class, story.simulation())).isEqualTo("CLOSED true");
	}

	@Test
	void everyRestingOrderIsCanceledWithItsHoldReleasedAndNothingElseMoves() throws Exception {
		for (UUID order : story.resting()) {
			Map<String, Object> row = jdbc.queryForMap(
					"SELECT state, reason, working_quantity, canceled_quantity FROM orders WHERE id = ?", order);
			assertThat(row.get("state")).as("order " + order).isEqualTo("CANCELED");
			assertThat(row.get("reason")).isEqualTo("MARKET_CLOSED");
			assertThat(row.get("working_quantity")).isEqualTo(0L);
			assertThat(row.get("canceled_quantity")).isEqualTo(story.restingQuantity().get(order));
		}
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM orders WHERE simulation_id = ? AND state IN ('OPEN', 'PARTIALLY_FILLED')""",
				Long.class, story.simulation())).isZero();
		// Orders that had ended before the close keep how they ended.
		assertThat(jdbc.queryForObject("SELECT state || ' ' || reason FROM orders WHERE id = ?", String.class,
				story.ownerCanceled())).isEqualTo("CANCELED CANCELED_BY_OWNER");
		assertThat(jdbc.queryForObject("SELECT state FROM orders WHERE id = ?", String.class, story.filled()))
			.isEqualTo("FILLED");

		// Every hold is back, and no total moved: each account has exactly the cash and shares it had.
		Map<UUID, Balances> after = balances();
		assertThat(after.keySet()).isEqualTo(story.before().keySet());
		assertThat(story.before().values()).as("the fixture holds cash and shares")
			.anyMatch((balances) -> balances.cashReserved() > 0)
			.anyMatch((balances) -> balances.sharesReserved().values().stream().anyMatch((held) -> held > 0));
		after.forEach((account, balances) -> {
			Balances had = story.before().get(account);
			assertThat(balances.cash()).as("cash of " + account).isEqualTo(had.cash());
			assertThat(balances.shares()).as("shares of " + account).isEqualTo(had.shares());
			assertThat(balances.cashReserved()).as("cash held by " + account).isZero();
			assertThat(balances.sharesReserved().values()).as("shares held by " + account).containsOnly(0L);
		});
		// One release for each order, in the transaction that cancelled it.
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM journal_batch WHERE simulation_id = ? AND cause = 'RELEASE' AND command_sequence > ?""",
				Long.class, story.simulation(), story.sequenceBefore())).isEqualTo(8);
		assertThat(reconciliationProblems()).isEmpty();
		for (String instrument : story.instruments().values()) {
			for (Side side : Side.values()) {
				assertThat(engine.depth(UUID.fromString(instrument), side).get(5, TimeUnit.SECONDS)).isEmpty();
			}
		}
	}

	/**
	 * Three orders per transaction: the close cancels the first three, and two more parts the other five. Each part
	 * is a command of its own: the next sequence, a stored answer and an audit row under the operator who closed.
	 */
	@Test
	void theSweepRunsInPartsEachACommandOfItsOwn() {
		long close = story.sequenceBefore() + 1;
		assertThat(story.sequenceAfter()).as("the close and two more parts").isEqualTo(close + 2);
		Map<Long, Long> releases = new TreeMap<>();
		jdbc.query("""
				SELECT command_sequence, count(*) FROM journal_batch
				WHERE simulation_id = ? AND cause = 'RELEASE' AND command_sequence >= ? GROUP BY 1""",
				(row) -> {
					releases.put(row.getLong(1), row.getLong(2));
				}, story.simulation(), close);
		assertThat(releases).containsExactly(Map.entry(close, 3L), Map.entry(close + 1, 3L), Map.entry(close + 2, 2L));

		List<String> answers = jdbc.queryForList("""
				SELECT command_sequence || ' ' || route || ' ' || response_status || ' ' || (principal_id = ?)
				    || ' ' || coalesce(response_body ->> 'canceledOrders', '') || ' '
				    || coalesce(response_body ->> 'restingOrders', '')
				FROM command_result WHERE simulation_id = ? AND command_sequence BETWEEN ? AND ?
				ORDER BY command_sequence""", String.class, story.operatorId(), story.simulation(), close, close + 2);
		assertThat(answers).containsExactly(close + " POST /api/v1/admin/market/close 200 true 8 ",
				(close + 1) + " MARKET_CLOSE_SWEEP 200 true 3 2", (close + 2) + " MARKET_CLOSE_SWEEP 200 true 2 0");
		List<String> audit = jdbc.queryForList("""
				SELECT a.command_sequence || ' ' || a.action || ' ' || a.outcome || ' ' || (a.actor_user_id = ?) || ' '
				    || (a.correlation_id = c.idempotency_key)
				FROM audit_log a JOIN command_result c
				    ON c.simulation_id = a.simulation_id AND c.command_sequence = a.command_sequence
				WHERE a.simulation_id = ? AND a.command_sequence BETWEEN ? AND ? ORDER BY a.command_sequence""",
				String.class, story.operatorId(), story.simulation(), close, close + 2);
		assertThat(audit).containsExactly(close + " MARKET_CLOSE APPLIED true true",
				(close + 1) + " MARKET_CLOSE_SWEEP APPLIED true true",
				(close + 2) + " MARKET_CLOSE_SWEEP APPLIED true true");
		// As for every command before them: one stored answer for each sequence.
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM command_result WHERE simulation_id = ? AND command_sequence <= ?""", Long.class,
				story.simulation(), story.sequenceAfter())).isEqualTo(story.sequenceAfter());
	}

	/**
	 * The close's own snapshots say CLOSED for every instrument, the one nobody traded included, with the depth its
	 * first three cancels leave; a later part writes one for each instrument whose book it changed. Each instrument's
	 * newest snapshot is CLOSED with an empty book, and nothing trades after the close.
	 */
	@Test
	void everyInstrumentsLastSnapshotSaysClosed() throws Exception {
		long close = story.sequenceBefore() + 1;
		UUID nova = UUID.fromString(story.instruments().get("NOVA"));
		UUID orbt = UUID.fromString(story.instruments().get("ORBT"));
		UUID zeta = UUID.fromString(story.instruments().get("ZETA"));

		Map<UUID, MarketSnapshot> atClose = snapshots(close);
		assertThat(atClose).containsOnlyKeys(nova, orbt, zeta)
			.allSatisfy((instrument, snapshot) -> assertThat(snapshot.getVenueState()).isEqualTo(VenueState.CLOSED));
		// NOVA's two bids and its best offer went with the close; ORBT's book is still whole.
		assertThat(atClose.get(nova).getBids()).isEmpty();
		assertThat(atClose.get(nova).getAsks()).containsExactly(new BookLevel(11_000L, 10L, 1));
		assertThat(atClose.get(orbt).getBids()).containsExactly(new BookLevel(4_500L, 2L, 1),
				new BookLevel(4_200L, 3L, 1), new BookLevel(4_100L, 1L, 1));
		assertThat(atClose.get(orbt).getAsks()).containsExactly(new BookLevel(5_500L, 5L, 1));
		assertThat(atClose.get(zeta).getBids()).isEmpty();
		assertThat(atClose.get(nova).getLastTrade().getPriceCents()).as("the final mark").isEqualTo(10_000L);

		Map<UUID, MarketSnapshot> second = snapshots(close + 1);
		assertThat(second).containsOnlyKeys(nova, orbt)
			.allSatisfy((instrument, snapshot) -> assertThat(snapshot.getVenueState()).isEqualTo(VenueState.CLOSED));
		assertThat(second.get(nova).getAsks()).isEmpty();
		assertThat(second.get(orbt).getBids()).containsExactly(new BookLevel(4_100L, 1L, 1));
		Map<UUID, MarketSnapshot> third = snapshots(close + 2);
		assertThat(third).containsOnlyKeys(orbt);

		for (UUID instrument : List.of(nova, orbt, zeta)) {
			MarketSnapshot last = WireEvents.decodeSnapshot(jdbc.queryForObject("""
					SELECT payload FROM outbox WHERE topic = 'mse.market.v1' AND event_key = ?
					ORDER BY id DESC LIMIT 1""", byte[].class, story.simulation() + "|" + instrument));
			assertThat(last.getVenueState()).as("the last snapshot of " + last.getSymbol()).isEqualTo(VenueState.CLOSED);
			assertThat(last.getBids()).isEmpty();
			assertThat(last.getAsks()).isEmpty();
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM trades WHERE simulation_id = ? AND command_sequence >= ?",
				Long.class, story.simulation(), close)).isZero();
	}

	/**
	 * The run that was going ends at the close: stopped and ended by it at its sequence, with the time the market
	 * closed, and its figures are computed like any finished run's. A run that had ended before keeps its end. Neither
	 * is work any more: no worker is handed one, and a heartbeat is answered as for a finished run.
	 */
	@Test
	void runsEndWithTheCloseAndTheirFiguresAreComputed() throws Exception {
		long close = story.sequenceBefore() + 1;
		Timestamp closedAt = jdbc.queryForObject("SELECT closed_at FROM simulation WHERE id = ?", Timestamp.class,
				story.simulation());
		Map<String, Object> run = jdbc.queryForMap("SELECT * FROM bot_run WHERE id = ?", story.run());
		assertThat(run.get("desired_state")).isEqualTo("STOPPED");
		assertThat(run.get("ended_by")).isEqualTo("MARKET_CLOSE");
		assertThat(run.get("stop_sequence")).isEqualTo(close);
		assertThat(run.get("end_sequence")).isEqualTo(close);
		assertThat(run.get("stop_requested_at")).isEqualTo(closedAt);
		assertThat(run.get("ended_at")).isEqualTo(closedAt);
		assertThat(run.get("stopped_at")).isEqualTo(closedAt);
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM bot_run_instrument r JOIN instrument i ON i.id = r.instrument_id
				WHERE r.run_id = ? AND r.end_trade_sequence = i.last_trade_sequence""", Long.class, story.run()))
			.as("each instrument's newest trade at the close").isEqualTo(3);
		// The run the next start had ended keeps that end; the close only records that it will never be confirmed.
		Map<String, Object> first = jdbc.queryForMap("SELECT * FROM bot_run WHERE id = ?", story.firstRun());
		assertThat(first.get("ended_by")).isEqualTo("NEXT_START");
		assertThat((Long) first.get("end_sequence")).isLessThan(close);
		assertThat(first.get("stopped_at")).isEqualTo(closedAt);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM bot_run WHERE simulation_id = ? AND desired_state = 'RUNNING'",
				Long.class, story.simulation())).isZero();

		figures.computeFinished();
		JsonNode detail = JSON.readTree(operator.get("/api/v1/admin/bot-runs/" + story.run()).body());
		assertThat(detail.get("run").get("status").asString()).isEqualTo("STOPPED");
		assertThat(detail.get("run").get("window").get("endedBy").asString()).isEqualTo("MARKET_CLOSE");
		assertThat(detail.get("run").get("window").get("endSequence").asString()).isEqualTo(Long.toString(close));
		assertThat(detail.get("figures").isNull()).as("figures of a run the close ended").isFalse();
		Map<String, Long> placements = new HashMap<>();
		detail.get("figures").get("strategies").forEach((strategy) -> placements.put(strategy.get("strategy").asString(),
				strategy.get("placements").asLong()));
		assertThat(placements).containsEntry("MARKET_MAKER", 2L).containsEntry("NOISE", 1L);
		HttpResponse<String> read = operator.get("/api/v1/admin/bot-runs/" + story.run());
		CONTRACT.assertResponse("GET", "/api/v1/admin/bot-runs/{runId}", 200, contentType(read), read.body());

		TestBrowser simulator = new TestBrowser(port);
		assertThat(JSON.readTree(simulator.get("/internal/simulator/assignment", bearer(SIMULATOR_TOKEN)).body())
			.get("run")
			.isNull()).as("nothing to work on").isTrue();
		for (UUID ended : List.of(story.run(), story.firstRun())) {
			assertThat(problem(heartbeat(simulator, ended, "worker-a"), 409)).isEqualTo("RUN_FINISHED");
		}
		assertThat(problem(operator.post("/api/v1/admin/bot-runs/" + story.run() + "/stop", "", "Idempotency-Key",
				UUID.randomUUID().toString()), 409)).isEqualTo("RUN_NOT_RUNNING");
	}

	/** Every token of the market's bots is revoked with the close, the unassigned bot's too, and their files are gone. */
	@Test
	void botTokensAreRevokedAndTheirFilesDeleted() throws Exception {
		assertThat(story.botTokens()).hasSize(3);
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM api_token t JOIN account a ON a.id = t.account_id JOIN simulation s ON s.id = a.simulation_id
				WHERE a.simulation_id = ? AND t.revoked_at = s.closed_at""", Long.class, story.simulation())).isEqualTo(3);
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM api_token t JOIN account a ON a.id = t.account_id
				WHERE a.simulation_id = ? AND t.revoked_at IS NULL""", Long.class, story.simulation())).isZero();
		TestBrowser bot = new TestBrowser(port);
		for (String token : story.botTokens().values()) {
			assertThat(bot.get("/api/v1/portfolio", bearer(token)).statusCode()).isEqualTo(401);
			assertThat(bot.post(PLACE, limit(story.instruments().get("NOVA"), "BUY", 1, "90.00"), "Authorization",
					"Bearer " + token, "Idempotency-Key", UUID.randomUUID().toString()).statusCode()).isEqualTo(401);
		}
		assertThat(story.tokenFilesBefore()).hasSize(3);
		assertThat(story.tokenFilesBefore()).noneMatch(Files::exists);
		assertThat(engine.simulationIdUnlessClosed()).isNull();

		// Revoking is what ends the tokens, but not all that does: one made after the close, which nothing revoked,
		// is refused as well, because its market is closed.
		ApiTokens.Issued late = ApiTokens.issue();
		jdbc.update("""
				INSERT INTO api_token (id, account_id, account_kind, token_hash, scope)
				SELECT ?, id, 'BOT', ?, 'TRADE' FROM account WHERE kind = 'BOT' ORDER BY id LIMIT 1""", UUID.randomUUID(),
				late.hash());
		try {
			assertThat(bot.get("/api/v1/portfolio", bearer(late.token())).statusCode()).isEqualTo(401);
		}
		finally {
			jdbc.update("UPDATE api_token SET revoked_at = now() WHERE token_hash = ?", late.hash());
		}
	}

	/**
	 * After the close an order is rejected with MARKET_CLOSED, committed like any rejection, so its key repeats it; a
	 * run does not start; no transition leads anywhere, a second close included; and setup commands are refused as
	 * for any market past SETUP, without a second market appearing.
	 */
	@Test
	void aClosedMarketRefusesOrdersRunsTransitionsAndSetup() throws Exception {
		UUID key = UUID.randomUUID();
		String order = limit(story.instruments().get("NOVA"), "BUY", 1, "90.00");
		HttpResponse<String> rejected = trader.post(PLACE, order, "Idempotency-Key", key.toString());
		assertThat(problem(rejected, 422)).isEqualTo("MARKET_CLOSED");
		CONTRACT.assertResponse("POST", PLACE, 422, contentType(rejected), rejected.body());
		JsonNode rejection = JSON.readTree(rejected.body());
		assertThat(jdbc.queryForObject("SELECT state || ' ' || reason FROM orders WHERE id = ?::uuid", String.class,
				rejection.get("orderId").asString())).isEqualTo("REJECTED MARKET_CLOSED");
		HttpResponse<String> again = trader.post(PLACE, order, "Idempotency-Key", key.toString());
		assertThat(again.statusCode()).isEqualTo(422);
		assertThat(JSON.readTree(again.body()).get("commandSequence")).isEqualTo(rejection.get("commandSequence"));
		assertThat(JSON.readTree(again.body()).get("orderId")).isEqualTo(rejection.get("orderId"));

		UUID runKey = UUID.randomUUID();
		String runBody = "{\"marketMakers\":1,\"commandsPerSecond\":\"1.00\"}";
		HttpResponse<String> run = operator.post("/api/v1/admin/bot-runs", runBody, "Idempotency-Key", runKey.toString());
		assertThat(problem(run, 409)).isEqualTo("MARKET_CLOSED");
		CONTRACT.assertResponse("POST", "/api/v1/admin/bot-runs", 409, contentType(run), run.body());
		assertThat(operator.post("/api/v1/admin/bot-runs", runBody, "Idempotency-Key", runKey.toString()).body())
			.isEqualTo(run.body());
		assertThat(jdbc.queryForObject("SELECT action || ' ' || outcome FROM audit_log WHERE correlation_id = ?",
				String.class, runKey)).isEqualTo("BOT_RUN_START NOT_ALLOWED");

		for (String action : new String[] { "open", "halt", "resume", "close" }) {
			UUID transition = UUID.randomUUID();
			HttpResponse<String> refused = operator.post("/api/v1/admin/market/" + action, "", "Idempotency-Key",
					transition.toString());
			assertThat(problem(refused, 409)).as(action).isEqualTo("MARKET_TRANSITION_NOT_ALLOWED");
			assertThat(JSON.readTree(refused.body()).get("state").asString()).isEqualTo("CLOSED");
			CONTRACT.assertResponse("POST", "/api/v1/admin/market/" + action, 409, contentType(refused), refused.body());
			assertThat(jdbc.queryForObject("SELECT action || ' ' || outcome FROM audit_log WHERE correlation_id = ?",
					String.class, transition)).isEqualTo("MARKET_" + action.toUpperCase() + " NOT_ALLOWED");
		}

		assertThat(problem(operator.post("/api/v1/admin/instruments", "{\"symbol\":\"LATE\",\"referencePrice\":\"1.00\"}",
				"Idempotency-Key", UUID.randomUUID().toString()), 409)).isEqualTo("MARKET_NOT_IN_SETUP");
		assertThat(problem(operator.post("/api/v1/admin/traders", """
				{"username":"late.comer","password":"%s","cash":"1.00","holdings":[]}""".formatted(PASSWORD),
				"Idempotency-Key", UUID.randomUUID().toString()), 409)).isEqualTo("MARKET_NOT_IN_SETUP");
		assertThat(problem(operator.post("/api/v1/admin/bots", """
				{"count":1,"cash":"1.00","holdings":[],"strategy":"NOISE"}""", "Idempotency-Key",
				UUID.randomUUID().toString()), 409)).isEqualTo("MARKET_NOT_IN_SETUP");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM simulation", Long.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT state FROM simulation", String.class)).isEqualTo("CLOSED");
	}

	/**
	 * A key answered before the close still returns that answer, though its order has since been cancelled; and a
	 * cancel of an order the close cancelled is answered like any cancel of an order already cancelled.
	 */
	@Test
	void keysUsedBeforeTheCloseRepeatTheirAnswersAndCancelsFindTheOrdersCanceled() throws Exception {
		HttpResponse<String> repeated = trader.post(PLACE, story.keptBody(), "Idempotency-Key", story.keptKey().toString());
		assertThat(repeated.statusCode()).as(repeated.body()).isEqualTo(201);
		assertThat(repeated.body()).isEqualTo(story.keptAnswer());
		assertThat(JSON.readTree(repeated.body()).get("state").asString()).isEqualTo("OPEN");
		HttpResponse<String> stored = trader.get("/api/v1/commands/" + story.keptKey());
		assertThat(stored.statusCode()).isEqualTo(201);
		assertThat(stored.body()).isEqualTo(story.keptAnswer());

		String swept = JSON.readTree(story.keptAnswer()).get("orderId").asString();
		assertThat(story.resting()).contains(UUID.fromString(swept));
		for (String order : List.of(swept, story.ownerCanceled().toString())) {
			HttpResponse<String> canceled = cancel(trader, order);
			assertThat(canceled.statusCode()).as(canceled.body()).isEqualTo(200);
			assertThat(JSON.readTree(canceled.body()).get("status").asString()).isEqualTo("ALREADY_CANCELED");
			assertThat(JSON.readTree(canceled.body()).get("canceledQuantity").asLong()).isZero();
		}
		assertThat(problem(cancel(trader, story.filled().toString()), 409)).isEqualTo("ORDER_NOT_CANCELABLE");
	}

	/** Nothing is deleted and nothing is hidden: the closed market's people read what it left. */
	@Test
	void readsStillAnswerForTheClosedMarket() throws Exception {
		JsonNode portfolio = JSON.readTree(trader.get("/api/v1/portfolio").body());
		Balances had = story.before().get(story.accounts().get("trader"));
		assertThat(portfolio.get("cash").get("total").asString()).isEqualTo(decimal(had.cash()));
		assertThat(portfolio.get("cash").get("available").asString()).isEqualTo(decimal(had.cash()));
		assertThat(portfolio.get("cash").get("reserved").asString()).isEqualTo("0.00");

		assertThat(JSON.readTree(trader.get("/api/v1/orders?status=active").body()).get("orders")).isEmpty();
		Map<String, String> reasons = new HashMap<>();
		JSON.readTree(trader.get("/api/v1/orders?limit=200").body())
			.get("orders")
			.forEach((order) -> reasons.put(order.get("orderId").asString(),
					order.get("state").asString() + " " + order.get("reason").asString()));
		assertThat(reasons).containsEntry(JSON.readTree(story.keptAnswer()).get("orderId").asString(),
				"CANCELED MARKET_CLOSED");
		HttpResponse<String> orders = trader.get("/api/v1/orders?limit=200");
		CONTRACT.assertResponse("GET", "/api/v1/orders", 200, contentType(orders), orders.body());
		assertThat(JSON.readTree(trader.get("/api/v1/portfolio/trades").body()).get("trades")).hasSize(1);

		assertThat(ids(operator.get("/api/v1/admin/accounts?limit=200"), "accounts", "accountId"))
			.containsExactlyInAnyOrderElementsOf(story.accounts().values().stream().map(UUID::toString).toList());
		assertThat(ids(operator.get("/api/v1/admin/bot-runs"), "runs", "runId"))
			.containsExactly(story.run().toString(), story.firstRun().toString());
		assertThat(JSON.readTree(operator.get("/api/v1/admin/bots").body()).get("population").asInt()).isEqualTo(3);
		HttpResponse<String> market = new TestBrowser(port).get("/api/v1/market");
		CONTRACT.assertResponse("GET", "/api/v1/market", 200, contentType(market), market.body());
	}

	/**
	 * A restart finds only a closed simulation. It loads it as the market, closed: it takes its epoch, reconciles it
	 * and goes on refusing, so a setup command can't start a second market behind it.
	 */
	@Test
	void aRestartLoadsTheClosedMarketAndStillRefusesSetup() throws Exception {
		long epoch = jdbc.queryForObject("SELECT writer_epoch FROM simulation", Long.class);
		long sequence = sequence();

		engine.start();

		assertThat(jdbc.queryForObject("SELECT writer_epoch FROM simulation", Long.class)).isEqualTo(epoch + 1);
		assertThat(sequence()).as("nothing was left to sweep").isEqualTo(sequence);
		assertThat(engine.simulationId()).isEqualTo(story.simulation());
		assertThat(engine.simulationIdUnlessClosed()).isNull();
		assertThat(problem(operator.post("/api/v1/admin/instruments", "{\"symbol\":\"AGAIN\",\"referencePrice\":\"1.00\"}",
				"Idempotency-Key", UUID.randomUUID().toString()), 409)).isEqualTo("MARKET_NOT_IN_SETUP");
		assertThat(problem(trader.post(PLACE, limit(story.instruments().get("ORBT"), "SELL", 1, "60.00"),
				"Idempotency-Key", UUID.randomUUID().toString()), 422)).isEqualTo("MARKET_CLOSED");
		assertThat(problem(operator.post(CLOSE, "", "Idempotency-Key", UUID.randomUUID().toString()), 409))
			.isEqualTo("MARKET_TRANSITION_NOT_ALLOWED");
		assertThat(operator.post(CLOSE, "", "Idempotency-Key", story.closeKey().toString()).body())
			.isEqualTo(story.closed().body());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM simulation", Long.class)).isEqualTo(1);
		assertThat(JSON.readTree(new TestBrowser(port).get("/api/v1/market").body()).get("state").asString())
			.isEqualTo("CLOSED");
		assertThat(reconciliationProblems()).isEmpty();
	}

	/** Sets the market up, opens it, trades in it and closes it, through the API. */
	private Story tradeAndClose() throws Exception {
		UUID operatorId = jdbc.queryForObject("SELECT id FROM app_user WHERE username = 'operator'", UUID.class);
		String earlyClose = problem(operator.post(CLOSE, "", "Idempotency-Key", UUID.randomUUID().toString()), 409);

		Map<String, String> instruments = new LinkedHashMap<>();
		for (String[] listed : new String[][] { { "NOVA", "100.00" }, { "ORBT", "50.00" }, { "ZETA", "20.00" } }) {
			instruments.put(listed[0], created(operator, "/api/v1/admin/instruments",
					"{\"symbol\":\"%s\",\"referencePrice\":\"%s\"}".formatted(listed[0], listed[1])).get("instrumentId")
				.asString());
		}
		String nova = instruments.get("NOVA");
		String orbt = instruments.get("ORBT");
		String holdings = "[{\"instrumentId\":\"%s\",\"quantity\":500},{\"instrumentId\":\"%s\",\"quantity\":500}]"
			.formatted(nova, orbt);
		created(operator, "/api/v1/admin/accounts",
				"{\"username\":\"trader\",\"cash\":\"100000.00\",\"holdings\":%s}".formatted(holdings));
		created(operator, "/api/v1/admin/traders",
				"{\"username\":\"second\",\"password\":\"%s\",\"cash\":\"100000.00\",\"holdings\":%s}".formatted(PASSWORD,
						holdings));
		for (String[] tagged : new String[][] { { "MARKET_MAKER", "2" }, { "NOISE", "1" } }) {
			created(operator, "/api/v1/admin/bots",
					"{\"count\":%s,\"cash\":\"10000.00\",\"holdings\":%s,\"strategy\":\"%s\"}".formatted(tagged[1], holdings,
							tagged[0]));
		}
		UUID simulation = jdbc.queryForObject("SELECT id FROM simulation", UUID.class);
		Map<String, UUID> accounts = new LinkedHashMap<>();
		jdbc.query("SELECT display_name, id FROM account WHERE kind <> 'ISSUANCE' ORDER BY created_at, id", (row) -> {
			accounts.put(row.getString(1), row.getObject(2, UUID.class));
		});

		UUID setupCloseKey = UUID.randomUUID();
		HttpResponse<String> inSetup = operator.post(CLOSE, "", "Idempotency-Key", setupCloseKey.toString());
		String setupClose = problem(inSetup, 409) + " " + JSON.readTree(inSetup.body()).get("state").asString();
		String stateInSetup = jdbc.queryForObject("""
				SELECT state || ' opened ' || coalesce(opened_at::text, 'null') || ' closed '
				    || coalesce(closed_at::text, 'null') FROM simulation""", String.class);

		ok(operator.post("/api/v1/admin/market/open", "", "Idempotency-Key", UUID.randomUUID().toString()));
		// A first run that never had a worker: starting the second ends it. The second is going when the market closes.
		UUID firstRun = UUID.fromString(created(operator, "/api/v1/admin/bot-runs",
				"{\"noise\":1,\"commandsPerSecond\":\"1.00\"}").get("runId").asString());
		ok(operator.post("/api/v1/admin/bot-runs/" + firstRun + "/stop", "", "Idempotency-Key",
				UUID.randomUUID().toString()));
		UUID run = UUID.fromString(created(operator, "/api/v1/admin/bot-runs",
				"{\"marketMakers\":1,\"noise\":1,\"commandsPerSecond\":\"1.00\"}").get("runId").asString());
		ok(heartbeat(new TestBrowser(port), run, "worker-a"));

		// The run's two bots trade with the tokens the exchange wrote for them; the third bot's token is never used.
		Map<String, String> botTokens = new LinkedHashMap<>();
		List<Path> tokenFiles = new ArrayList<>();
		for (UUID bot : jdbc.queryForList("SELECT id FROM account WHERE kind = 'BOT' ORDER BY created_at, id", UUID.class)) {
			Path file = tokenDirectory.resolve(bot + ".token");
			tokenFiles.add(file);
			botTokens.put(jdbc.queryForObject("""
					SELECT coalesce((SELECT strategy FROM bot_assignment WHERE run_id = ? AND account_id = ?), 'UNASSIGNED')""",
					String.class, run, bot), Files.readString(file).strip());
		}
		TestBrowser maker = new TestBrowser(port);
		TestBrowser noise = new TestBrowser(port);
		TestBrowser tradingTrader = TestBrowser.loggedIn(port, "trader", PASSWORD);
		TestBrowser tradingSecond = TestBrowser.loggedIn(port, "second", PASSWORD);
		String[] makerToken = bearer(botTokens.get("MARKET_MAKER"));
		String[] noiseToken = bearer(botTokens.get("NOISE"));

		List<UUID> resting = new ArrayList<>();
		// NOVA: second offers 6 and the trader takes 2 of them, so 4 rest on a partially filled order.
		UUID partial = placed(tradingSecond, limit(nova, "SELL", 6, "100.00"));
		UUID filled = placed(tradingTrader, limit(nova, "BUY", 2, "100.00"));
		UUID keptKey = UUID.randomUUID();
		String keptBody = limit(nova, "BUY", 5, "90.00");
		HttpResponse<String> kept = tradingTrader.post(PLACE, keptBody, "Idempotency-Key", keptKey.toString());
		assertThat(kept.statusCode()).as(kept.body()).isEqualTo(201);
		resting.add(UUID.fromString(JSON.readTree(kept.body()).get("orderId").asString()));
		resting.add(partial);
		resting.add(placed(tradingTrader, limit(nova, "SELL", 10, "110.00")));
		resting.add(placed(maker, limit(nova, "BUY", 3, "95.00"), makerToken));
		// ORBT: four more, and one its owner cancels before the close.
		resting.add(placed(maker, limit(orbt, "SELL", 5, "55.00"), makerToken));
		resting.add(placed(noise, limit(orbt, "BUY", 2, "45.00"), noiseToken));
		resting.add(placed(tradingSecond, limit(orbt, "BUY", 3, "42.00")));
		resting.add(placed(tradingTrader, limit(orbt, "BUY", 1, "41.00")));
		UUID ownerCanceled = placed(tradingTrader, limit(orbt, "BUY", 4, "40.00"));
		ok(cancel(tradingTrader, ownerCanceled.toString()));

		Map<UUID, Long> restingQuantity = new HashMap<>();
		for (UUID order : resting) {
			restingQuantity.put(order,
					jdbc.queryForObject("SELECT working_quantity FROM orders WHERE id = ?", Long.class, order));
		}
		assertThat(jdbc.queryForObject("SELECT state FROM orders WHERE id = ?", String.class, partial))
			.isEqualTo("PARTIALLY_FILLED");
		assertThat(tokenFiles).allMatch(Files::exists);
		Map<UUID, Balances> before = balances();
		long sequenceBefore = sequence();

		UUID closeKey = UUID.randomUUID();
		HttpResponse<String> closed = operator.post(CLOSE, "", "Idempotency-Key", closeKey.toString());
		return new Story(simulation, operatorId, instruments, earlyClose, setupClose, setupCloseKey, stateInSetup,
				accounts, botTokens, firstRun, run, resting, restingQuantity, ownerCanceled, filled, keptKey, keptBody,
				kept.body(), before, sequenceBefore, closeKey, closed, sequence(), tokenFiles);
	}

	/** Every trading account's totals and holds, by account. */
	private Map<UUID, Balances> balances() {
		Map<UUID, Balances> balances = new HashMap<>();
		jdbc.query("""
				SELECT b.account_id, b.total_cents, b.reserved_cents FROM cash_balance b
				JOIN account a ON a.id = b.account_id WHERE a.kind <> 'ISSUANCE'""", (row) -> {
			balances.put(row.getObject(1, UUID.class),
					new Balances(row.getLong(2), row.getLong(3), new TreeMap<>(), new TreeMap<>()));
		});
		jdbc.query("""
				SELECT p.account_id, i.symbol, p.total_quantity, p.reserved_quantity FROM position p
				JOIN instrument i ON i.id = p.instrument_id""", (row) -> {
			Balances account = balances.get(row.getObject(1, UUID.class));
			account.shares().put(row.getString(2), row.getLong(3));
			account.sharesReserved().put(row.getString(2), row.getLong(4));
		});
		return balances;
	}

	private List<String> reconciliationProblems() {
		return new TransactionTemplate(transactionManager)
			.execute((status) -> store.reconciliationProblems(story.simulation()));
	}

	private long sequence() {
		return jdbc.queryForObject("SELECT last_command_sequence FROM simulation", Long.class);
	}

	private Map<UUID, MarketSnapshot> snapshots(long commandSequence) throws Exception {
		Map<UUID, MarketSnapshot> byInstrument = new HashMap<>();
		for (byte[] payload : jdbc.queryForList(
				"SELECT payload FROM outbox WHERE topic = 'mse.market.v1' AND command_sequence = ?", byte[].class,
				commandSequence)) {
			MarketSnapshot snapshot = WireEvents.decodeSnapshot(payload);
			assertThat(byInstrument.put(snapshot.getInstrumentId(), snapshot)).as("one snapshot for each instrument")
				.isNull();
		}
		return byInstrument;
	}

	private HttpResponse<String> heartbeat(TestBrowser simulator, UUID run, String worker) throws Exception {
		return simulator.post("/internal/simulator/heartbeat", """
				{"runId":"%s","workerId":"%s","placed":0,"canceled":0,"rejected":0,"errors":0,"inFlight":0,
				"stopFailures":0,"stopped":false}""".formatted(run, worker), bearer(SIMULATOR_TOKEN));
	}

	private static JsonNode created(TestBrowser browser, String path, String body) throws Exception {
		HttpResponse<String> response = browser.post(path, body, "Idempotency-Key", UUID.randomUUID().toString());
		assertThat(response.statusCode()).as(path + ": " + response.body()).isEqualTo(201);
		return JSON.readTree(response.body());
	}

	private static UUID placed(TestBrowser browser, String body, String... headers) throws Exception {
		String[] all = new String[headers.length + 2];
		System.arraycopy(headers, 0, all, 0, headers.length);
		all[headers.length] = "Idempotency-Key";
		all[headers.length + 1] = UUID.randomUUID().toString();
		HttpResponse<String> response = browser.post(PLACE, body, all);
		assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
		return UUID.fromString(JSON.readTree(response.body()).get("orderId").asString());
	}

	private static HttpResponse<String> cancel(TestBrowser browser, String orderId) throws Exception {
		return browser.post("/api/v1/orders/" + orderId + "/cancel", "", "Idempotency-Key",
				UUID.randomUUID().toString());
	}

	private static void ok(HttpResponse<String> response) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
	}

	private static String problem(HttpResponse<String> response, int status) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
		return JSON.readTree(response.body()).get("code").asString();
	}

	private static List<String> ids(HttpResponse<String> response, String list, String field) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		List<String> ids = new ArrayList<>();
		JSON.readTree(response.body()).get(list).forEach((row) -> ids.add(row.get(field).asString()));
		return ids;
	}

	private static String contentType(HttpResponse<String> response) {
		return response.headers().firstValue("Content-Type").orElse(null);
	}

	private static String[] bearer(String token) {
		return new String[] { "Authorization", "Bearer " + token };
	}

	private static String decimal(long cents) {
		return java.math.BigDecimal.valueOf(cents, 2).toPlainString();
	}

	private static String limit(String instrument, String side, long quantity, String price) {
		return """
				{"instrumentId":"%s","side":"%s","type":"LIMIT","timeInForce":"GTC","quantity":%d,"limitPrice":"%s"}"""
			.formatted(instrument, side, quantity, price);
	}

}
