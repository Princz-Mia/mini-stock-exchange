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
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The simulator's control plane: which run to work on, and its heartbeat. Internal, by the simulator's own token,
 * which can't trade. Assignments name each bot's credential file, never a token.
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

	private static final String RUN_COLUMNS = """
			id, desired_state, seed, commands_per_second, max_order_quantity, max_position, ends_at, worker_id,
			lease_expires_at, stopped_at, placed, canceled, rejected, errors""";

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

	record Totals(long placed, long canceled, long rejected, long errors) {
	}

	/** {@code desiredState} is STOPPED once the run is stopped or its time is up. */
	record RunAssignment(String runId, String desiredState, String seed, String commandsPerSecond,
			int maxOrderQuantity, int maxPosition, Instant endsAt, long leaseSeconds, List<Instrument> instruments,
			List<Bot> bots, Worker worker, Totals totals) {
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
	 * then the run that should be running. A run that never had a worker has nothing to settle.
	 */
	@GetMapping("/assignment")
	Assignment assignment() {
		Timestamp now = Timestamp.from(clock.instant());
		Optional<Run> run = jdbc.sql(select("""
				WHERE stopped_at IS NULL AND worker_id IS NOT NULL AND (desired_state = 'STOPPED' OR ends_at <= ?)
				ORDER BY started_at, id LIMIT 1"""))
			.param(now)
			.query(SimulatorControlController::run)
			.optional()
			.or(() -> jdbc.sql(select("WHERE desired_state = 'RUNNING' AND (ends_at IS NULL OR ends_at > ?)"))
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
		List<Bot> bots = jdbc.sql("""
				SELECT b.account_id, b.strategy FROM bot_assignment b JOIN account a ON a.id = b.account_id
				WHERE b.run_id = ? ORDER BY a.created_at, a.id""")
			.param(run.id())
			.query((row, index) -> {
				UUID account = row.getObject("account_id", UUID.class);
				return new Bot(account.toString(), row.getString("strategy"), account + ".token");
			})
			.list();
		return new RunAssignment(run.id().toString(), run.shouldRun(clock.instant()) ? "RUNNING" : "STOPPED",
				Long.toString(run.seed()), run.rate().setScale(2).toPlainString(), run.maxOrderQuantity(),
				run.maxPosition(), run.endsAt(), lease.toSeconds(), instruments, bots,
				new Worker(run.workerId(), run.leaseExpiresAt()), run.totals());
	}

	/** Totals are cumulative for the run. {@code stopped} confirms the stop, with nothing left in flight. */
	record HeartbeatBody(UUID runId, String workerId, Long placed, Long canceled, Long rejected, Long errors,
			Integer inFlight, Integer stopFailures, Boolean stopped) {

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
			if (!errors.isEmpty()) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The heartbeat is invalid", errors);
			}
		}

		private static void count(String field, Long value, List<ApiException.FieldError> errors) {
			if (value == null || value < 0) {
				errors.add(new ApiException.FieldError(field, "OUT_OF_RANGE", field + " is required and not negative"));
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
				    in_flight = ?, stop_failures = ?,
				    stopped_at = CASE WHEN ? THEN CAST(? AS timestamptz) END,
				    desired_state = CASE WHEN ? THEN 'STOPPED' ELSE desired_state END,
				    stop_requested_at = CASE WHEN ? THEN coalesce(stop_requested_at, ends_at) ELSE stop_requested_at END
				WHERE id = ?""")
			.params(body.workerId(), at, Timestamp.from(leaseExpiresAt), body.placed(), body.canceled(),
					body.rejected(), body.errors(), body.inFlight(), body.stopFailures(), stopping, at, stopping,
					stopping, run.id())
			.update();
		return new Heartbeat(run.id().toString(), shouldRun ? "RUNNING" : "STOPPED", leaseExpiresAt, stopping);
	}

	private static Run run(ResultSet row, int index) throws SQLException {
		return new Run(row.getObject("id", UUID.class), row.getString("desired_state"), row.getLong("seed"),
				row.getBigDecimal("commands_per_second"), row.getInt("max_order_quantity"), row.getInt("max_position"),
				instant(row.getTimestamp("ends_at")), row.getString("worker_id"),
				instant(row.getTimestamp("lease_expires_at")), new Totals(row.getLong("placed"), row.getLong("canceled"),
						row.getLong("rejected"), row.getLong("errors")));
	}

	private static String select(String where) {
		return "SELECT " + RUN_COLUMNS + " FROM bot_run " + where;
	}

	private static Instant instant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}

}
