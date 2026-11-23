package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.mia_princz.mse.execution.trading.application.TradingEngine;
import com.mia_princz.mse.execution.trading.application.TradingStore;
import com.mia_princz.mse.execution.trading.domain.Side;

/**
 * Three simulations in a row, over real HTTP and PostgreSQL, set up, traded and closed through the API, each started
 * by an operator by name. The first is started with no market at all. The second and third are started after a
 * close, and the second is set up with the same symbols and the same traders as the first, with other amounts, so
 * that anything read from the wrong simulation shows. The story is told once, and every test reads what it left: the
 * answers and the reads taken on the way, and each simulation's rows, which a closed simulation never changes again.
 * <p>
 * The backup manifest is read on the way too, by running the script the backup runs, inside this PostgreSQL.
 * <p>
 * What a start does when it does not go through in one piece, and what the engine holds afterwards, are in
 * {@link SimulationStartEngineIT}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=simulation-start", "mse.market.close-chunk-orders=3",
				"mse.bots.token-directory=${java.io.tmpdir}/mse-simulation-start-it-tokens",
				"mse.dev-users.trader-password=" + SimulationStartIT.PASSWORD,
				"mse.dev-users.operator-password=" + SimulationStartIT.PASSWORD,
				"mse.simulator.token=" + SimulationStartIT.SIMULATOR_TOKEN,
				// Figures are computed when a test asks, not behind its back.
				"mse.runs.figures-interval=1h",
				// A few accounts send commands back to back. AccountRateLimitIT covers pacing.
				"mse.trading.human-commands-per-second=1000", "mse.trading.bot-commands-per-second=1000" })
class SimulationStartIT {

	static final String PASSWORD = "test-simulation-start-password";

	static final String SIMULATOR_TOKEN = "test-simulation-start-simulator-token";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final OpenApiContract CONTRACT = OpenApiContract.get();

	private static final String START = "/api/v1/admin/simulations";

	private static final String CLOSE = "/api/v1/admin/market/close";

	private static final String PLACE = "/api/v1/orders";

	/** An account's totals and what of them is held: cash, then each instrument's shares by symbol. */
	private record Balances(long cash, long cashReserved, Map<String, Long> shares, Map<String, Long> sharesReserved) {
	}

	/**
	 * One simulation as the story set it up: its instruments by symbol, with the answer to adding the first, its
	 * accounts by name, its run, and its bots' tokens and token files.
	 */
	private record Setup(UUID simulation, Map<String, String> instruments, Answer firstInstrument,
			Map<String, UUID> accounts, UUID run, Map<String, String> botTokens, List<Path> tokenFiles) {
	}

	/** An answer as the story got it: its status, its problem code if it has one, and its body. */
	private record Answer(int status, String code, String body, String contentType) {

		JsonNode json() {
			return JSON.readTree(body);
		}

	}

	/** Everything the story left for the tests: what was answered and read on the way, in the order it happened. */
	private static final class Story {

		UUID operatorId;

		Map<String, String> manifestEmpty;

		Answer statusBefore;

		Map<String, Answer> badRequests = new LinkedHashMap<>();

		Answer keyless;

		int traderStarts;

		int anonymousStarts;

		UUID alphaKey;

		Answer alphaStarted;

		Answer alphaAgain;

		Answer alphaConflict;

		Answer statusAlphaSetup;

		UUID setupKey;

		Answer refusedInSetup;

		UUID openKey;

		Answer refusedOpen;

		Answer refusedHalted;

		Setup alpha;

		UUID sharedKey;

		String sharedBody;

		Answer alphaShared;

		UUID closeKey;

		Answer alphaClosed;

		long alphaEpoch;

		long alphaSequenceClosed;

		Map<UUID, Balances> alphaBalances;

		Map<String, String> manifestAlphaClosed;

		UUID takenKey;

		Answer nameTaken;

		long alphaSequence;

		UUID betaKey;

		Answer betaStarted;

		Answer betaAgain;

		Answer refusedOpenAgain;

		long alphaEpochRetired;

		long betaEpoch;

		List<Long> sequencesAfterRetries;

		boolean streamOpen;

		boolean streamEnded;

		Answer statusBetaSetup;

		Map<String, Answer> unfunded = new LinkedHashMap<>();

		int accountsListed;

		int botsListed;

		int runsListed;

		Map<String, Boolean> fundedBefore;

		Map<String, Integer> oldToken = new LinkedHashMap<>();

		boolean assignmentEmpty;

		Answer oldHeartbeat;

		Answer oldRun;

		Answer oldRevoke;

		boolean oldBooksGone;

		Answer secondAddedAgain;

		Setup beta;

		Answer traderFunded;

		Map<String, Boolean> fundedAfter;

		Answer oldOrderRead;

		Answer oldOrderCancel;

		Answer betaShared;

		Answer betaLookup;

		Answer betaTrade;

		boolean newTokenFilesExist;

		int oldTokenWhileOpen;

		Answer assignmentBeta;

		Answer alphaClosedAgain;

		long sequencesTakenByTheOldClose;

		boolean newTokenFilesKept;

		int newTokenAfterTheOldClose;

		Answer statusBetaOpen;

		Map<String, String> manifestBetaOpen;

		Map<String, String> manifestBeforeNames;

		long outboxAtBetaOpen;

		Answer betaClosed;

		long betaSequence;

		Map<UUID, Balances> betaBalances;

		UUID gammaKey;

		Answer gammaStarted;

		Answer statusGamma;

		Map<String, Long> epochs;

		Map<String, String> manifestGamma;

		int gammaAccounts;

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
	PostgreSQLContainer postgres;

	@Value("${mse.bots.token-directory}")
	Path tokenDirectory;

	private TestBrowser operator;

	@BeforeEach
	void threeSimulations() throws Exception {
		operator = TestBrowser.loggedIn(port, "operator", PASSWORD);
		synchronized (SimulationStartIT.class) {
			if (story == null) {
				Story told = new Story();
				tell(told);
				story = told;
			}
		}
	}

	/** A name is required, trimmed, 1 to 80 characters and printable; nothing is committed for one that is not. */
	@Test
	void aBadNameAMissingKeyAndAnyoneButAnOperatorAreRefusedBeforeAnything() {
		assertThat(story.statusBefore.json().get("state").asString()).isEqualTo("NOT_SET_UP");
		assertThat(story.statusBefore.json().get("name").isNull()).isTrue();
		assertThat(story.badRequests).hasSize(6).allSatisfy((request, answer) -> {
			assertThat(answer.status()).as(request + ": " + answer.body()).isEqualTo(400);
			CONTRACT.assertResponse("POST", START, 400, answer.contentType(), answer.body());
		});
		for (String named : List.of("empty", "spaces only", "81 characters", "a control character")) {
			JsonNode error = story.badRequests.get(named).json().get("errors").get(0);
			assertThat(error.get("field").asString() + " " + error.get("code").asString()).as(named)
				.isEqualTo("name INVALID_NAME");
		}
		assertThat(story.badRequests.get("no name").json().get("errors").get(0).get("code").asString())
			.isEqualTo("REQUIRED");
		assertThat(story.keyless.code()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
		assertThat(story.traderStarts).isEqualTo(403);
		assertThat(story.anonymousStarts).isIn(401, 403);
	}

	/**
	 * With no market at all, a start is the explicit way to the first one: in SETUP, named, with the name trimmed,
	 * and the start is its command 1. Its key repeats the answer, whatever white space the retry has around the name.
	 */
	@Test
	void withNoMarketAStartCreatesTheFirstByName() {
		Answer started = story.alphaStarted;
		assertThat(started.status()).as(started.body()).isEqualTo(201);
		CONTRACT.assertResponse("POST", START, 201, started.contentType(), started.body());
		assertThat(started.json().get("name").asString()).isEqualTo("Alpha");
		assertThat(started.json().get("state").asString()).isEqualTo("SETUP");
		assertThat(started.json().get("commandSequence").asString()).isEqualTo("1");
		assertThat(started.json().get("simulationId").asString()).isEqualTo(story.alpha.simulation().toString());
		assertThat(story.alphaAgain.status()).isEqualTo(201);
		assertThat(story.alphaAgain.body()).isEqualTo(started.body());
		assertThat(story.alphaConflict.code()).isEqualTo("IDEMPOTENCY_CONFLICT");

		JsonNode status = story.statusAlphaSetup.json();
		CONTRACT.assertResponse("GET", "/api/v1/market", 200, story.statusAlphaSetup.contentType(),
				story.statusAlphaSetup.body());
		assertThat(status.get("state").asString()).isEqualTo("SETUP");
		assertThat(status.get("name").asString()).isEqualTo("Alpha");
		assertThat(status.get("simulationId").asString()).isEqualTo(story.alpha.simulation().toString());
		assertThat(status.get("commandSequence").asString()).as("only the start so far").isEqualTo("1");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM account WHERE simulation_id = ? AND kind = 'ISSUANCE'",
				Long.class, story.alpha.simulation())).isEqualTo(1);
	}

	/**
	 * A simulation that has not closed refuses a start, naming its state, as one of its own commands: sequenced,
	 * stored and audited in it. A name another simulation has is refused the same way, by the closed one.
	 */
	@Test
	void aStartIsRefusedWhileASimulationHasNotClosedAndForATakenName() {
		Map<String, Answer> refusals = new LinkedHashMap<>();
		refusals.put("SETUP", story.refusedInSetup);
		refusals.put("OPEN", story.refusedOpen);
		refusals.put("HALTED", story.refusedHalted);
		refusals.forEach((state, refused) -> {
			assertThat(refused.status()).as(state + ": " + refused.body()).isEqualTo(409);
			assertThat(refused.code()).isEqualTo("SIMULATION_NOT_CLOSED");
			assertThat(refused.json().get("state").asString()).isEqualTo(state);
			assertThat(refused.json().get("detail").asString()).contains(state);
			CONTRACT.assertResponse("POST", START, 409, refused.contentType(), refused.body());
		});
		assertThat(story.refusedInSetup.json().get("commandSequence").asString()).as("the second command of Alpha")
			.isEqualTo("2");
		assertThat(jdbc.queryForObject("""
				SELECT a.action || ' ' || a.outcome || ' ' || a.command_sequence || ' ' || (a.simulation_id = ?) || ' '
				    || c.response_status
				FROM audit_log a JOIN command_result c
				    ON c.simulation_id = a.simulation_id AND c.command_sequence = a.command_sequence
				WHERE a.correlation_id = ?""", String.class, story.alpha.simulation(), story.setupKey))
			.isEqualTo("SIMULATION_START NOT_ALLOWED 2 true 409");

		Answer taken = story.nameTaken;
		assertThat(taken.status()).as(taken.body()).isEqualTo(409);
		assertThat(taken.code()).as("names differ, whatever their case").isEqualTo("SIMULATION_NAME_TAKEN");
		CONTRACT.assertResponse("POST", START, 409, taken.contentType(), taken.body());
		assertThat(taken.json().get("commandSequence").asLong()).as("a command of the closed simulation")
			.isEqualTo(story.alphaSequenceClosed + 1);
		assertThat(jdbc.queryForObject("""
				SELECT action || ' ' || outcome || ' ' || (simulation_id = ?) FROM audit_log WHERE correlation_id = ?""",
				String.class, story.alpha.simulation(), story.takenKey)).isEqualTo("SIMULATION_START NOT_ALLOWED true");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM simulation", Long.class)).isEqualTo(3);
	}

	/**
	 * The start after a close: the new simulation is in SETUP with the name, its own issuance account, sequence 1 and
	 * the writer's epoch; the closed one has epoch 0 and took no command for it. The start's stored answer and audit
	 * row are the new simulation's, so its key finds them there.
	 */
	@Test
	void aStartAfterACloseIsTheNewSimulationsFirstCommandAndRetiresTheClosedOne() {
		Answer started = story.betaStarted;
		assertThat(started.status()).as(started.body()).isEqualTo(201);
		CONTRACT.assertResponse("POST", START, 201, started.contentType(), started.body());
		UUID beta = story.beta.simulation();
		assertThat(started.json().get("simulationId").asString()).isEqualTo(beta.toString());
		assertThat(started.json().get("name").asString()).isEqualTo("Béta run");
		assertThat(started.json().get("commandSequence").asString()).isEqualTo("1");
		assertThat(story.betaAgain.status()).isEqualTo(201);
		assertThat(story.betaAgain.body()).isEqualTo(started.body());

		assertThat(story.alphaEpoch).as("the epoch the writer held, after a restart").isGreaterThan(1);
		assertThat(story.alphaEpochRetired).as("no writer ever holds 0").isZero();
		assertThat(story.betaEpoch).as("this writer's epoch, moved to the new simulation").isEqualTo(story.alphaEpoch);
		assertThat(story.sequencesAfterRetries).as("the start took no command in Alpha, and one in Béta")
			.containsExactly(story.alphaSequence, 1L);
		assertThat(jdbc.queryForObject("""
				SELECT route || ' ' || response_status || ' ' || command_sequence || ' ' || (principal_id = ?)
				FROM command_result WHERE simulation_id = ? AND idempotency_key = ?""", String.class, story.operatorId,
				beta, story.betaKey)).isEqualTo("POST /api/v1/admin/simulations 201 1 true");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM command_result WHERE idempotency_key = ?", Long.class,
				story.betaKey)).as("stored once, and not in the closed simulation").isEqualTo(1);
		assertThat(jdbc.queryForObject("""
				SELECT action || ' ' || outcome || ' ' || command_sequence || ' ' || (actor_user_id = ?)
				FROM audit_log WHERE simulation_id = ? AND correlation_id = ?""", String.class, story.operatorId, beta,
				story.betaKey)).isEqualTo("SIMULATION_START APPLIED 1 true");
		assertThat(jdbc.queryForObject("""
				SELECT state || ' ' || name || ' ' || (SELECT count(*) FROM account WHERE simulation_id = s.id AND kind = 'ISSUANCE')
				FROM simulation s WHERE id = ?""", String.class, story.alpha.simulation())).isEqualTo("CLOSED Alpha 1");
		assertThat(jdbc.queryForObject("""
				SELECT (SELECT id FROM account WHERE simulation_id = ? AND kind = 'ISSUANCE')
				    <> (SELECT id FROM account WHERE simulation_id = ? AND kind = 'ISSUANCE')""", Boolean.class,
				story.alpha.simulation(), beta)).as("its own issuance account").isTrue();
	}

	/**
	 * A key that was refused while the market was open keeps that answer after the market has closed and another
	 * simulation has started. A lookup in the current simulation alone would find nothing by then and run the request
	 * again; this one is found where it was stored, and starts nothing.
	 */
	@Test
	void aRefusedKeyKeepsItsAnswerAfterALaterStart() throws Exception {
		assertThat(story.refusedOpenAgain.status()).isEqualTo(409);
		assertThat(story.refusedOpenAgain.body()).isEqualTo(story.refusedOpen.body());
		// Again now, three simulations on: still the answer Alpha stored, and still three simulations.
		HttpResponse<String> late = operator.post(START, "{\"name\":\"Too early\"}", "Idempotency-Key",
				story.openKey.toString());
		assertThat(late.statusCode()).isEqualTo(409);
		assertThat(late.body()).isEqualTo(story.refusedOpen.body());
		HttpResponse<String> first = operator.post(START, "{\"name\":\"Alpha\"}", "Idempotency-Key",
				story.alphaKey.toString());
		assertThat(first.statusCode()).as("the key that started Alpha still names Alpha").isEqualTo(201);
		assertThat(first.body()).isEqualTo(story.alphaStarted.body());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM simulation", Long.class)).isEqualTo(3);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM command_result WHERE idempotency_key IN (?, ?)", Long.class,
				story.openKey, story.alphaKey)).isEqualTo(2);
	}

	/**
	 * Everything people read follows the new simulation from the start on. Logins are kept, accounts are not: a trader
	 * of the closed simulation has no trading account, on any read, command or stream, until funded in the new one's
	 * setup. The operator's lists are the new simulation's, so empty.
	 */
	@Test
	void readsFollowTheNewSimulationAndAnOldTraderHasNoAccountUntilFunded() {
		JsonNode status = story.statusBetaSetup.json();
		CONTRACT.assertResponse("GET", "/api/v1/market", 200, story.statusBetaSetup.contentType(),
				story.statusBetaSetup.body());
		assertThat(status.get("state").asString()).isEqualTo("SETUP");
		assertThat(status.get("simulationId").asString()).isEqualTo(story.beta.simulation().toString());
		assertThat(status.get("name").asString()).isEqualTo("Béta run");
		assertThat(status.get("openedAt").isNull()).isTrue();
		assertThat(status.get("closedAt").isNull()).isTrue();
		assertThat(status.get("commandSequence").asString()).isEqualTo("1");

		assertThat(story.unfunded).hasSize(6).allSatisfy((read, answer) -> {
			assertThat(answer.status()).as(read + ": " + answer.body()).isEqualTo(403);
			assertThat(answer.code()).as(read).isEqualTo("NO_TRADING_ACCOUNT");
		});
		assertThat(story.accountsListed).isZero();
		assertThat(story.botsListed).isZero();
		assertThat(story.runsListed).isZero();
		assertThat(story.fundedBefore).containsEntry("trader", false).containsEntry("second", false);

		// Funded again, with the same login: the new account's amounts, and nothing of the old one's.
		assertThat(story.secondAddedAgain.code()).as("the login exists; it is funded, not added").isEqualTo("USERNAME_TAKEN");
		assertThat(story.fundedAfter).containsEntry("trader", true).containsEntry("second", true);
		assertThat(story.traderFunded.status()).isEqualTo(200);
		assertThat(story.traderFunded.json().get("cash").get("total").asString()).isEqualTo("50000.00");
		assertThat(story.beta.accounts().get("trader")).isNotEqualTo(story.alpha.accounts().get("trader"));
		assertThat(story.oldOrderRead.code()).as("an order of the old account is nobody's here").isEqualTo("ORDER_NOT_FOUND");
		assertThat(story.oldOrderCancel.status()).isEqualTo(404);
		assertThat(story.statusBetaOpen.json().get("state").asString()).isEqualTo("OPEN");
		assertThat(story.statusBetaOpen.json().get("name").asString()).isEqualTo("Béta run");
		assertThat(story.statusGamma.json().get("name").asString()).isEqualTo("Gamma");
		assertThat(story.statusGamma.json().get("state").asString()).isEqualTo("SETUP");
		assertThat(story.gammaAccounts).isZero();
	}

	/**
	 * The private stream of an old account is closed when the next simulation starts: its account changes no more, and
	 * a client that asks again is given its account in the new simulation, or told it has none.
	 */
	@Test
	void anOldAccountsStreamEndsWhenTheNextSimulationStarts() {
		assertThat(story.streamOpen).as("open through the close, which ends no stream").isTrue();
		assertThat(story.streamEnded).isTrue();
		assertThat(story.unfunded.get("the stream").code()).isEqualTo("NO_TRADING_ACCOUNT");
	}

	/**
	 * Nothing of the closed simulation is work again: its bot tokens stay dead, its run is handed to no worker and a
	 * heartbeat for it is refused, the operator's commands do not reach its bots, and the token files are gone. The
	 * new simulation's bots have new accounts, tokens and files.
	 */
	@Test
	void theClosedSimulationsBotsRunsAndTokensStayDead() {
		assertThat(story.oldToken).containsOnlyKeys("a read", "an order");
		assertThat(story.oldToken.values()).containsOnly(401);
		assertThat(story.assignmentEmpty).isTrue();
		assertThat(story.oldHeartbeat.code()).isEqualTo("RUN_FINISHED");
		assertThat(story.oldRun.status()).as("the run list is the new simulation's").isEqualTo(404);
		assertThat(story.oldRevoke.code()).as("an old bot is no bot of this market").isEqualTo("BOT_NOT_FOUND");
		assertThat(story.alpha.tokenFiles()).hasSize(3).noneMatch(Files::exists);
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM api_token t JOIN account a ON a.id = t.account_id
				WHERE a.simulation_id IN (?, ?) AND t.revoked_at IS NULL""", Long.class, story.alpha.simulation(),
				story.beta.simulation())).as("every token of a closed simulation is revoked").isZero();

		assertThat(story.newTokenFilesExist).isTrue();
		assertThat(story.beta.tokenFiles()).hasSize(2).doesNotContainAnyElementsOf(story.alpha.tokenFiles());
		assertThat(story.beta.botTokens().values()).doesNotContainAnyElementsOf(story.alpha.botTokens().values());
		assertThat(story.oldTokenWhileOpen).as("an old token, while the new market is open").isEqualTo(401);
		JsonNode assigned = story.assignmentBeta.json().get("run");
		assertThat(assigned.get("runId").asString()).isEqualTo(story.beta.run().toString());
		assertThat(story.beta.tokenFiles()).as("removed again by the second close").noneMatch(Files::exists);
		// The old run's last heartbeat changed nothing of it, and nothing of a closed simulation's run changes later.
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM bot_run r JOIN simulation s ON s.id = r.simulation_id
				WHERE r.simulation_id IN (?, ?) AND r.ended_by = 'MARKET_CLOSE' AND r.stopped_at = s.closed_at
				    AND coalesce(r.heartbeat_at, s.closed_at) <= s.closed_at""", Long.class, story.alpha.simulation(),
				story.beta.simulation())).isEqualTo(2);
	}

	/** The engine holds no book of a closed simulation's instruments: their depth cannot even be asked for. */
	@Test
	void theEngineHoldsNoBookOfAClosedSimulation() {
		assertThat(story.oldBooksGone).isTrue();
		for (Setup closed : List.of(story.alpha, story.beta)) {
			for (String instrument : closed.instruments().values()) {
				assertThatThrownBy(() -> engine.depth(UUID.fromString(instrument), Side.BUY).get(5, TimeUnit.SECONDS))
					.isInstanceOf(ExecutionException.class);
			}
		}
	}

	/**
	 * The same symbols and the same traders in two simulations, kept apart: each simulation's commands are numbered
	 * from 1 with one stored answer and nothing missing, each instrument's trades from 1, and no row of one refers to
	 * an account, an instrument or a run of the other.
	 */
	@Test
	void sequencesStoredAnswersTradesRunsAndAuditRowsAreKeptApart() {
		UUID alpha = story.alpha.simulation();
		UUID beta = story.beta.simulation();
		assertThat(story.beta.firstInstrument().json().get("commandSequence").asString())
			.as("the start, the refused revoke, the refused trader, then the first instrument")
			.isEqualTo("4");
		assertThat(story.beta.instruments().keySet()).isEqualTo(story.alpha.instruments().keySet());
		assertThat(story.beta.instruments().values()).doesNotContainAnyElementsOf(story.alpha.instruments().values());
		Map<UUID, Long> last = Map.of(alpha, story.alphaSequence, beta, story.betaSequence);
		last.forEach((simulation, sequence) -> {
			assertThat(jdbc.queryForObject("SELECT last_command_sequence FROM simulation WHERE id = ?", Long.class,
					simulation)).as("a closed simulation takes no more commands").isEqualTo(sequence);
			assertThat(jdbc.queryForObject("""
					SELECT count(*) || ' ' || min(command_sequence) || ' ' || max(command_sequence)
					FROM command_result WHERE simulation_id = ?""", String.class, simulation))
				.as("one stored answer for each sequence, from 1")
				.isEqualTo(sequence + " 1 " + sequence);
			assertThat(jdbc.queryForObject("""
					SELECT route FROM command_result WHERE simulation_id = ? AND command_sequence = 1""", String.class,
					simulation)).isEqualTo("POST /api/v1/admin/simulations");
			// Every audit row finds its stored answer in its own simulation, by its sequence and its key.
			assertThat(jdbc.queryForObject("""
					SELECT count(*) FILTER (WHERE c.idempotency_key = a.correlation_id) || ' of ' || count(*)
					FROM audit_log a LEFT JOIN command_result c
					    ON c.simulation_id = a.simulation_id AND c.command_sequence = a.command_sequence
					WHERE a.simulation_id = ?""", String.class, simulation)).matches("([1-9][0-9]*) of \\1");
			assertThat(jdbc.queryForObject("""
					SELECT count(*) FROM orders o JOIN account a ON a.id = o.account_id JOIN instrument i ON i.id = o.instrument_id
					WHERE o.simulation_id = ? AND (a.simulation_id <> o.simulation_id OR i.simulation_id <> o.simulation_id)""",
					Long.class, simulation)).isZero();
			assertThat(jdbc.queryForObject("""
					SELECT count(*) FROM trades t JOIN account b ON b.id = t.buyer_account_id
					    JOIN account s ON s.id = t.seller_account_id JOIN instrument i ON i.id = t.instrument_id
					WHERE t.simulation_id = ? AND (b.simulation_id <> t.simulation_id OR s.simulation_id <> t.simulation_id
					    OR i.simulation_id <> t.simulation_id)""", Long.class, simulation)).isZero();
			assertThat(jdbc.queryForObject("""
					SELECT count(*) FROM bot_assignment b JOIN bot_run r ON r.id = b.run_id JOIN account a ON a.id = b.account_id
					WHERE r.simulation_id = ? AND a.simulation_id <> r.simulation_id""", Long.class, simulation)).isZero();
		});

		// NOVA traded twice in Alpha and once in Béta: each simulation numbers its instruments' trades from 1.
		assertThat(tradeSequences(alpha)).containsExactly(Map.entry("NOVA", "2 1 2"));
		assertThat(tradeSequences(beta)).containsExactly(Map.entry("NOVA", "1 1 1"));
		assertThat(story.betaTrade.json().get("state").asString()).isEqualTo("FILLED");
		assertThat(jdbc.queryForObject("SELECT count(*) || ' ' || count(DISTINCT simulation_id) FROM bot_run", String.class))
			.as("one run in each of the two that traded")
			.isEqualTo("2 2");
		assertThat(jdbc.queryForObject("SELECT simulation_id FROM bot_run WHERE id = ?", UUID.class, story.alpha.run()))
			.isEqualTo(alpha);
		assertThat(jdbc.queryForObject("SELECT simulation_id FROM bot_run WHERE id = ?", UUID.class, story.beta.run()))
			.isEqualTo(beta);
		assertThat(jdbc.queryForObject("""
				SELECT string_agg(action || ' ' || outcome, ', ' ORDER BY command_sequence) FROM audit_log
				WHERE simulation_id = (SELECT id FROM simulation WHERE name = 'Gamma')""", String.class))
			.startsWith("SIMULATION_START APPLIED");
	}

	/**
	 * The same idempotency key in two simulations is two commands: the trader's key that placed an order in Alpha
	 * places another in Béta, and the lookup by key answers for the current simulation's.
	 */
	@Test
	void theSameKeyInTwoSimulationsIsTwoCommands() {
		assertThat(story.alphaShared.status()).isEqualTo(201);
		assertThat(story.betaShared.status()).as(story.betaShared.body()).isEqualTo(201);
		String first = story.alphaShared.json().get("orderId").asString();
		String second = story.betaShared.json().get("orderId").asString();
		assertThat(second).isNotEqualTo(first);
		assertThat(story.betaLookup.status()).isEqualTo(201);
		assertThat(story.betaLookup.body()).isEqualTo(story.betaShared.body());
		Map<UUID, String> stored = new HashMap<>();
		jdbc.query("SELECT simulation_id, response_body ->> 'orderId' FROM command_result WHERE idempotency_key = ?",
				(row) -> {
					stored.put(row.getObject(1, UUID.class), row.getString(2));
				}, story.sharedKey);
		assertThat(stored).containsOnly(Map.entry(story.alpha.simulation(), first),
				Map.entry(story.beta.simulation(), second));
		assertThat(jdbc.queryForObject("SELECT simulation_id FROM orders WHERE id = ?::uuid", UUID.class, second))
			.isEqualTo(story.beta.simulation());
	}

	/**
	 * A transition is sent with no body, so its key is the one thing that says which command it is, and a close's key
	 * is the one a caller is told to send again when the answer did not come. Sent after the next simulation has
	 * started and opened, the key that closed Alpha still answers for Alpha, and the market that trades is not
	 * closed: it takes no command, its bots keep their tokens and their token files, and it stays open.
	 */
	@Test
	void aClosesKeySentAfterTheNextSimulationOpenedAnswersForTheMarketItClosed() {
		assertThat(story.alphaClosed.json().get("canceledOrders").asInt()).isEqualTo(4);
		assertThat(story.alphaClosedAgain.status()).as(story.alphaClosedAgain.body()).isEqualTo(200);
		assertThat(story.alphaClosedAgain.body()).isEqualTo(story.alphaClosed.body());
		CONTRACT.assertResponse("POST", CLOSE, 200, story.alphaClosedAgain.contentType(), story.alphaClosedAgain.body());
		assertThat(story.sequencesTakenByTheOldClose).isZero();
		assertThat(story.statusBetaOpen.json().get("state").asString()).as("read after the old close came again")
			.isEqualTo("OPEN");
		assertThat(story.newTokenFilesKept).isTrue();
		assertThat(story.newTokenAfterTheOldClose).isEqualTo(200);
		assertThat(jdbc.queryForList("SELECT simulation_id FROM command_result WHERE idempotency_key = ?", UUID.class,
				story.closeKey)).as("answered once, in the simulation it closed").containsExactly(story.alpha.simulation());
		assertThat(jdbc.queryForList("SELECT simulation_id FROM audit_log WHERE correlation_id = ?", UUID.class,
				story.closeKey)).containsExactly(story.alpha.simulation());
		// Béta was then closed by a close of its own, which found everything still in place.
		assertThat(story.betaClosed.json().get("canceledOrders").asInt()).isEqualTo(3);
		assertThat(story.betaClosed.json().get("endedRuns").asInt()).isEqualTo(1);
	}

	/**
	 * Balances and holds are each simulation's own. Alpha's accounts hold exactly what they held when Alpha closed,
	 * through everything Béta did with the same traders and symbols; Béta's hold what Béta's setup and its one trade
	 * give; nothing is held in either; and each simulation reconciles on its own.
	 */
	@Test
	void balancesAndHoldsAreKeptApartAndEachSimulationReconciles() {
		UUID alpha = story.alpha.simulation();
		UUID beta = story.beta.simulation();
		assertThat(balances(alpha)).isEqualTo(story.alphaBalances);
		assertThat(balances(beta)).isEqualTo(story.betaBalances);
		Balances alphaTrader = story.alphaBalances.get(story.alpha.accounts().get("trader"));
		// 100,000.00 and 500 NOVA, less three bought at 100.00.
		assertThat(alphaTrader.cash()).isEqualTo(10_000_000 - 3 * 10_000);
		assertThat(alphaTrader.shares()).containsEntry("NOVA", 503L).containsEntry("ORBT", 500L);
		Balances betaTrader = story.betaBalances.get(story.beta.accounts().get("trader"));
		// 50,000.00 and 200 NOVA, less one bought at 101.00.
		assertThat(betaTrader.cash()).isEqualTo(5_000_000 - 10_100);
		assertThat(betaTrader.shares()).containsEntry("NOVA", 201L).containsEntry("ORBT", 200L);
		for (Map<UUID, Balances> closed : List.of(story.alphaBalances, story.betaBalances)) {
			assertThat(closed.values()).allSatisfy((balances) -> {
				assertThat(balances.cashReserved()).isZero();
				assertThat(balances.sharesReserved().values()).containsOnly(0L);
			});
		}
		assertThat(story.alphaBalances.keySet()).doesNotContainAnyElementsOf(story.betaBalances.keySet());
		for (UUID simulation : jdbc.queryForList("SELECT id FROM simulation", UUID.class)) {
			assertThat(reconciliationProblems(simulation)).as("simulation " + simulation).isEmpty();
		}
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM orders WHERE state IN ('OPEN', 'PARTIALLY_FILLED')""", Long.class)).isZero();
	}

	/**
	 * The third start, after the second close: both closed simulations are retired at epoch 0, and the writer's epoch
	 * moved on to the third.
	 */
	@Test
	void aThirdSimulationStartsAfterTheSecondCloses() {
		assertThat(story.betaClosed.status()).as(story.betaClosed.body()).isEqualTo(200);
		assertThat(story.betaClosed.json().get("canceledOrders").asInt()).isEqualTo(3);
		assertThat(story.betaClosed.json().get("endedRuns").asInt()).isEqualTo(1);
		assertThat(story.gammaStarted.status()).as(story.gammaStarted.body()).isEqualTo(201);
		assertThat(story.gammaStarted.json().get("commandSequence").asString()).isEqualTo("1");
		assertThat(story.epochs).containsOnly(Map.entry("Alpha", 0L), Map.entry("Béta run", 0L),
				Map.entry("Gamma", story.alphaEpoch));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM simulation WHERE state <> 'CLOSED'", Long.class)).isEqualTo(1);
	}

	/**
	 * The backup manifest's figures are the current simulation's, and named so. With two simulations they are not the
	 * largest of each: Alpha has more commands, more trades and a higher trade sequence than Béta, and while Béta is
	 * the current one the manifest gives Béta's. Only the count of simulations and of outbox events, which a restore
	 * republishes whole, are the database's.
	 */
	@Test
	void theBackupManifestGivesTheCurrentSimulationsFigures() {
		assertThat(story.manifestEmpty).containsEntry("simulations", "0")
			.containsEntry("current_simulation", "")
			.containsEntry("current_simulation_name", "")
			.containsEntry("current_simulation_state", "")
			.containsEntry("current_simulation_last_command_sequence", "0")
			.containsEntry("current_simulation_trades", "0")
			.containsEntry("current_simulation_last_trade_sequence", "0")
			.containsEntry("outbox_events", "0");
		assertThat(story.manifestEmpty.get("created_at")).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
		assertThat(Integer.parseInt(story.manifestEmpty.get("schema_version"))).isGreaterThanOrEqualTo(37);
		assertThat(story.manifestEmpty.keySet()).as("no figure under a name that reads as the whole database's")
			.containsExactly("created_at", "schema_version", "simulations", "current_simulation",
					"current_simulation_name", "current_simulation_state", "current_simulation_last_command_sequence",
					"current_simulation_trades", "current_simulation_last_trade_sequence", "outbox_events");

		// A closed simulation with none after it is still the current one.
		assertThat(story.manifestAlphaClosed).containsEntry("simulations", "1")
			.containsEntry("current_simulation", story.alpha.simulation().toString())
			.containsEntry("current_simulation_name", "Alpha")
			.containsEntry("current_simulation_state", "CLOSED")
			.containsEntry("current_simulation_last_command_sequence", Long.toString(story.alphaSequenceClosed))
			.containsEntry("current_simulation_trades", "2")
			.containsEntry("current_simulation_last_trade_sequence", "2");

		assertThat(story.betaSequence).as("Alpha took more commands than Béta").isLessThan(story.alphaSequence);
		long betaAtManifest = Long.parseLong(story.manifestBetaOpen.get("current_simulation_last_command_sequence"));
		assertThat(betaAtManifest).isLessThan(story.alphaSequence).isLessThan(story.betaSequence).isPositive();
		assertThat(story.manifestBetaOpen).containsEntry("simulations", "2")
			.containsEntry("current_simulation", story.beta.simulation().toString())
			// Plain text for both scripts: a character outside ASCII is shown as a question mark.
			.containsEntry("current_simulation_name", "B?ta run")
			.containsEntry("current_simulation_state", "OPEN")
			.containsEntry("current_simulation_trades", "1")
			.containsEntry("current_simulation_last_trade_sequence", "1")
			.containsEntry("outbox_events", Long.toString(story.outboxAtBetaOpen));
		assertThat(story.outboxAtBetaOpen).as("every simulation's events")
			.isGreaterThan(Long.parseLong(story.manifestAlphaClosed.get("outbox_events")));

		// The backup someone takes before they upgrade runs this script against a database whose simulations have no
		// name yet. It gives the same figures there, and no name.
		Map<String, String> named = new LinkedHashMap<>(story.manifestBetaOpen);
		named.put("current_simulation_name", "");
		named.remove("created_at");
		Map<String, String> unnamed = new LinkedHashMap<>(story.manifestBeforeNames);
		unnamed.remove("created_at");
		assertThat(unnamed).isEqualTo(named);

		assertThat(story.manifestGamma).containsEntry("simulations", "3")
			.containsEntry("current_simulation_name", "Gamma")
			.containsEntry("current_simulation_state", "SETUP")
			.containsEntry("current_simulation_last_command_sequence", "1")
			.containsEntry("current_simulation_trades", "0")
			.containsEntry("current_simulation_last_trade_sequence", "0");
	}

	/**
	 * A process that starts with two closed simulations and a newer one in SETUP loads the newer one only: it takes
	 * that one's epoch and leaves the closed ones at 0, takes no command, and goes on setting the new one up.
	 */
	@Test
	void aRestartLoadsTheNewestSimulationAndLeavesTheClosedOnesRetired() throws Exception {
		UUID gamma = UUID.fromString(story.gammaStarted.json().get("simulationId").asString());
		long epoch = jdbc.queryForObject("SELECT writer_epoch FROM simulation WHERE id = ?", Long.class, gamma);
		long sequence = jdbc.queryForObject("SELECT last_command_sequence FROM simulation WHERE id = ?", Long.class, gamma);

		engine.start();

		assertThat(engine.simulationId()).isEqualTo(gamma);
		assertThat(engine.simulationIdUnlessClosed()).isEqualTo(gamma);
		assertThat(jdbc.queryForObject("SELECT writer_epoch FROM simulation WHERE id = ?", Long.class, gamma))
			.isEqualTo(epoch + 1);
		assertThat(jdbc.queryForList("SELECT writer_epoch FROM simulation WHERE state = 'CLOSED'", Long.class))
			.containsExactly(0L, 0L);
		assertThat(jdbc.queryForObject("SELECT last_command_sequence FROM simulation WHERE id = ?", Long.class, gamma))
			.as("a start takes no command")
			.isEqualTo(sequence);
		HttpResponse<String> added = operator.post("/api/v1/admin/instruments",
				"{\"symbol\":\"NOVA\",\"referencePrice\":\"30.00\"}", "Idempotency-Key", UUID.randomUUID().toString());
		assertThat(added.statusCode()).as("the symbol is free a third time: " + added.body()).isEqualTo(201);
		assertThat(JSON.readTree(added.body()).get("commandSequence").asLong()).isEqualTo(sequence + 1);
		assertThat(JSON.readTree(new TestBrowser(port).get("/api/v1/market").body()).get("simulationId").asString())
			.isEqualTo(gamma.toString());
		assertThat(jdbc.queryForObject("SELECT last_command_sequence FROM simulation WHERE id = ?", Long.class,
				story.beta.simulation())).isEqualTo(story.betaSequence);
	}

	/** The whole story, through the API, in the order an operator and two traders would live it. */
	private void tell(Story s) throws Exception {
		TestBrowser anonymous = new TestBrowser(port);
		s.operatorId = jdbc.queryForObject("SELECT id FROM app_user WHERE username = 'operator'", UUID.class);
		s.manifestEmpty = manifest();
		s.statusBefore = answer(anonymous.get("/api/v1/market"));

		// Before any market: what a start refuses without committing anything.
		Map<String, String> bad = new LinkedHashMap<>();
		bad.put("empty", "{\"name\":\"\"}");
		bad.put("spaces only", "{\"name\":\"   \"}");
		bad.put("81 characters", "{\"name\":\"" + "x".repeat(81) + "\"}");
		bad.put("a control character", "{\"name\":\"two\\nlines\"}");
		bad.put("no name", "{}");
		bad.put("another field", "{\"name\":\"Alpha\",\"template\":\"none\"}");
		for (Map.Entry<String, String> request : bad.entrySet()) {
			s.badRequests.put(request.getKey(), answer(start(operator, request.getValue(), UUID.randomUUID())));
		}
		s.keyless = answer(operator.post(START, "{\"name\":\"Alpha\"}"));
		TestBrowser trader = TestBrowser.loggedIn(port, "trader", PASSWORD);
		s.traderStarts = start(trader, "{\"name\":\"Alpha\"}", UUID.randomUUID()).statusCode();
		s.anonymousStarts = start(anonymous, "{\"name\":\"Alpha\"}", UUID.randomUUID()).statusCode();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM simulation", Long.class)).isZero();

		// Alpha: started by name with no market at all.
		s.alphaKey = UUID.randomUUID();
		s.alphaStarted = answer(start(operator, "{\"name\":\"  Alpha \\t\"}", s.alphaKey));
		UUID alpha = UUID.fromString(s.alphaStarted.json().get("simulationId").asString());
		s.alphaAgain = answer(start(operator, "{\"name\":\"Alpha\"}", s.alphaKey));
		s.alphaConflict = answer(start(operator, "{\"name\":\"Another\"}", s.alphaKey));
		s.statusAlphaSetup = answer(anonymous.get("/api/v1/market"));
		s.setupKey = UUID.randomUUID();
		s.refusedInSetup = answer(start(operator, "{\"name\":\"Too early\"}", s.setupKey));
		created(operator, "/api/v1/admin/traders",
				"{\"username\":\"second\",\"password\":\"%s\"}".formatted(PASSWORD));
		s.alpha = setUp(alpha, "100000.00", 500, 2, 1);
		TestBrowser second = TestBrowser.loggedIn(port, "second", PASSWORD);
		s.openKey = UUID.randomUUID();
		s.refusedOpen = answer(start(operator, "{\"name\":\"Too early\"}", s.openKey));

		String nova = s.alpha.instruments().get("NOVA");
		String orbt = s.alpha.instruments().get("ORBT");
		String[] maker = bearer(s.alpha.botTokens().get("MARKET_MAKER"));
		// NOVA trades twice: second offers 6, the trader takes 2 and then 1, so 3 rest on a partially filled order.
		placed(second, limit(nova, "SELL", 6, "100.00"));
		placed(trader, limit(nova, "BUY", 2, "100.00"));
		placed(trader, limit(nova, "BUY", 1, "100.00"));
		s.sharedKey = UUID.randomUUID();
		s.sharedBody = limit(nova, "BUY", 5, "90.00");
		s.alphaShared = answer(trader.post(PLACE, s.sharedBody, "Idempotency-Key", s.sharedKey.toString()));
		placed(new TestBrowser(port), limit(nova, "BUY", 3, "95.00"), maker);
		placed(second, limit(orbt, "BUY", 3, "42.00"));
		ok(operator.post("/api/v1/admin/market/halt", "", "Idempotency-Key", UUID.randomUUID().toString()));
		s.refusedHalted = answer(start(operator, "{\"name\":\"Too early\"}", UUID.randomUUID()));
		ok(operator.post("/api/v1/admin/market/resume", "", "Idempotency-Key", UUID.randomUUID().toString()));
		CompletableFuture<Void> stream = stream(trader);

		s.closeKey = UUID.randomUUID();
		s.alphaClosed = answer(operator.post(CLOSE, "", "Idempotency-Key", s.closeKey.toString()));
		assertThat(s.alphaClosed.status()).as(s.alphaClosed.body()).isEqualTo(200);
		// A restart on the closed market, as a stack stopped for the night has: the writer's epoch moves on.
		long alphaFirstEpoch = epoch(alpha);
		engine.start();
		s.alphaEpoch = epoch(alpha);
		assertThat(s.alphaEpoch).isEqualTo(alphaFirstEpoch + 1);
		s.alphaSequenceClosed = sequence(alpha);
		s.alphaBalances = balances(alpha);
		s.manifestAlphaClosed = manifest();
		s.takenKey = UUID.randomUUID();
		s.nameTaken = answer(start(operator, "{\"name\":\"ALPHA\"}", s.takenKey));
		s.alphaSequence = sequence(alpha);
		s.streamOpen = !stream.isDone();

		// Béta: started after the close, with the same symbols and the same two traders, and other amounts.
		s.betaKey = UUID.randomUUID();
		s.betaStarted = answer(start(operator, "{\"name\":\"Béta run\"}", s.betaKey));
		assertThat(s.betaStarted.status()).as(s.betaStarted.body()).isEqualTo(201);
		UUID beta = UUID.fromString(s.betaStarted.json().get("simulationId").asString());
		s.alphaEpochRetired = epoch(alpha);
		s.betaEpoch = epoch(beta);
		s.betaAgain = answer(start(operator, "{\"name\":\"Béta run\"}", s.betaKey));
		s.refusedOpenAgain = answer(start(operator, "{\"name\":\"Too early\"}", s.openKey));
		s.sequencesAfterRetries = List.of(sequence(alpha), sequence(beta));
		try {
			stream.get(5, TimeUnit.SECONDS);
			s.streamEnded = true;
		}
		catch (java.util.concurrent.TimeoutException ex) {
			s.streamEnded = false;
		}

		s.statusBetaSetup = answer(anonymous.get("/api/v1/market"));
		String oldOrder = s.alphaShared.json().get("orderId").asString();
		s.unfunded.put("the portfolio", answer(trader.get("/api/v1/portfolio")));
		s.unfunded.put("the orders", answer(trader.get("/api/v1/orders")));
		s.unfunded.put("an old order", answer(trader.get("/api/v1/orders/" + oldOrder)));
		s.unfunded.put("an order", answer(trader.post(PLACE, s.sharedBody, "Idempotency-Key", UUID.randomUUID().toString())));
		s.unfunded.put("a key's answer", answer(trader.get("/api/v1/commands/" + s.sharedKey)));
		s.unfunded.put("the stream", answer(trader.get("/api/v1/account-events")));
		s.accountsListed = JSON.readTree(operator.get("/api/v1/admin/accounts?limit=200").body()).get("accounts").size();
		s.botsListed = JSON.readTree(operator.get("/api/v1/admin/bots").body()).get("population").asInt();
		s.runsListed = JSON.readTree(operator.get("/api/v1/admin/bot-runs").body()).get("runs").size();
		s.fundedBefore = funded();
		TestBrowser oldBot = new TestBrowser(port);
		s.oldToken.put("a read", oldBot.get("/api/v1/portfolio", maker).statusCode());
		s.oldToken.put("an order", oldBot.post(PLACE, limit(nova, "BUY", 1, "90.00"), "Authorization", maker[1],
				"Idempotency-Key", UUID.randomUUID().toString()).statusCode());
		TestBrowser simulator = new TestBrowser(port);
		s.assignmentEmpty = JSON.readTree(simulator.get("/internal/simulator/assignment", bearer(SIMULATOR_TOKEN)).body())
			.get("run")
			.isNull();
		s.oldHeartbeat = answer(heartbeat(simulator, s.alpha.run(), "worker-a"));
		s.oldRun = answer(operator.get("/api/v1/admin/bot-runs/" + s.alpha.run()));
		s.oldRevoke = answer(operator.post("/api/v1/admin/bots/" + s.alpha.accounts().get("bot-0001") + "/revoke-token", "",
				"Idempotency-Key", UUID.randomUUID().toString()));
		boolean gone = true;
		for (String instrument : s.alpha.instruments().values()) {
			try {
				engine.depth(UUID.fromString(instrument), Side.BUY).get(5, TimeUnit.SECONDS);
				gone = false;
			}
			catch (ExecutionException ex) {
				// No book: the engine has nothing to read the depth from.
			}
		}
		s.oldBooksGone = gone;

		s.secondAddedAgain = answer(operator.post("/api/v1/admin/traders",
				"{\"username\":\"second\",\"password\":\"%s\"}".formatted(PASSWORD), "Idempotency-Key",
				UUID.randomUUID().toString()));
		s.beta = setUp(beta, "50000.00", 200, 1, 1);
		s.fundedAfter = funded();
		s.traderFunded = answer(trader.get("/api/v1/portfolio"));
		s.oldOrderRead = answer(trader.get("/api/v1/orders/" + oldOrder));
		s.oldOrderCancel = answer(trader.post("/api/v1/orders/" + oldOrder + "/cancel", "", "Idempotency-Key",
				UUID.randomUUID().toString()));
		s.newTokenFilesExist = s.beta.tokenFiles().stream().allMatch(Files::exists);
		s.assignmentBeta = answer(simulator.get("/internal/simulator/assignment", bearer(SIMULATOR_TOKEN)));
		String betaNova = s.beta.instruments().get("NOVA");
		placed(second, limit(betaNova, "SELL", 4, "101.00"));
		s.betaTrade = answer(trader.post(PLACE, limit(betaNova, "BUY", 1, "101.00"), "Idempotency-Key",
				UUID.randomUUID().toString()));
		// The trader's key from Alpha, with the same body: the body names Alpha's NOVA, which this market does not have.
		s.betaShared = answer(trader.post(PLACE, limit(betaNova, "BUY", 5, "90.00"), "Idempotency-Key",
				s.sharedKey.toString()));
		s.betaLookup = answer(trader.get("/api/v1/commands/" + s.sharedKey));
		placed(new TestBrowser(port), limit(betaNova, "BUY", 2, "95.00"), bearer(s.beta.botTokens().get("MARKET_MAKER")));
		s.oldTokenWhileOpen = oldBot.get("/api/v1/portfolio", maker).statusCode();
		// The key that closed Alpha, sent again while Béta trades, as a close without its answer is asked for.
		long betaBefore = sequence(beta);
		s.alphaClosedAgain = answer(operator.post(CLOSE, "", "Idempotency-Key", s.closeKey.toString()));
		s.sequencesTakenByTheOldClose = sequence(beta) - betaBefore;
		s.newTokenFilesKept = s.beta.tokenFiles().stream().allMatch(Files::exists);
		s.newTokenAfterTheOldClose = new TestBrowser(port)
			.get("/api/v1/portfolio", bearer(s.beta.botTokens().get("MARKET_MAKER")))
			.statusCode();
		s.statusBetaOpen = answer(anonymous.get("/api/v1/market"));
		s.manifestBetaOpen = manifest();
		s.manifestBeforeNames = manifestBeforeNames();
		s.outboxAtBetaOpen = jdbc.queryForObject("SELECT count(*) FROM outbox", Long.class);

		s.betaClosed = answer(operator.post(CLOSE, "", "Idempotency-Key", UUID.randomUUID().toString()));
		s.betaSequence = sequence(beta);
		s.betaBalances = balances(beta);

		// Gamma: the third.
		s.gammaKey = UUID.randomUUID();
		s.gammaStarted = answer(start(operator, "{\"name\":\"Gamma\"}", s.gammaKey));
		s.statusGamma = answer(anonymous.get("/api/v1/market"));
		s.epochs = new TreeMap<>();
		jdbc.query("SELECT name, writer_epoch FROM simulation", (row) -> {
			s.epochs.put(row.getString(1), row.getLong(2));
		});
		s.manifestGamma = manifest();
		s.gammaAccounts = JSON.readTree(operator.get("/api/v1/admin/accounts?limit=200").body()).get("accounts").size();
	}

	/**
	 * Sets the current simulation up through the operator API and opens it: NOVA and ORBT, the trader and the second
	 * trader funded by their logins with this cash and this many shares of each, bots, and a run with a heartbeat.
	 */
	private Setup setUp(UUID simulation, String cash, int shares, int makers, int noise) throws Exception {
		Map<String, String> instruments = new LinkedHashMap<>();
		Answer firstInstrument = null;
		for (String[] listed : new String[][] { { "NOVA", "100.00" }, { "ORBT", "50.00" } }) {
			HttpResponse<String> added = operator.post("/api/v1/admin/instruments",
					"{\"symbol\":\"%s\",\"referencePrice\":\"%s\"}".formatted(listed[0], listed[1]), "Idempotency-Key",
					UUID.randomUUID().toString());
			assertThat(added.statusCode()).as(listed[0] + ": " + added.body()).isEqualTo(201);
			firstInstrument = firstInstrument == null ? answer(added) : firstInstrument;
			instruments.put(listed[0], JSON.readTree(added.body()).get("instrumentId").asString());
		}
		String holdings = "[{\"instrumentId\":\"%s\",\"quantity\":%d},{\"instrumentId\":\"%s\",\"quantity\":%d}]"
			.formatted(instruments.get("NOVA"), shares, instruments.get("ORBT"), shares);
		for (String username : List.of("trader", "second")) {
			created(operator, "/api/v1/admin/accounts",
					"{\"username\":\"%s\",\"cash\":\"%s\",\"holdings\":%s}".formatted(username, cash, holdings));
		}
		for (String[] tagged : new String[][] { { "MARKET_MAKER", Integer.toString(makers) },
				{ "NOISE", Integer.toString(noise) } }) {
			created(operator, "/api/v1/admin/bots",
					"{\"count\":%s,\"cash\":\"10000.00\",\"holdings\":%s,\"strategy\":\"%s\"}".formatted(tagged[1], holdings,
							tagged[0]));
		}
		Map<String, UUID> accounts = new LinkedHashMap<>();
		jdbc.query("""
				SELECT display_name, id FROM account WHERE simulation_id = ? AND kind <> 'ISSUANCE'
				ORDER BY created_at, id""", (row) -> {
			accounts.put(row.getString(1), row.getObject(2, UUID.class));
		}, simulation);
		ok(operator.post("/api/v1/admin/market/open", "", "Idempotency-Key", UUID.randomUUID().toString()));
		UUID run = UUID.fromString(created(operator, "/api/v1/admin/bot-runs",
				"{\"marketMakers\":1,\"noise\":1,\"commandsPerSecond\":\"1.00\"}").get("runId").asString());
		ok(heartbeat(new TestBrowser(port), run, "worker-a"));
		Map<String, String> botTokens = new LinkedHashMap<>();
		List<Path> tokenFiles = new ArrayList<>();
		for (UUID bot : jdbc.queryForList(
				"SELECT id FROM account WHERE simulation_id = ? AND kind = 'BOT' ORDER BY created_at, id", UUID.class,
				simulation)) {
			Path file = tokenDirectory.resolve(bot + ".token");
			tokenFiles.add(file);
			botTokens.put(jdbc.queryForObject("""
					SELECT coalesce((SELECT strategy FROM bot_assignment WHERE run_id = ? AND account_id = ?), 'UNASSIGNED')""",
					String.class, run, bot), Files.readString(file).strip());
		}
		return new Setup(simulation, instruments, firstInstrument, accounts, run, botTokens, tokenFiles);
	}

	/**
	 * The backup manifest's figures as the backup reads them: the script the backup scripts run inside the PostgreSQL
	 * container, run inside this one, which takes its dump and prints its figures from one snapshot.
	 */
	private Map<String, String> manifest() throws Exception {
		Path script = Path.of("../../infra/postgres/backup-execution-db.sh").toAbsolutePath().normalize();
		postgres.copyFileToContainer(MountableFile.forHostPath(script), "/tmp/backup-execution-db.sh");
		// The script signs in as postgres, the stack's superuser; this container names its own otherwise.
		ExecResult role = postgres.execInContainer("psql", "-U", postgres.getUsername(), "-d", "postgres", "-c", """
				DO $$ BEGIN
				    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'postgres') THEN
				        CREATE ROLE postgres SUPERUSER LOGIN;
				    END IF;
				END $$""");
		assertThat(role.getExitCode()).as(role.getStderr()).isZero();
		Map<String, String> figures = figures(inPostgres("sh", "/tmp/backup-execution-db.sh"));
		inPostgres("test", "-s", "/tmp/execution_db.dump");
		return figures;
	}

	/**
	 * The figures the same script prints for a database from before simulations had a name: the dump the last
	 * {@link #manifest()} took, restored beside the real database, with the column dropped. Only the database's name
	 * is changed in the copy of the script that runs there.
	 */
	private Map<String, String> manifestBeforeNames() throws Exception {
		String copy = "execution_db_unnamed";
		inPostgres("psql", "-U", "postgres", "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-c",
				"DROP DATABASE IF EXISTS " + copy, "-c", "CREATE DATABASE " + copy);
		inPostgres("pg_restore", "-U", "postgres", "-d", copy, "--exit-on-error", "/tmp/execution_db.dump");
		inPostgres("psql", "-U", "postgres", "-d", copy, "-v", "ON_ERROR_STOP=1", "-c",
				"ALTER TABLE simulation DROP COLUMN name");
		inPostgres("sh", "-c", "sed 's/execution_db/" + copy + "/g' /tmp/backup-execution-db.sh > /tmp/backup-unnamed.sh");
		return figures(inPostgres("sh", "/tmp/backup-unnamed.sh"));
	}

	/** Runs a command in the PostgreSQL container, and fails with what it wrote if it did. */
	private ExecResult inPostgres(String... command) throws Exception {
		ExecResult ran = postgres.execInContainer(command);
		assertThat(ran.getExitCode()).as("%s: %s", String.join(" ", command), ran.getStderr()).isZero();
		return ran;
	}

	private static Map<String, String> figures(ExecResult ran) {
		Map<String, String> figures = new LinkedHashMap<>();
		for (String line : ran.getStdout().lines().toList()) {
			int equals = line.indexOf('=');
			assertThat(equals).as("a figure and its value: '%s'", line).isPositive();
			assertThat(figures.put(line.substring(0, equals), line.substring(equals + 1))).as(line).isNull();
		}
		return figures;
	}

	/** The trader's private stream, opened now; completes when the server ends it. */
	private CompletableFuture<Void> stream(TestBrowser browser) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/account-events"))
			.header("Accept", "text/event-stream")
			.header("Cookie", browser.cookies.entrySet()
				.stream()
				.map((cookie) -> cookie.getKey() + "=" + cookie.getValue())
				.collect(Collectors.joining("; ")))
			.GET()
			.build();
		HttpResponse<java.util.stream.Stream<String>> response = HttpClient.newHttpClient()
			.send(request, HttpResponse.BodyHandlers.ofLines());
		assertThat(response.statusCode()).isEqualTo(200);
		CompletableFuture<Void> ended = new CompletableFuture<>();
		Thread.ofVirtual().start(() -> {
			try {
				response.body().forEach((line) -> {
				});
			}
			catch (RuntimeException ex) {
				// The connection closed under the reader.
			}
			ended.complete(null);
		});
		return ended;
	}

	/** Each trader login the story uses, and whether it has an account in the current simulation. */
	private Map<String, Boolean> funded() throws Exception {
		Map<String, Boolean> funded = new HashMap<>();
		JSON.readTree(operator.get("/api/v1/admin/traders?limit=200").body())
			.get("traders")
			.forEach((trader) -> funded.put(trader.get("username").asString(), trader.get("funded").asBoolean()));
		return funded;
	}

	/** Every trading account's totals and holds in one simulation, by account. */
	private Map<UUID, Balances> balances(UUID simulation) {
		Map<UUID, Balances> balances = new HashMap<>();
		jdbc.query("""
				SELECT b.account_id, b.total_cents, b.reserved_cents FROM cash_balance b
				JOIN account a ON a.id = b.account_id WHERE a.simulation_id = ? AND a.kind <> 'ISSUANCE'""", (row) -> {
			balances.put(row.getObject(1, UUID.class),
					new Balances(row.getLong(2), row.getLong(3), new TreeMap<>(), new TreeMap<>()));
		}, simulation);
		jdbc.query("""
				SELECT p.account_id, i.symbol, p.total_quantity, p.reserved_quantity FROM position p
				JOIN instrument i ON i.id = p.instrument_id WHERE i.simulation_id = ?""", (row) -> {
			Balances account = balances.get(row.getObject(1, UUID.class));
			account.shares().put(row.getString(2), row.getLong(3));
			account.sharesReserved().put(row.getString(2), row.getLong(4));
		}, simulation);
		return balances;
	}

	/** Each traded instrument of a simulation by symbol: how many trades, and their first and last sequence. */
	private Map<String, String> tradeSequences(UUID simulation) {
		Map<String, String> sequences = new TreeMap<>();
		jdbc.query("""
				SELECT i.symbol, count(*) || ' ' || min(t.trade_sequence) || ' ' || max(t.trade_sequence)
				FROM trades t JOIN instrument i ON i.id = t.instrument_id WHERE t.simulation_id = ? GROUP BY i.symbol""",
				(row) -> {
					sequences.put(row.getString(1), row.getString(2));
				}, simulation);
		return sequences;
	}

	private List<String> reconciliationProblems(UUID simulation) {
		return new TransactionTemplate(transactionManager).execute((status) -> store.reconciliationProblems(simulation));
	}

	private long sequence(UUID simulation) {
		return jdbc.queryForObject("SELECT last_command_sequence FROM simulation WHERE id = ?", Long.class, simulation);
	}

	private long epoch(UUID simulation) {
		return jdbc.queryForObject("SELECT writer_epoch FROM simulation WHERE id = ?", Long.class, simulation);
	}

	private HttpResponse<String> heartbeat(TestBrowser simulator, UUID run, String worker) throws Exception {
		return simulator.post("/internal/simulator/heartbeat", """
				{"runId":"%s","workerId":"%s","placed":0,"canceled":0,"rejected":0,"errors":0,"inFlight":0,
				"stopFailures":0,"stopped":false}""".formatted(run, worker), bearer(SIMULATOR_TOKEN));
	}

	private static HttpResponse<String> start(TestBrowser browser, String body, UUID key) throws Exception {
		return browser.post(START, body, "Idempotency-Key", key.toString());
	}

	private static Answer answer(HttpResponse<String> response) {
		String contentType = response.headers().firstValue("Content-Type").orElse(null);
		String code = null;
		if (response.statusCode() >= 400 && !response.body().isEmpty()) {
			JsonNode problem = JSON.readTree(response.body());
			code = problem.has("code") ? problem.get("code").asString() : null;
		}
		return new Answer(response.statusCode(), code, response.body(), contentType);
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

	private static void ok(HttpResponse<String> response) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
	}

	private static String[] bearer(String token) {
		return new String[] { "Authorization", "Bearer " + token };
	}

	private static String limit(String instrument, String side, long quantity, String price) {
		return """
				{"instrumentId":"%s","side":"%s","type":"LIMIT","timeInForce":"GTC","quantity":%d,"limitPrice":"%s"}"""
			.formatted(instrument, side, quantity, price);
	}

}
