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
	 * {@code status}: STARTING before a worker has taken it, RUNNING while a worker holds a live lease, STALE when the
	 * lease ran out while it should run, STOPPING until the worker confirms, then STOPPED.
	 */
	record RunView(String runId, String status, String desiredState, Instant startedAt, Instant endsAt,
			Instant stopRequestedAt, Instant stoppedAt, Config config, Worker worker, Activity activity) {
	}

	record RunList(List<RunView> runs, String nextCursor) {
	}

	/** The newest runs first unless {@code order=oldest}, in keyset pages. */
	@GetMapping
	RunList runs(@RequestParam(defaultValue = "20") int limit, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) String order) {
		Pages.checkLimit(limit);
		PageOrder direction = PageOrder.parse(order, PageOrder.NEWEST);
		Cursors.RunPosition after = cursor == null ? null : Cursors.runs(cursor, direction);
		Instant now = clock.instant();
		record Row(UUID id, String desired, Instant startedAt, Instant endsAt, Instant stopRequestedAt,
				Instant stoppedAt, long seed, BigDecimal rate, int maxOrderQuantity, int maxPosition, String workerId,
				Instant heartbeatAt, Instant leaseExpiresAt, Activity activity) {
		}
		List<Row> rows = jdbc.sql("""
				SELECT id, desired_state, started_at, ends_at, stop_requested_at, stopped_at, seed, commands_per_second,
				    max_order_quantity, max_position, worker_id, heartbeat_at, lease_expires_at, placed, canceled,
				    rejected, errors, in_flight, stop_failures
				FROM bot_run%1$s
				ORDER BY started_at %2$s, id %2$s LIMIT :limit""".formatted(
				after == null ? "" : " WHERE (started_at, id) " + direction.after() + " (:afterAt, :afterId)",
				direction.direction()))
			.param("afterAt", after == null ? null : Timestamp.from(after.startedAt()))
			.param("afterId", after == null ? null : after.id())
			.param("limit", limit + 1)
			.query((row, index) -> new Row(row.getObject("id", UUID.class), row.getString("desired_state"),
					instant(row.getTimestamp("started_at")), instant(row.getTimestamp("ends_at")),
					instant(row.getTimestamp("stop_requested_at")), instant(row.getTimestamp("stopped_at")),
					row.getLong("seed"), row.getBigDecimal("commands_per_second"), row.getInt("max_order_quantity"),
					row.getInt("max_position"), row.getString("worker_id"), instant(row.getTimestamp("heartbeat_at")),
					instant(row.getTimestamp("lease_expires_at")),
					new Activity(row.getLong("placed"), row.getLong("canceled"), row.getLong("rejected"),
							row.getLong("errors"), row.getInt("in_flight"), row.getInt("stop_failures"))))
			.list();
		if (rows.isEmpty()) {
			return new RunList(List.of(), null);
		}
		String next = null;
		if (rows.size() > limit) {
			rows = rows.subList(0, limit);
			next = Cursors.runs(new Cursors.RunPosition(rows.getLast().startedAt(), rows.getLast().id()), direction);
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
		return new RunList(rows.stream().map((row) -> {
			int[] counts = strategies.getOrDefault(row.id(), new int[3]);
			return new RunView(row.id().toString(), status(row.desired(), row.endsAt(), row.stoppedAt(), row.workerId(),
					row.leaseExpiresAt(), now), row.desired(), row.startedAt(), row.endsAt(), row.stopRequestedAt(),
					row.stoppedAt(),
					new Config(Long.toString(row.seed()), row.rate().setScale(2).toPlainString(), row.maxOrderQuantity(),
							row.maxPosition(), instruments.getOrDefault(row.id(), List.of()),
							new Strategies(counts[0], counts[1], counts[2])),
					new Worker(row.workerId(), row.heartbeatAt(), row.leaseExpiresAt()), row.activity());
		}).toList(), next);
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
