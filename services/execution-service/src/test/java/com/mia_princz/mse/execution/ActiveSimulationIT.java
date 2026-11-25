package com.mia_princz.mse.execution;

import static com.mia_princz.mse.execution.SchemaFixtures.UNIQUE_VIOLATION;
import static com.mia_princz.mse.execution.SchemaFixtures.assertFails;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.mia_princz.mse.execution.identity.ApiTokens;
import com.mia_princz.mse.execution.trading.application.CancelOrder;
import com.mia_princz.mse.execution.trading.application.CancelResult;
import com.mia_princz.mse.execution.trading.application.CommandKey;
import com.mia_princz.mse.execution.trading.application.PlaceOrder;
import com.mia_princz.mse.execution.trading.application.TradingEngine;
import com.mia_princz.mse.execution.trading.application.TradingStore;
import com.mia_princz.mse.execution.trading.domain.DepthLevel;
import com.mia_princz.mse.execution.trading.domain.OrderType;
import com.mia_princz.mse.execution.trading.domain.Price;
import com.mia_princz.mse.execution.trading.domain.Quantity;
import com.mia_princz.mse.execution.trading.domain.Side;

/**
 * A closed simulation beside the active one, over real HTTP and PostgreSQL. The closed one is inserted as the database
 * owner before any market is set up, with everything that could be mistaken for the active one's: an account for the
 * same trader, bots with working tokens, instruments with the same symbols, an order left OPEN, a trade, a stored
 * answer under the same idempotency key, and runs no worker ever finished. It does not even reconcile: its open order
 * holds nothing. The active simulation is then set up, opened and traded through the API, and every read and command
 * must mean it alone.
 * <p>
 * The writer of this test started on an empty database and is never told of the closed rows, which is the only way to
 * set a market up beside them until a new simulation can be started after a close. What a writer does when it starts
 * on a closed market is covered where a market is closed for real.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=active-simulation",
				"mse.dev-users.trader-password=" + ActiveSimulationIT.PASSWORD,
				"mse.dev-users.operator-password=" + ActiveSimulationIT.PASSWORD,
				"mse.simulator.token=" + ActiveSimulationIT.SIMULATOR_TOKEN,
				// A read that waits for a lock fails after five seconds; see the test that locks the simulation table.
				"spring.datasource.hikari.connection-init-sql=SET lock_timeout = '5s'",
				// One trader and one bot send commands back to back. AccountRateLimitIT covers pacing.
				"mse.trading.human-commands-per-second=1000", "mse.trading.bot-commands-per-second=1000" })
class ActiveSimulationIT {

	static final String PASSWORD = "test-active-simulation-password";

	static final String SIMULATOR_TOKEN = "test-simulator-token";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final ApiTokens.Issued ACTIVE_TOKEN = ApiTokens.issue();

	private static final ApiTokens.Issued CLOSED_TOKEN = ApiTokens.issue();

	/** The trader's key for an order in the closed simulation, used again in the active one. */
	private static final UUID SHARED_KEY = UUID.randomUUID();

	/** The closed simulation's rows the tests aim at. */
	private record Closed(UUID simulation, UUID trader, UUID bot, UUID openOrder, UUID stuckRun, UUID runningRun) {
	}

	private record Active(UUID simulation, String nova, String orbt, UUID run) {
	}

	/** What the API said while the closed simulations were the only ones. */
	private record Before(JsonNode status, JsonNode portfolio, List<String> accounts, List<String> runs,
			boolean assigned, int closedToken) {
	}

	private static Closed closed;

	private static Active active;

	private static Before before;

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PostgreSQLContainer postgres;

	@Autowired
	TradingEngine engine;

	@Autowired
	TradingStore store;

	private JdbcTemplate owner;

	private TestBrowser operator;

	private TestBrowser trader;

	@BeforeEach
	void simulations() throws Exception {
		owner = new JdbcTemplate(new DriverManagerDataSource(TestcontainersConfiguration.jdbcUrl(postgres, "execution_db"),
				"execution_owner", TestcontainersConfiguration.EXECUTION_OWNER_PASSWORD));
		operator = TestBrowser.loggedIn(port, "operator", PASSWORD);
		trader = TestBrowser.loggedIn(port, "trader", PASSWORD);
		synchronized (ActiveSimulationIT.class) {
			if (active == null) {
				// Closed long before the fixture's, and stored first: of two closed simulations the newer is the current one.
				olderClosedSimulation();
				closed = closedSimulation();
				before = new Before(JSON.readTree(operator.get("/api/v1/market").body()),
						JSON.readTree(trader.get("/api/v1/portfolio").body()),
						ids(operator.get("/api/v1/admin/accounts?limit=200"), "accounts", "accountId"),
						ids(operator.get("/api/v1/admin/bot-runs"), "runs", "runId"), !assignment().get("run").isNull(),
						new TestBrowser(port).get("/api/v1/portfolio", bearer(CLOSED_TOKEN.token())).statusCode());
				active = activeSimulation();
			}
		}
	}

	/**
	 * With every simulation closed, the one closed last is still the current one: the status names it, and its
	 * traders and its operator read what it left, not what the older one beside it did. It is history, though, not
	 * work: none of its runs is handed to a worker, and none of its tokens authenticates.
	 */
	@Test
	void withEverySimulationClosedTheNewestIsReadAndNothingOfItIsWork() {
		assertThat(before.status().get("state").asString()).isEqualTo("CLOSED");
		assertThat(before.status().get("simulationId").asString()).isEqualTo(closed.simulation().toString());
		// The trader has an account in both closed simulations: 5.00 in the newer, 0.01 in the older.
		assertThat(before.portfolio().get("cash").get("total").asString()).isEqualTo("5.00");
		assertThat(before.accounts()).containsExactlyInAnyOrderElementsOf(jdbc.queryForList(
				"SELECT id::text FROM account WHERE simulation_id = ? AND kind <> 'ISSUANCE'", String.class,
				closed.simulation()))
			.hasSize(5);
		assertThat(before.runs()).containsExactlyInAnyOrder(closed.stuckRun().toString(), closed.runningRun().toString());
		// One of its runs is still marked running and another was never confirmed stopped: a real close ends both.
		assertThat(before.assigned()).isFalse();
		// Never revoked, as a real close would have: the token check asks the database, which has none not closed.
		assertThat(before.closedToken()).isEqualTo(401);
		assertThat(store.hasSimulation()).isTrue();
	}

	@Test
	void theStatusAndTheOperatorsListsAreTheActiveSimulations() throws Exception {
		// The status is the active simulation's, not the newest one's: a closed one recorded later doesn't take it.
		owner.update("INSERT INTO simulation (id, state, opened_at, closed_at) VALUES (?, 'CLOSED', now(), now())",
				UUID.randomUUID());
		JsonNode status = JSON.readTree(operator.get("/api/v1/market").body());
		assertThat(status.get("state").asString()).isEqualTo("OPEN");
		assertThat(status.get("simulationId").asString()).isEqualTo(active.simulation().toString());

		List<String> accounts = new ArrayList<>();
		JSON.readTree(operator.get("/api/v1/admin/accounts?limit=200").body())
			.get("accounts")
			.forEach((account) -> accounts.add(account.get("accountId").asString()));
		assertThat(accounts).containsExactlyInAnyOrderElementsOf(jdbc.queryForList(
				"SELECT id::text FROM account WHERE simulation_id = ? AND kind <> 'ISSUANCE'", String.class,
				active.simulation()))
			.hasSize(3);

		// "former" has an account in the closed simulation only, so here it is a login still to be funded.
		Map<String, Boolean> funded = new HashMap<>();
		JSON.readTree(operator.get("/api/v1/admin/traders").body())
			.get("traders")
			.forEach((login) -> funded.put(login.get("username").asString(), login.get("funded").asBoolean()));
		assertThat(funded).containsEntry("trader", true).containsEntry("former", false);
		assertThat(operator.get("/api/v1/admin/traders?funded=false").body()).contains("former").doesNotContain("\"trader\"");

		JsonNode bots = JSON.readTree(operator.get("/api/v1/admin/bots").body());
		assertThat(bots.get("population").asInt()).isEqualTo(2);
		assertThat(bots.get("byStrategy").get("marketMakers").asInt()).isEqualTo(1);
		assertThat(bots.get("byStrategy").get("noise").asInt()).isEqualTo(1);

		List<String> runs = new ArrayList<>();
		JSON.readTree(operator.get("/api/v1/admin/bot-runs").body())
			.get("runs")
			.forEach((run) -> runs.add(run.get("runId").asString()));
		assertThat(runs).containsExactly(active.run().toString());
		assertThat(operator.get("/api/v1/admin/bot-runs/" + active.run()).statusCode()).isEqualTo(200);
		assertThat(problem(operator.get("/api/v1/admin/bot-runs/" + closed.runningRun()), 404)).isEqualTo("RUN_NOT_FOUND");
	}

	@Test
	void aTraderWithAnAccountInBothTradesInTheActiveOne() throws Exception {
		JsonNode portfolio = JSON.readTree(trader.get("/api/v1/portfolio").body());
		assertThat(portfolio.get("cash").get("total").asString()).isEqualTo("100000.00");

		// The key already has an answer in the closed simulation; here it is a new command with its own answer.
		HttpResponse<String> placed = trader.post("/api/v1/orders", order(active.nova(), "BUY", 2, "90.00"),
				"Idempotency-Key", SHARED_KEY.toString());
		assertThat(placed.statusCode()).as(placed.body()).isEqualTo(201);
		String orderId = JSON.readTree(placed.body()).get("orderId").asString();
		assertThat(jdbc.queryForObject("SELECT simulation_id FROM orders WHERE id = ?::uuid", UUID.class, orderId))
			.isEqualTo(active.simulation());
		HttpResponse<String> stored = trader.get("/api/v1/commands/" + SHARED_KEY);
		assertThat(stored.statusCode()).as(stored.body()).isEqualTo(201);
		assertThat(stored.body()).isEqualTo(placed.body());

		assertThat(trader.get("/api/v1/orders?status=active").body()).contains(orderId)
			.doesNotContain(closed.openOrder().toString());
		assertThat(problem(trader.get("/api/v1/orders/" + closed.openOrder()), 404)).isEqualTo("ORDER_NOT_FOUND");
		assertThat(problem(cancel(trader, closed.openOrder().toString()), 404)).isEqualTo("ORDER_NOT_FOUND");
		assertThat(cancel(trader, orderId).statusCode()).isEqualTo(200);

		assertThat(jdbc.queryForObject("SELECT state FROM orders WHERE id = ?", String.class, closed.openOrder()))
			.isEqualTo("OPEN");
		assertThat(jdbc.queryForObject("SELECT last_command_sequence FROM simulation WHERE id = ?", Long.class,
				closed.simulation())).isEqualTo(40);
	}

	/**
	 * Past the web layer, which would never let these through: a closed simulation's account asks the writer directly.
	 * Its cancel of the order still resting in the closed simulation finds no such order instead of halting the writer
	 * over a book that doesn't hold it, and its order is refused before anything is written, though it has the cash.
	 */
	@Test
	void aClosedSimulationsAccountNeitherTradesNorHaltsTheWriter() throws Exception {
		CancelResult canceled = engine
			.cancel(new CommandKey(closed.trader(), UUID.randomUUID(), "POST /api/v1/orders/{id}/cancel", new byte[32]),
					new CancelOrder(closed.openOrder(), closed.trader()))
			.get(5, TimeUnit.SECONDS);
		assertThat(canceled.status()).isEqualTo(CancelResult.Status.NOT_FOUND);

		assertRefusedBeforeAnyWrite(closed.bot());

		HttpResponse<String> placed = trader.post("/api/v1/orders", order(active.nova(), "BUY", 1, "80.00"),
				"Idempotency-Key", UUID.randomUUID().toString());
		assertThat(placed.statusCode()).as("the writer still runs: " + placed.body()).isEqualTo(201);
		assertThat(cancel(trader, JSON.readTree(placed.body()).get("orderId").asString()).statusCode()).isEqualTo(200);
	}

	@Test
	void botTokensAndTheSimulatorWorkInTheActiveSimulationOnly() throws Exception {
		TestBrowser client = new TestBrowser(port);
		assertThat(client.get("/api/v1/portfolio", bearer(ACTIVE_TOKEN.token())).statusCode()).isEqualTo(200);
		// Never revoked, and still refused: its account's simulation is closed.
		assertThat(client.get("/api/v1/portfolio", bearer(CLOSED_TOKEN.token())).statusCode()).isEqualTo(401);
		assertThat(client.post("/api/v1/orders", order(active.nova(), "BUY", 1, "90.00"), "Authorization",
				"Bearer " + CLOSED_TOKEN.token(), "Idempotency-Key", UUID.randomUUID().toString()).statusCode())
			.isEqualTo(401);

		// The closed simulation left one run a worker never confirmed and one still marked running; neither is work.
		JsonNode run = assignment().get("run");
		assertThat(run.get("runId").asString()).isEqualTo(active.run().toString());
		assertThat(run.get("botCount").asInt()).isEqualTo(2);
		List<String> assigned = new ArrayList<>();
		JSON.readTree(client.get("/internal/simulator/runs/" + active.run() + "/bots", bearer(SIMULATOR_TOKEN)).body())
			.get("bots")
			.forEach((bot) -> assigned.add(bot.get("accountId").asString()));
		assertThat(assigned).containsExactlyInAnyOrderElementsOf(jdbc.queryForList(
				"SELECT id::text FROM account WHERE simulation_id = ? AND kind = 'BOT'", String.class,
				active.simulation()));
		HttpResponse<String> beat = client.post("/internal/simulator/heartbeat", """
				{"runId":"%s","workerId":"worker-a","placed":1,"canceled":0,"rejected":0,"errors":0,"inFlight":0,
				"stopFailures":0,"stopped":false}""".formatted(active.run()), bearer(SIMULATOR_TOKEN));
		assertThat(beat.statusCode()).as(beat.body()).isEqualTo(200);
		assertThat(JSON.readTree(beat.body()).get("desiredState").asString()).isEqualTo("RUNNING");
		assertThat(assignment().get("run").get("worker").get("workerId").asString()).isEqualTo("worker-a");
	}

	/**
	 * A bot's token is checked without reading the simulation's row. The writer rewrites that row with every run, and
	 * while a backup's snapshot keeps the old versions, each read of it walks them all; a check made on every bot
	 * request must not pay that. Here nothing can read the table at all while a bot asks for an order, on a route that
	 * reads nothing of the simulation itself: a token check that read it would wait and fail on the lock timeout.
	 */
	@Test
	void aBotTokenIsCheckedWithoutReadingTheSimulationRow() throws Exception {
		TestBrowser bot = new TestBrowser(port);
		try (Connection locking = owner.getDataSource().getConnection()) {
			locking.setAutoCommit(false);
			try (Statement lock = locking.createStatement()) {
				lock.execute("LOCK TABLE simulation IN ACCESS EXCLUSIVE MODE");
				assertThat(problem(bot.get("/api/v1/orders/" + UUID.randomUUID(), bearer(ACTIVE_TOKEN.token())), 404))
					.isEqualTo("ORDER_NOT_FOUND");
			}
			finally {
				locking.rollback();
			}
		}
	}

	/**
	 * A restart takes over the active simulation only: it claims that one's epoch, rebuilds that one's books and
	 * reconciles that one's balances. The closed simulation would fail any of it. The accounts the writer then knows
	 * are the active simulation's too: a closed one's is refused as it was before the restart.
	 */
	@Test
	void aRestartRebuildsAndReconcilesTheActiveSimulationOnly() throws Exception {
		TestBrowser bot = new TestBrowser(port);
		assertThat(bot.post("/api/v1/orders", order(active.nova(), "SELL", 1, "100.00"), "Authorization",
				"Bearer " + ACTIVE_TOKEN.token(), "Idempotency-Key", UUID.randomUUID().toString()).statusCode())
			.isEqualTo(201);
		HttpResponse<String> bought = trader.post("/api/v1/orders", order(active.nova(), "BUY", 1, "100.00"),
				"Idempotency-Key", UUID.randomUUID().toString());
		assertThat(JSON.readTree(bought.body()).get("state").asString()).as(bought.body()).isEqualTo("FILLED");
		HttpResponse<String> resting = trader.post("/api/v1/orders", order(active.orbt(), "BUY", 3, "40.00"),
				"Idempotency-Key", UUID.randomUUID().toString());
		assertThat(resting.statusCode()).as(resting.body()).isEqualTo(201);
		long epoch = epoch(active.simulation());

		engine.start();

		assertThat(epoch(active.simulation())).isEqualTo(epoch + 1);
		assertThat(epoch(closed.simulation())).as("a closed simulation is never taken over").isEqualTo(7);
		assertThat(engine.depth(UUID.fromString(active.orbt()), Side.BUY).get(5, TimeUnit.SECONDS))
			.containsExactly(new DepthLevel(new Price(4_000), 3, 1));
		assertRefusedBeforeAnyWrite(closed.bot());
		assertThat(cancel(trader, JSON.readTree(resting.body()).get("orderId").asString()).statusCode()).isEqualTo(200);
	}

	@Test
	void aSecondSimulationThatIsNotClosedIsRefused() {
		assertFails(UNIQUE_VIOLATION,
				() -> owner.update("INSERT INTO simulation (id, state) VALUES (?, 'SETUP')", UUID.randomUUID()));
		assertFails(UNIQUE_VIOLATION, () -> owner
			.update("INSERT INTO simulation (id, state, opened_at) VALUES (?, 'HALTED', now())", UUID.randomUUID()));
	}

	/** Set up, funded, opened and given a run through the operator API, with the closed simulation already there. */
	private Active activeSimulation() throws Exception {
		String nova = instrument("NOVA", "100.00");
		String orbt = instrument("ORBT", "50.00");
		created(operator.post("/api/v1/admin/accounts", """
				{"username":"trader","cash":"100000.00","holdings":[{"instrumentId":"%s","quantity":500}]}"""
			.formatted(nova), "Idempotency-Key", UUID.randomUUID().toString()));
		for (String strategy : new String[] { "MARKET_MAKER", "NOISE" }) {
			created(operator.post("/api/v1/admin/bots", """
					{"count":1,"cash":"10000.00","holdings":[{"instrumentId":"%s","quantity":50}],"strategy":"%s"}"""
				.formatted(nova, strategy), "Idempotency-Key", UUID.randomUUID().toString()));
		}
		UUID simulation = jdbc.queryForObject("SELECT simulation_id FROM instrument WHERE id = ?::uuid", UUID.class, nova);
		jdbc.update("""
				INSERT INTO api_token (id, account_id, account_kind, token_hash, scope)
				SELECT ?, id, 'BOT', ?, 'TRADE' FROM account WHERE simulation_id = ? AND bot_strategy = 'NOISE'""",
				UUID.randomUUID(), ACTIVE_TOKEN.hash(), simulation);
		assertThat(operator.post("/api/v1/admin/market/open", "", "Idempotency-Key", UUID.randomUUID().toString())
			.statusCode()).isEqualTo(200);
		JsonNode run = JSON.readTree(created(operator.post("/api/v1/admin/bot-runs",
				"{\"marketMakers\":1,\"noise\":1,\"commandsPerSecond\":\"1.00\"}", "Idempotency-Key",
				UUID.randomUUID().toString())));
		return new Active(simulation, nova, orbt, UUID.fromString(run.get("runId").asString()));
	}

	/** Older than the fixture's, with an account for the same trader that no read may find. */
	private void olderClosedSimulation() {
		UUID simulation = UUID.randomUUID();
		owner.update("""
				INSERT INTO simulation (id, state, opened_at, closed_at, created_at)
				VALUES (?, 'CLOSED', now() - interval '9 days', now() - interval '8 days', now() - interval '10 days')""",
				simulation);
		UUID account = UUID.randomUUID();
		owner.update("""
				INSERT INTO account (id, simulation_id, kind, owner_user_id, display_name, created_at)
				VALUES (?, ?, 'TRADER', ?, 'trader', now() - interval '10 days')""", account, simulation, user("trader"));
		owner.update("INSERT INTO cash_balance (account_id, account_kind, total_cents, reserved_cents) VALUES (?, 'TRADER', 1, 0)",
				account);
	}

	private Closed closedSimulation() {
		UUID simulation = UUID.randomUUID();
		owner.update("""
				INSERT INTO simulation (id, state, writer_epoch, last_command_sequence, opened_at, closed_at, created_at)
				VALUES (?, 'CLOSED', 7, 40, now() - interval '2 days', now() - interval '1 day',
				    now() - interval '3 days')""", simulation);
		String account = """
				INSERT INTO account (id, simulation_id, kind, owner_user_id, display_name, bot_strategy, created_at)
				VALUES (?, ?, ?, ?, ?, ?, now() - interval '3 days')""";
		owner.update(account, UUID.randomUUID(), simulation, "ISSUANCE", null, "Issuance", null);
		UUID traderUser = user("trader");
		UUID trader = UUID.randomUUID();
		owner.update(account, trader, simulation, "TRADER", traderUser, "trader", null);
		UUID former = UUID.randomUUID();
		TestUsers.insert(owner, former, "former", TestBrowser.passwordHash(PASSWORD), "TRADER");
		owner.update(account, UUID.randomUUID(), simulation, "TRADER", former, "former", null);
		List<UUID> bots = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
		for (UUID bot : bots) {
			owner.update(account, bot, simulation, "BOT", null, "bot-closed", "NOISE");
		}
		UUID bot = bots.getFirst();
		String cash = "INSERT INTO cash_balance (account_id, account_kind, total_cents, reserved_cents) VALUES (?, ?, ?, 0)";
		owner.update(cash, trader, "TRADER", 500);
		owner.update(cash, bot, "BOT", 1_000_000);
		owner.update("INSERT INTO api_token (id, account_id, account_kind, token_hash, scope) VALUES (?, ?, 'BOT', ?, 'TRADE')",
				UUID.randomUUID(), bot, CLOSED_TOKEN.hash());

		UUID nova = UUID.randomUUID();
		String instrument = """
				INSERT INTO instrument (id, simulation_id, symbol, reference_price_cents, last_trade_sequence)
				VALUES (?, ?, ?, 10000, ?)""";
		owner.update(instrument, nova, simulation, "NOVA", 1);
		owner.update(instrument, UUID.randomUUID(), simulation, "ORBT", 0);
		String order = """
				INSERT INTO orders (id, simulation_id, account_id, instrument_id, side, type, time_in_force, bound_cents,
				    requested_quantity, filled_quantity, working_quantity, state, accepted_sequence)
				VALUES (?, ?, ?, ?, ?, 'LIMIT', 'GTC', ?, ?, ?, ?, ?, ?)""";
		UUID open = UUID.randomUUID();
		UUID maker = UUID.randomUUID();
		UUID taker = UUID.randomUUID();
		// Left resting on purpose, and without its hold: closing would have cancelled it.
		owner.update(order, open, simulation, trader, nova, "BUY", 9_000, 5, 0, 5, "OPEN", 10);
		owner.update(order, maker, simulation, bot, nova, "SELL", 10_000, 2, 2, 0, "FILLED", 11);
		owner.update(order, taker, simulation, trader, nova, "BUY", 10_000, 2, 2, 0, "FILLED", 12);
		owner.update("""
				INSERT INTO trades (id, simulation_id, instrument_id, trade_sequence, maker_order_id, taker_order_id,
				    buyer_account_id, seller_account_id, price_cents, quantity, executed_at, command_sequence)
				VALUES (?, ?, ?, 1, ?, ?, ?, ?, 10000, 2, now() - interval '36 hours', 12)""", UUID.randomUUID(),
				simulation, nova, maker, taker, trader, bot);
		owner.update("""
				INSERT INTO command_result (simulation_id, principal_id, idempotency_key, route, request_hash,
				    response_status, response_body, command_sequence)
				VALUES (?, ?, ?, 'POST /api/v1/orders', ?, 201, ?::jsonb, 12)""", simulation, traderUser, SHARED_KEY,
				new byte[32], """
						{"orderId":"%s","state":"FILLED","reason":null,"rejection":null,"filled":2,"working":0,
						"canceled":0,"commandSequence":12}""".formatted(taker));

		String run = """
				INSERT INTO bot_run (id, simulation_id, desired_state, seed, commands_per_second, max_order_quantity,
				    max_position, started_by, start_sequence, started_at, stop_requested_at, worker_id, lease_expires_at)
				VALUES (?, ?, ?, 1, 1, 10, 100, ?, ?, now() - interval '40 hours', %s)""";
		UUID stuck = UUID.randomUUID();
		UUID running = UUID.randomUUID();
		// Stopped, its worker gone without confirming: before anything else, it would be handed to the next worker.
		owner.update(run.formatted("now() - interval '39 hours', 'old-worker', now() - interval '39 hours'"), stuck,
				simulation, "STOPPED", user("operator"), 20);
		owner.update(run.formatted("NULL, NULL, NULL"), running, simulation, "RUNNING", user("operator"), 30);
		owner.update("""
				INSERT INTO bot_run_instrument (run_id, simulation_id, instrument_id, start_trade_sequence)
				VALUES (?, ?, ?, 1)""", running, simulation, nova);
		owner.update("INSERT INTO bot_assignment (run_id, account_id, account_kind, strategy) VALUES (?, ?, 'BOT', 'NOISE')",
				running, bot);
		return new Closed(simulation, trader, bot, open, stuck, running);
	}

	/** The account's order for the active NOVA fails as not an account of this market, and leaves nothing behind. */
	private void assertRefusedBeforeAnyWrite(UUID account) {
		UUID orderId = UUID.randomUUID();
		long sequence = sequence(active.simulation());
		assertThatThrownBy(() -> engine
			.place(new CommandKey(account, UUID.randomUUID(), "POST /api/v1/orders", new byte[32]),
					new PlaceOrder(orderId, account, UUID.fromString(active.nova()), Side.BUY, OrderType.LIMIT,
							new Price(9_000), new Quantity(1)))
			.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(TradingEngine.UnknownAccountException.class);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE id = ?", Long.class, orderId)).isZero();
		assertThat(sequence(active.simulation())).as("refused before its transaction").isEqualTo(sequence);
	}

	private UUID user(String username) {
		return jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", UUID.class, username);
	}

	private long sequence(UUID simulation) {
		return jdbc.queryForObject("SELECT last_command_sequence FROM simulation WHERE id = ?", Long.class, simulation);
	}

	private long epoch(UUID simulation) {
		return jdbc.queryForObject("SELECT writer_epoch FROM simulation WHERE id = ?", Long.class, simulation);
	}

	private String instrument(String symbol, String price) throws Exception {
		return JSON.readTree(created(operator.post("/api/v1/admin/instruments",
				"{\"symbol\":\"%s\",\"referencePrice\":\"%s\"}".formatted(symbol, price), "Idempotency-Key",
				UUID.randomUUID().toString())))
			.get("instrumentId")
			.asString();
	}

	private static List<String> ids(HttpResponse<String> response, String list, String field) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		List<String> ids = new ArrayList<>();
		JSON.readTree(response.body()).get(list).forEach((row) -> ids.add(row.get(field).asString()));
		return ids;
	}

	private JsonNode assignment() throws Exception {
		HttpResponse<String> read = new TestBrowser(port).get("/internal/simulator/assignment", bearer(SIMULATOR_TOKEN));
		assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
		return JSON.readTree(read.body());
	}

	private static HttpResponse<String> cancel(TestBrowser browser, String orderId) throws Exception {
		return browser.post("/api/v1/orders/" + orderId + "/cancel", "", "Idempotency-Key",
				UUID.randomUUID().toString());
	}

	private static String created(HttpResponse<String> response) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
		return response.body();
	}

	private static String problem(HttpResponse<String> response, int status) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
		return JSON.readTree(response.body()).get("code").asString();
	}

	private static String[] bearer(String token) {
		return new String[] { "Authorization", "Bearer " + token };
	}

	private static String order(String instrument, String side, long quantity, String price) {
		return """
				{"instrumentId":"%s","side":"%s","type":"LIMIT","timeInForce":"GTC","quantity":%d,"limitPrice":"%s"}"""
			.formatted(instrument, side, quantity, price);
	}

}
