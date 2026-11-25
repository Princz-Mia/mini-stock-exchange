package com.mia_princz.mse.execution.trading.adapter.web;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

import com.mia_princz.mse.execution.trading.adapter.CurrentSimulation;

/**
 * The simulator's control plane: which run to work on, its bots, and its heartbeat. Internal, by the simulator's own
 * token, which can't trade. A run's bots are read in pages, since a run has no size limit; they never change once the
 * run starts, so a worker reads them once. Each names its credential file, never a token.
 * <p>
 * One worker holds a run at a time. A heartbeat takes the run's lease if it is free, its own or lapsed, and renews it
 * for {@code mse.simulator.lease}; totals are cumulative for the run, so a retried heartbeat changes nothing and a
 * worker taking over continues from the totals the assignment shows. Once a run should stop, the worker settles what
 * is in flight and confirms with {@code stopped}, which releases the lease. Heartbeats aren't trading state, so they
 * don't go through the writer: a row lock orders them against each other and against start and stop.
 */
@RestController
@RequestMapping("/internal/simulator")
class SimulatorControlController {

	private static final Pattern WORKER_ID = Pattern.compile("[A-Za-z0-9._:-]{1,100}");

	private static final Set<String> PHASES = Set.of("LOADING", "TRADING");

	/** The most bots one page holds: 10,000 bots are ten pages. */
	static final int MAX_BOTS_PER_PAGE = 1_000;

	private static final String RUN_COLUMNS = """
			id, desired_state, seed, commands_per_second, max_order_quantity, max_position, ends_at, worker_id,
			lease_expires_at, stopped_at, placed, canceled, rejected, errors, paused_stale_data, paused_market_not_open,
			paused_other""";

	private final JdbcClient jdbc;

	private final TransactionTemplate transactions;

	private final Clock clock;

	private final Duration lease;

	SimulatorControlController(JdbcClient jdbc, TransactionTemplate transactions, Clock clock,
			@Value("${mse.simulator.lease:15s}") Duration lease) {
		this.jdbc = jdbc;
		this.transactions = transactions;
		this.clock = clock;
		this.lease = lease;
	}

	record Instrument(String instrumentId, String symbol) {
	}

	/** {@code credentialRef} is the file in the simulator's token directory that holds this bot's token. */
	record Bot(String accountId, String strategy, String credentialRef) {
	}

	record Worker(String workerId, Instant leaseExpiresAt) {
	}

	/** The paused counts are the bots' steps that could place nothing new, by why; see {@link HeartbeatBody}. */
	record Totals(long placed, long canceled, long rejected, long errors, long pausedStaleData,
			long pausedMarketNotOpen, long pausedOther) {
	}

	/**
	 * {@code desiredState} is STOPPED once the run is stopped or its time is up. {@code botCount} is how many bots the
	 * run's pages hold.
	 */
	record RunAssignment(String runId, String desiredState, String seed, String commandsPerSecond,
			int maxOrderQuantity, int maxPosition, Instant endsAt, long leaseSeconds, List<Instrument> instruments,
			int botCount, Worker worker, Totals totals) {
	}

	record BotPage(List<Bot> bots, String nextCursor) {
	}

	record Assignment(RunAssignment run) {
	}

	private record Run(UUID id, String desired, long seed, BigDecimal rate, int maxOrderQuantity, int maxPosition,
			Instant endsAt, String workerId, Instant leaseExpiresAt, Totals totals) {

		boolean shouldRun(Instant now) {
			return "RUNNING".equals(desired) && (endsAt == null || endsAt.isAfter(now));
		}

	}

	/**
	 * A run a worker left without confirming its stop comes first, so its quotes are cancelled before new trading;
	 * then the run that should be running. A run that never had a worker has nothing to settle. Only the runs of a
	 * simulation that is not closed are work: a closed simulation's bots can no longer trade, the current one's
	 * included.
	 */
	@GetMapping("/assignment")
	Assignment assignment() {
		Timestamp now = Timestamp.from(clock.instant());
		String active = "WHERE simulation_id = " + CurrentSimulation.NOT_CLOSED_ID + " AND ";
		Optional<Run> run = jdbc.sql(select(active + """
				stopped_at IS NULL AND worker_id IS NOT NULL AND (desired_state = 'STOPPED' OR ends_at <= ?)
				ORDER BY started_at, id LIMIT 1"""))
			.param(now)
			.query(SimulatorControlController::run)
			.optional()
			.or(() -> jdbc.sql(select(active + "desired_state = 'RUNNING' AND (ends_at IS NULL OR ends_at > ?)"))
				.param(now)
				.query(SimulatorControlController::run)
				.optional());
		return new Assignment(run.map(this::assignment).orElse(null));
	}

	private RunAssignment assignment(Run run) {
		List<Instrument> instruments = jdbc.sql("""
				SELECT r.instrument_id, i.symbol FROM bot_run_instrument r JOIN instrument i ON i.id = r.instrument_id
				WHERE r.run_id = ? ORDER BY i.symbol""")
			.param(run.id())
			.query((row, index) -> new Instrument(row.getObject("instrument_id", UUID.class).toString(),
					row.getString("symbol")))
			.list();
		int bots = jdbc.sql("SELECT count(*) FROM bot_assignment WHERE run_id = ?")
			.param(run.id())
			.query(Integer.class)
			.single();
		return new RunAssignment(run.id().toString(), run.shouldRun(clock.instant()) ? "RUNNING" : "STOPPED",
				Long.toString(run.seed()), run.rate().setScale(2).toPlainString(), run.maxOrderQuantity(),
				run.maxPosition(), run.endsAt(), lease.toSeconds(), instruments, bots,
				new Worker(run.workerId(), run.leaseExpiresAt()), run.totals());
	}

	/**
	 * A page of a run's bots, oldest first, as they were assigned. A cursor belongs to its run, and is refused for
	 * another. Each assignment keeps its account's creation time, so a page is one range of an index, however large
	 * the run.
	 */
	@GetMapping("/runs/{runId}/bots")
	BotPage bots(@PathVariable UUID runId, @RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "1000") int limit) {
		if (limit < 1 || limit > MAX_BOTS_PER_PAGE) {
			throw ApiException.invalid("limit", "OUT_OF_RANGE", "limit must be between 1 and " + MAX_BOTS_PER_PAGE);
		}
		TradingQueries.AccountPosition after = cursor == null ? null : Cursors.assignedBots(cursor, runId);
		boolean known = jdbc.sql("SELECT EXISTS (SELECT 1 FROM bot_run WHERE id = ?)")
			.param(runId)
			.query(Boolean.class)
			.single();
		if (!known) {
			throw new ApiException(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", "No such bot run");
		}
		List<AssignedBot> page = jdbc.sql("""
				SELECT account_id, strategy, account_created_at FROM bot_assignment
				WHERE run_id = :run%s
				ORDER BY account_created_at, account_id LIMIT :limit""".formatted(
				after == null ? "" : " AND (account_created_at, account_id) > (:afterAt, :afterId)"))
			.param("run", runId)
			.param("afterAt", after == null ? null : Timestamp.from(after.createdAt()))
			.param("afterId", after == null ? null : after.id())
			.param("limit", limit + 1)
			.query((row, index) -> new AssignedBot(row.getObject("account_id", UUID.class), row.getString("strategy"),
					row.getTimestamp("account_created_at").toInstant()))
			.list();
		List<Bot> bots = page.stream()
			.limit(limit)
			.map((bot) -> new Bot(bot.accountId().toString(), bot.strategy(), bot.accountId() + ".token"))
			.toList();
		if (page.size() <= limit) {
			return new BotPage(bots, null);
		}
		AssignedBot last = page.get(limit - 1);
		return new BotPage(bots,
				Cursors.assignedBots(new TradingQueries.AccountPosition(last.createdAt(), last.accountId()), runId));
	}

	private record AssignedBot(UUID accountId, String strategy, Instant createdAt) {
	}

	/**
	 * Totals are cumulative for the run. {@code stopped} confirms the stop, with nothing left in flight.
	 * {@code manifest} is what the worker reports about itself ({@link Manifests}), sent until a beat of the run is
	 * held; optional, so a worker that sends none still holds its run.
	 * <p>
	 * {@code phase} says where the worker is with the run, LOADING {@code botsLoaded} of {@code botsTotal} bots, then
	 * TRADING; the paused counts are the bots' steps that could place nothing new because the market data was too old
	 * or not live, the market was not open, or for another reason. All of them are optional, so a worker that knows
	 * nothing of them still holds its run: a beat without a phase leaves the run with none, and a paused count left
	 * out stays as it was.
	 */
	record HeartbeatBody(UUID runId, String workerId, Long placed, Long canceled, Long rejected, Long errors,
			Integer inFlight, Integer stopFailures, Boolean stopped, JsonNode manifest, String phase,
			Integer botsLoaded, Integer botsTotal, Long pausedStaleData, Long pausedMarketNotOpen,
			Long pausedOther) {

		void validate() {
			List<ApiException.FieldError> errors = new ArrayList<>();
			if (runId == null) {
				errors.add(new ApiException.FieldError("runId", "REQUIRED", "runId is required"));
			}
			if (workerId == null || !WORKER_ID.matcher(workerId).matches()) {
				errors.add(new ApiException.FieldError("workerId", "INVALID_VALUE",
						"workerId must be 1 to 100 letters, digits or . _ : -"));
			}
			count("placed", placed, errors);
			count("canceled", canceled, errors);
			count("rejected", rejected, errors);
			count("errors", this.errors, errors);
			count("inFlight", inFlight == null ? null : inFlight.longValue(), errors);
			count("stopFailures", stopFailures == null ? null : stopFailures.longValue(), errors);
			if (Boolean.TRUE.equals(stopped) && inFlight != null && inFlight != 0) {
				errors.add(new ApiException.FieldError("inFlight", "IN_FLIGHT",
						"a stop is confirmed only with nothing in flight"));
			}
			progress(errors);
			optionalCount("pausedStaleData", pausedStaleData, errors);
			optionalCount("pausedMarketNotOpen", pausedMarketNotOpen, errors);
			optionalCount("pausedOther", pausedOther, errors);
			Manifests.check(manifest, errors);
			if (!errors.isEmpty()) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The heartbeat is invalid", errors);
			}
		}

		private static void count(String field, Long value, List<ApiException.FieldError> errors) {
			if (value == null || value < 0) {
				errors.add(new ApiException.FieldError(field, "OUT_OF_RANGE", field + " is required and not negative"));
			}
		}

		private static void optionalCount(String field, Long value, List<ApiException.FieldError> errors) {
			if (value != null && value < 0) {
				errors.add(new ApiException.FieldError(field, "OUT_OF_RANGE", field + " must not be negative"));
			}
		}

		/** A phase comes with both counts, and the counts only with a phase. */
		private void progress(List<ApiException.FieldError> errors) {
			if (phase == null) {
				if (botsLoaded != null || botsTotal != null) {
					errors.add(new ApiException.FieldError("phase", "REQUIRED",
							"phase is required with botsLoaded and botsTotal"));
				}
				return;
			}
			if (!PHASES.contains(phase)) {
				errors.add(new ApiException.FieldError("phase", "INVALID_VALUE", "phase must be LOADING or TRADING"));
			}
			count("botsLoaded", botsLoaded == null ? null : botsLoaded.longValue(), errors);
			count("botsTotal", botsTotal == null ? null : botsTotal.longValue(), errors);
			if (botsLoaded != null && botsTotal != null && botsLoaded > botsTotal) {
				errors.add(new ApiException.FieldError("botsLoaded", "OUT_OF_RANGE",
						"botsLoaded must be at most botsTotal"));
			}
		}

	}

	record Heartbeat(String runId, String desiredState, Instant leaseExpiresAt, boolean stopped) {
	}

	@PostMapping("/heartbeat")
	Heartbeat heartbeat(@RequestBody HeartbeatBody body) {
		body.validate();
		return transactions.execute((status) -> beat(body));
	}

	private Heartbeat beat(HeartbeatBody body) {
		if (body.manifest() != null) {
			// Its size as it would be stored, measured as the table's CHECK measures it, before anything is written:
			// PostgreSQL's text of it is a little longer than what was sent.
			int stored = jdbc.sql("SELECT octet_length(CAST(? AS jsonb)::text)")
				.param(body.manifest().toString())
				.query(Integer.class)
				.single();
			if (stored > Manifests.MAX_BYTES) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The heartbeat is invalid",
						List.of(new ApiException.FieldError("manifest", "TOO_LARGE",
								"manifest must be at most " + Manifests.MAX_BYTES + " bytes as stored")));
			}
		}
		Run run = jdbc.sql(select("WHERE id = ? FOR UPDATE"))
			.param(body.runId())
			.query((row, index) -> {
				if (row.getTimestamp("stopped_at") != null) {
					throw new ApiException(HttpStatus.CONFLICT, "RUN_FINISHED", "The run's stop is already confirmed");
				}
				return run(row, index);
			})
			.optional()
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", "No such bot run"));
		Instant now = clock.instant();
		if (run.workerId() != null && !run.workerId().equals(body.workerId()) && run.leaseExpiresAt() != null
				&& run.leaseExpiresAt().isAfter(now)) {
			throw new ApiException(HttpStatus.CONFLICT, "LEASE_HELD", "Another worker holds this run's lease");
		}
		boolean shouldRun = run.shouldRun(now);
		boolean stopping = Boolean.TRUE.equals(body.stopped());
		if (stopping && shouldRun) {
			throw new ApiException(HttpStatus.CONFLICT, "RUN_NOT_STOPPING", "The run should still be running");
		}
		Instant leaseExpiresAt = stopping ? now : now.plus(lease);
		Timestamp at = Timestamp.from(now);
		// A run that ran out of time is recorded as stopped at its end.
		jdbc.sql("""
				UPDATE bot_run SET worker_id = ?, heartbeat_at = ?, lease_expires_at = ?, placed = GREATEST(placed, ?),
				    canceled = GREATEST(canceled, ?), rejected = GREATEST(rejected, ?), errors = GREATEST(errors, ?),
				    in_flight = ?, stop_failures = ?, phase = ?, bots_loaded = ?, bots_total = ?,
				    paused_stale_data = GREATEST(paused_stale_data, CAST(? AS bigint)),
				    paused_market_not_open = GREATEST(paused_market_not_open, CAST(? AS bigint)),
				    paused_other = GREATEST(paused_other, CAST(? AS bigint)),
				    stopped_at = CASE WHEN ? THEN CAST(? AS timestamptz) END,
				    desired_state = CASE WHEN ? THEN 'STOPPED' ELSE desired_state END,
				    stop_requested_at = CASE WHEN ? THEN coalesce(stop_requested_at, ends_at) ELSE stop_requested_at END
				WHERE id = ?""")
			.params(body.workerId(), at, Timestamp.from(leaseExpiresAt), body.placed(), body.canceled(),
					body.rejected(), body.errors(), body.inFlight(), body.stopFailures(), body.phase(), body.botsLoaded(),
					body.botsTotal(), body.pausedStaleData(), body.pausedMarketNotOpen(), body.pausedOther(), stopping, at,
					stopping, stopping, run.id())
			.update();
		if (body.manifest() != null) {
			// Only past every refusal above, under the run's row lock: the first manifest stays the run's, the same one
			// again adds nothing, and a different one goes beside it, up to eight.
			jdbc.sql("""
					INSERT INTO bot_run_manifest (run_id, manifest, worker_id, reported_at)
					SELECT ?, CAST(? AS jsonb), ?, ?
					WHERE (SELECT count(*) FROM bot_run_manifest WHERE run_id = ?) < 8
					ON CONFLICT DO NOTHING""")
				.params(run.id(), body.manifest().toString(), body.workerId(), at, run.id())
				.update();
		}
		if (stopping) {
			// The confirmed stop ends the run's window at the newest committed command, unless the next start ended it
			// first: nothing is in flight, and the worker hears back only after its commands commit, so every command
			// of the run, its closing cancels included, is at or before it. One statement, so the command and each
			// instrument's newest trade sequence come from one snapshot.
			jdbc.sql("""
					WITH ended AS (
					    UPDATE bot_run b SET ended_by = 'WORKER', end_sequence = s.last_command_sequence,
					        ended_at = CAST(? AS timestamptz)
					    FROM simulation s
					    WHERE b.id = ? AND b.ended_by IS NULL AND s.id = b.simulation_id
					    RETURNING b.id)
					UPDATE bot_run_instrument r SET end_trade_sequence = i.last_trade_sequence
					FROM ended e, instrument i
					WHERE r.run_id = e.id AND i.simulation_id = r.simulation_id AND i.id = r.instrument_id""")
				.params(at, run.id())
				.update();
		}
		return new Heartbeat(run.id().toString(), shouldRun ? "RUNNING" : "STOPPED", leaseExpiresAt, stopping);
	}

	private static Run run(ResultSet row, int index) throws SQLException {
		return new Run(row.getObject("id", UUID.class), row.getString("desired_state"), row.getLong("seed"),
				row.getBigDecimal("commands_per_second"), row.getInt("max_order_quantity"), row.getInt("max_position"),
				instant(row.getTimestamp("ends_at")), row.getString("worker_id"),
				instant(row.getTimestamp("lease_expires_at")), new Totals(row.getLong("placed"), row.getLong("canceled"),
						row.getLong("rejected"), row.getLong("errors"), row.getLong("paused_stale_data"),
						row.getLong("paused_market_not_open"), row.getLong("paused_other")));
	}

	private static String select(String where) {
		return "SELECT " + RUN_COLUMNS + " FROM bot_run " + where;
	}

	private static Instant instant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}

}
