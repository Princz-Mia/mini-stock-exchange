package com.mia_princz.mse.execution.trading.adapter.web;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.mia_princz.mse.execution.trading.adapter.CurrentSimulation;
import com.mia_princz.mse.execution.trading.application.BotRunResult;
import com.mia_princz.mse.execution.trading.application.CommandKey;
import com.mia_princz.mse.execution.trading.application.StartBotRun;
import com.mia_princz.mse.execution.trading.application.StopBotRun;
import com.mia_princz.mse.execution.trading.application.TradingEngine;

/**
 * Operator control of bot runs: start and stop are writer commands with idempotency keys and audit rows, like the
 * market controls; the list shows each run's configuration, what its worker last reported, and a status derived from
 * the worker's lease.
 */
@RestController
@RequestMapping("/api/v1/admin/bot-runs")
class BotRunController {

	static final String START_ROUTE = "POST /api/v1/admin/bot-runs";

	/** Reads stored manifests with a plain mapper, independent of the web layer's settings. */
	private static final JsonMapper STORED = JsonMapper.builder().build();

	static final String STOP_ROUTE = "POST /api/v1/admin/bot-runs/{id}/stop";

	private static final SecureRandom SEEDS = new SecureRandom();

	private final TradingEngine engine;

	private final TradingQueries queries;

	private final JdbcClient jdbc;

	private final Clock clock;

	private final Duration timeout;

	BotRunController(TradingEngine engine, TradingQueries queries, JdbcClient jdbc, Clock clock,
			@Value("${mse.trading.command-timeout:5s}") Duration timeout) {
		this.engine = engine;
		this.queries = queries;
		this.jdbc = jdbc;
		this.clock = clock;
		this.timeout = timeout;
	}

	record Started(String runId, int bots, String commandSequence) {
	}

	record Stopping(String runId, String commandSequence) {
	}

	@PostMapping
	ResponseEntity<Started> start(@RequestHeader(name = "Idempotency-Key", required = false) String keyHeader,
			@RequestBody BotRunBody body, Authentication authentication) {
		UUID key = CommandCalls.idempotencyKey(keyHeader);
		BotRunBody.Valid run = body.validate();
		UUID operator = queries.userId(authentication.getName()).orElseThrow();
		CommandKey commandKey = new CommandKey(operator, key, START_ROUTE, run.hash());
		long seed = run.seed() != null ? run.seed() : SEEDS.nextLong(1L << 48);
		StartBotRun command = new StartBotRun(UUID.randomUUID(), run.marketMakers(), run.noise(), run.momentum(), seed,
				run.commandsPerSecondHundredths(), run.maxOrderQuantity(), run.maxPosition(), run.duration(),
				run.instrumentIds(), operator);
		BotRunResult result = CommandCalls.await(key, timeout, () -> engine.startBotRun(commandKey, command));
		if (result.status() == BotRunResult.Status.STARTED) {
			return ResponseEntity.status(HttpStatus.CREATED)
				.body(new Started(result.runId().toString(), result.bots(), Long.toString(result.commandSequence())));
		}
		throw refusal(result);
	}

	@PostMapping("/{runId}/stop")
	Stopping stop(@RequestHeader(name = "Idempotency-Key", required = false) String keyHeader,
			@PathVariable UUID runId, Authentication authentication) {
		UUID key = CommandCalls.idempotencyKey(keyHeader);
		UUID operator = queries.userId(authentication.getName()).orElseThrow();
		CommandKey commandKey = new CommandKey(operator, key, STOP_ROUTE, CommandCalls.sha256(runId.toString()));
		BotRunResult result = CommandCalls.await(key, timeout,
				() -> engine.stopBotRun(commandKey, new StopBotRun(runId, operator)));
		if (result.status() == BotRunResult.Status.STOPPED) {
			return new Stopping(runId.toString(), Long.toString(result.commandSequence()));
		}
		throw refusal(result);
	}

	private static ApiException refusal(BotRunResult result) {
		ApiException refused = switch (result.status()) {
			case NO_MARKET, NOT_OPEN -> new ApiException(HttpStatus.CONFLICT, "MARKET_NOT_OPEN",
					"Bots run only while the market is open or halted");
			case RUN_ACTIVE -> new ApiException(HttpStatus.CONFLICT, "RUN_ACTIVE",
					"A bot run is already running; stop it first");
			case NOT_ENOUGH_BOTS -> notEnoughBots(result.shortfall());
			case UNKNOWN_INSTRUMENT -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "UNKNOWN_INSTRUMENT",
					"The run names an instrument this market does not have");
			case NOT_FOUND -> new ApiException(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", "No such bot run");
			case NOT_RUNNING -> new ApiException(HttpStatus.CONFLICT, "RUN_NOT_RUNNING", "The run is already stopped");
			case STARTED, STOPPED -> throw new IllegalStateException("Not a refusal: " + result.status());
		};
		return result.commandSequence() > 0 ? refused.with("commandSequence", Long.toString(result.commandSequence()))
				: refused;
	}

	/** Names the strategy that fell short; an answer stored before runs assigned by tag has none to name. */
	private static ApiException notEnoughBots(BotRunResult.Shortfall shortfall) {
		if (shortfall == null) {
			return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "NOT_ENOUGH_BOTS",
					"The run asks for more bots than the market has");
		}
		return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "NOT_ENOUGH_BOTS",
				"The run asks for %s; %d %s tagged".formatted(bots(shortfall.strategy(), shortfall.requested()),
						shortfall.available(), shortfall.available() == 1 ? "is" : "are"))
			.with("shortfall", shortfall);
	}

	/** "1 market maker", "3 noise bots". */
	private static String bots(String strategy, int count) {
		String one = switch (strategy) {
			case "MARKET_MAKER" -> "market maker";
			case "NOISE" -> "noise bot";
			default -> "momentum bot";
		};
		return count + " " + one + (count == 1 ? "" : "s");
	}

	record Instrument(String instrumentId, String symbol) {
	}

	record Strategies(int marketMakers, int noise, int momentum) {
	}

	record Config(String seed, String commandsPerSecond, int maxOrderQuantity, int maxPosition,
			List<Instrument> instruments, Strategies strategies) {
	}

	record Worker(String workerId, Instant heartbeatAt, Instant leaseExpiresAt) {
	}

	record Activity(long placed, long canceled, long rejected, long errors, int inFlight, int stopFailures) {
	}

	/**
	 * The run's commands in the command sequence: from its start to its end. {@code stopSequence} is the operator's
	 * stop command, if there was one; {@code endSequence} stays null until the run is finished, and {@code endedBy}
	 * says how it ended: WORKER, NEXT_START, or NOT_KEPT for a run recorded before windows were kept. {@code endedAt}
	 * is when it ended, set with the end: the worker's confirmation, or the next run's start.
	 */
	record Window(String startSequence, String stopSequence, String endSequence, String endedBy, Instant endedAt) {
	}

	/**
	 * What the worker reported about itself with the run: the first manifest, which never changes, and
	 * {@code reports}, how many different ones came: more than one means the code or settings changed during the run.
	 */
	record Manifest(String version, String workerId, Instant reportedAt, JsonNode runtime, JsonNode strategies,
			JsonNode defaults, int reports) {
	}

	/**
	 * {@code status}: STARTING before a worker has taken it, RUNNING while a worker holds a live lease, STALE when the
	 * lease ran out while it should run, STOPPING until the worker confirms, then STOPPED. {@code manifest} is null
	 * until a worker reports one.
	 */
	record RunView(String runId, String status, String desiredState, Instant startedAt, Instant endsAt,
			Instant stopRequestedAt, Instant stoppedAt, Config config, Worker worker, Activity activity, Window window,
			Manifest manifest) {
	}

	record RunList(List<RunView> runs, String nextCursor) {
	}

	/** A price and where it came from: the newest trade, or the reference price before any trade. */
	record Mark(String price, String source) {
	}

	/** Every trade in the run's window in one of its instruments, by anyone, and the marks at its edges. */
	record InstrumentFigures(String instrumentId, String symbol, long trades, long volume, String notional,
			Mark startMark, Mark endMark) {
	}

	record Refusal(String code, long commands) {
	}

	/**
	 * One strategy over the run's bots: commands answered in the window, refusals by code, what its accepted orders
	 * asked for and what of it filled, its side of the trades, and an estimated P&L (cash moved plus holdings at the end
	 * minus at the start, at the marks).
	 */
	record StrategyFigures(String strategy, int bots, long placements, long cancels, List<Refusal> refusals,
			long placedQuantity, long filledQuantity, long trades, long volume, String notional, String cashChange,
			String holdingsStart, String holdingsEnd, String pnl) {
	}

	record Figures(Instant computedAt, List<InstrumentFigures> instruments, List<StrategyFigures> strategies) {
	}

	record RunDetail(RunView run, Figures figures) {
	}

	/** The newest runs first unless {@code order=oldest}, in keyset pages. */
	@GetMapping
	RunList runs(@RequestParam(defaultValue = "20") int limit, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) String order) {
		Pages.checkLimit(limit);
		PageOrder direction = PageOrder.parse(order, PageOrder.NEWEST);
		Cursors.RunPosition after = cursor == null ? null : Cursors.runs(cursor, direction);
		List<RunView> views = views(null, after, direction, limit + 1);
		if (views.size() <= limit) {
			return new RunList(views, null);
		}
		views = views.subList(0, limit);
		RunView last = views.getLast();
		return new RunList(views, Cursors.runs(new Cursors.RunPosition(last.startedAt(), UUID.fromString(last.runId())),
				direction));
	}

	/**
	 * One run, with its figures once it is finished and they are computed: null until then, and for a run recorded
	 * before windows were kept.
	 */
	@GetMapping("/{runId}")
	RunDetail run(@PathVariable UUID runId) {
		RunView view = views(runId, null, PageOrder.NEWEST, 1).stream()
			.findFirst()
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", "No such bot run"));
		return new RunDetail(view, figures(runId));
	}

	/**
	 * The active simulation's runs in the given order, at most {@code limit}: one if {@code only} is given, else a page
	 * after the cursor.
	 */
	private List<RunView> views(UUID only, Cursors.RunPosition after, PageOrder direction, int limit) {
		Instant now = clock.instant();
		record Row(UUID id, String desired, Instant startedAt, Instant endsAt, Instant stopRequestedAt,
				Instant stoppedAt, long seed, BigDecimal rate, int maxOrderQuantity, int maxPosition, String workerId,
				Instant heartbeatAt, Instant leaseExpiresAt, Activity activity, Window window) {
		}
		List<Row> rows = jdbc.sql("""
				SELECT id, desired_state, started_at, ends_at, stop_requested_at, stopped_at, seed, commands_per_second,
				    max_order_quantity, max_position, worker_id, heartbeat_at, lease_expires_at, placed, canceled,
				    rejected, errors, in_flight, stop_failures, start_sequence, stop_sequence, end_sequence, ended_by,
				    ended_at
				FROM bot_run WHERE simulation_id = %3$s%1$s
				ORDER BY started_at %2$s, id %2$s LIMIT :limit""".formatted(
				only != null ? " AND id = :only"
						: after == null ? "" : " AND (started_at, id) " + direction.after() + " (:afterAt, :afterId)",
				direction.direction(), CurrentSimulation.ID))
			.param("only", only)
			.param("afterAt", after == null ? null : Timestamp.from(after.startedAt()))
			.param("afterId", after == null ? null : after.id())
			.param("limit", limit)
			.query((row, index) -> new Row(row.getObject("id", UUID.class), row.getString("desired_state"),
					instant(row.getTimestamp("started_at")), instant(row.getTimestamp("ends_at")),
					instant(row.getTimestamp("stop_requested_at")), instant(row.getTimestamp("stopped_at")),
					row.getLong("seed"), row.getBigDecimal("commands_per_second"), row.getInt("max_order_quantity"),
					row.getInt("max_position"), row.getString("worker_id"), instant(row.getTimestamp("heartbeat_at")),
					instant(row.getTimestamp("lease_expires_at")),
					new Activity(row.getLong("placed"), row.getLong("canceled"), row.getLong("rejected"),
							row.getLong("errors"), row.getInt("in_flight"), row.getInt("stop_failures")),
					new Window(row.getString("start_sequence"), row.getString("stop_sequence"),
							row.getString("end_sequence"), row.getString("ended_by"), instant(row.getTimestamp("ended_at")))))
			.list();
		if (rows.isEmpty()) {
			return List.of();
		}
		List<UUID> ids = rows.stream().map(Row::id).toList();
		Map<UUID, List<Instrument>> instruments = new HashMap<>();
		jdbc.sql("""
				SELECT r.run_id, r.instrument_id, i.symbol FROM bot_run_instrument r
				JOIN instrument i ON i.id = r.instrument_id WHERE r.run_id IN (:ids) ORDER BY i.symbol""")
			.param("ids", ids)
			.query((row) -> {
				instruments.computeIfAbsent(row.getObject("run_id", UUID.class), (id) -> new ArrayList<>())
					.add(new Instrument(row.getObject("instrument_id", UUID.class).toString(), row.getString("symbol")));
			});
		Map<UUID, int[]> strategies = new HashMap<>();
		jdbc.sql("SELECT run_id, strategy, count(*) AS bots FROM bot_assignment WHERE run_id IN (:ids) GROUP BY 1, 2")
			.param("ids", ids)
			.query((row) -> {
				int[] counts = strategies.computeIfAbsent(row.getObject("run_id", UUID.class), (id) -> new int[3]);
				counts[switch (row.getString("strategy")) {
					case "MARKET_MAKER" -> 0;
					case "NOISE" -> 1;
					default -> 2;
				}] = row.getInt("bots");
			});
		// Each run's first manifest, by when it came, and how many different ones did.
		Map<UUID, Manifest> manifests = new HashMap<>();
		jdbc.sql("""
				SELECT DISTINCT ON (run_id) run_id, manifest::text AS manifest, worker_id, reported_at,
				    count(*) OVER (PARTITION BY run_id) AS reports
				FROM bot_run_manifest WHERE run_id IN (:ids) ORDER BY run_id, reported_at, digest""")
			.param("ids", ids)
			.query((row) -> {
				JsonNode manifest = STORED.readTree(row.getString("manifest"));
				manifests.put(row.getObject("run_id", UUID.class), new Manifest(manifest.get("version").asString(),
						row.getString("worker_id"), instant(row.getTimestamp("reported_at")), manifest.get("runtime"),
						manifest.get("strategies"), manifest.get("defaults"), row.getInt("reports")));
			});
		return rows.stream().map((row) -> {
			int[] counts = strategies.getOrDefault(row.id(), new int[3]);
			return new RunView(row.id().toString(), status(row.desired(), row.endsAt(), row.stoppedAt(), row.workerId(),
					row.leaseExpiresAt(), now), row.desired(), row.startedAt(), row.endsAt(), row.stopRequestedAt(),
					row.stoppedAt(),
					new Config(Long.toString(row.seed()), row.rate().setScale(2).toPlainString(), row.maxOrderQuantity(),
							row.maxPosition(), instruments.getOrDefault(row.id(), List.of()),
							new Strategies(counts[0], counts[1], counts[2])),
					new Worker(row.workerId(), row.heartbeatAt(), row.leaseExpiresAt()), row.activity(), row.window(),
					manifests.get(row.id()));
		}).toList();
	}

	/** The run's stored figures, or null if it has none yet. Amounts are decimals with two places. */
	private Figures figures(UUID runId) {
		Optional<Instant> computed = jdbc.sql("SELECT computed_at FROM bot_run_figures WHERE run_id = ?")
			.param(runId)
			.query((row, index) -> row.getTimestamp(1).toInstant())
			.optional();
		if (computed.isEmpty()) {
			return null;
		}
		List<InstrumentFigures> instruments = jdbc.sql("""
				SELECT f.instrument_id, i.symbol, f.trades, f.volume, f.notional_cents, f.start_mark_cents,
				    f.start_mark_source, f.end_mark_cents, f.end_mark_source
				FROM bot_run_instrument_figures f JOIN instrument i ON i.id = f.instrument_id
				WHERE f.run_id = ? ORDER BY i.symbol""")
			.param(runId)
			.query((row, index) -> new InstrumentFigures(row.getObject(1, UUID.class).toString(), row.getString(2),
					row.getLong(3), row.getLong(4), PortfolioController.cents(row.getBigDecimal(5)),
					new Mark(PortfolioController.cents(BigDecimal.valueOf(row.getLong(6))), row.getString(7)),
					new Mark(PortfolioController.cents(BigDecimal.valueOf(row.getLong(8))), row.getString(9))))
			.list();
		Map<String, List<Refusal>> refusals = new HashMap<>();
		jdbc.sql("SELECT strategy, code, commands FROM bot_run_refusals WHERE run_id = ? ORDER BY strategy, code")
			.param(runId)
			.query((row) -> {
				refusals.computeIfAbsent(row.getString(1), (strategy) -> new ArrayList<>())
					.add(new Refusal(row.getString(2), row.getLong(3)));
			});
		List<StrategyFigures> strategies = jdbc.sql("""
				SELECT strategy, bots, placements, cancels, placed_quantity, filled_quantity, trades, volume,
				    notional_cents, cash_change_cents, holdings_start_cents, holdings_end_cents, pnl_cents
				FROM bot_run_strategy_figures WHERE run_id = ?
				ORDER BY CASE strategy WHEN 'MARKET_MAKER' THEN 0 WHEN 'NOISE' THEN 1 ELSE 2 END""")
			.param(runId)
			.query((row, index) -> new StrategyFigures(row.getString(1), row.getInt(2), row.getLong(3), row.getLong(4),
					refusals.getOrDefault(row.getString(1), List.of()), row.getLong(5), row.getLong(6), row.getLong(7),
					row.getLong(8), PortfolioController.cents(row.getBigDecimal(9)),
					PortfolioController.cents(row.getBigDecimal(10)), PortfolioController.cents(row.getBigDecimal(11)),
					PortfolioController.cents(row.getBigDecimal(12)), PortfolioController.cents(row.getBigDecimal(13))))
			.list();
		return new Figures(computed.get(), instruments, strategies);
	}

	static String status(String desired, Instant endsAt, Instant stoppedAt, String workerId, Instant leaseExpiresAt,
			Instant now) {
		boolean shouldRun = "RUNNING".equals(desired) && (endsAt == null || endsAt.isAfter(now));
		boolean leased = leaseExpiresAt != null && leaseExpiresAt.isAfter(now);
		if (shouldRun) {
			return workerId == null ? "STARTING" : leased ? "RUNNING" : "STALE";
		}
		return stoppedAt != null || workerId == null || !leased ? "STOPPED" : "STOPPING";
	}

	private static Instant instant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}

}
