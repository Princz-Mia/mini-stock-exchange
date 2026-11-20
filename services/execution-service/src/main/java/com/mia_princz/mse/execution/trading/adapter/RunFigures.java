package com.mia_princz.mse.execution.trading.adapter;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Each finished bot run's figures, computed once from its window and stored, off the writer: the trades in its
 * instruments, and per strategy its bots' commands, refusals, fills and estimated P&L. A run is finished once its
 * window has an end (V30); one recorded before windows were kept never is.
 * <p>
 * Everything is read in one repeatable-read snapshot. The run's bots may already trade in a later run, so holdings at
 * an edge are today's holdings less what the bots traded since: no balance history is needed, and the writer's tables
 * gain no index.
 */
public final class RunFigures {

	private static final Logger LOG = LoggerFactory.getLogger(RunFigures.class);

	/** Runs computed per pass; more wait for the next. */
	private static final int PASS = 5;

	private static final String PLACE = "POST /api/v1/orders";

	private static final String CANCEL = "POST /api/v1/orders/{id}/cancel";

	private final JdbcClient jdbc;

	private final TransactionTemplate snapshot;

	private final Clock clock;

	RunFigures(JdbcClient jdbc, PlatformTransactionManager transactions, Clock clock) {
		this.jdbc = jdbc;
		this.snapshot = new TransactionTemplate(transactions);
		this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
		this.clock = clock;
	}

	@Scheduled(initialDelayString = "${mse.runs.figures-interval:5s}",
			fixedDelayString = "${mse.runs.figures-interval:5s}")
	void scheduled() {
		try {
			computeFinished();
		}
		catch (RuntimeException ex) {
			LOG.warn("Bot run figures could not be computed; trying again on the next pass", ex);
		}
	}

	/** Computes the figures of finished runs that have none yet, oldest first; returns how many it stored. */
	public int computeFinished() {
		List<UUID> due = jdbc.sql("""
				SELECT r.id FROM bot_run r
				WHERE r.ended_by IN ('WORKER', 'NEXT_START')
				    AND NOT EXISTS (SELECT 1 FROM bot_run_figures f WHERE f.run_id = r.id)
				ORDER BY r.start_sequence LIMIT ?""")
			.param(PASS)
			.query(UUID.class)
			.list();
		int stored = 0;
		for (UUID run : due) {
			try {
				if (Boolean.TRUE.equals(snapshot.execute((status) -> compute(run)))) {
					stored++;
				}
			}
			catch (RuntimeException ex) {
				// One run's failure doesn't hold back the runs after it; it is tried again on the next pass.
				LOG.warn("The figures of bot run {} could not be computed", run, ex);
			}
		}
		return stored;
	}

	private record Window(UUID simulationId, long start, long end) {
	}

	private record Instrument(UUID id, long trades, long volume, BigDecimal notional, long startMark,
			String startSource, long endMark, String endSource) {
	}

	/** One strategy's figures as they are gathered. */
	private static final class Strategy {

		int bots;

		long placements;

		long cancels;

		long placedQuantity;

		long filledQuantity;

		long trades;

		long volume;

		BigDecimal notional = BigDecimal.ZERO;

		BigDecimal cashChange = BigDecimal.ZERO;

		BigDecimal holdingsStart = BigDecimal.ZERO;

		BigDecimal holdingsEnd = BigDecimal.ZERO;

		final Map<String, Long> refusals = new TreeMap<>();

	}

	private boolean compute(UUID runId) {
		Optional<Window> found = jdbc.sql("""
				SELECT simulation_id, start_sequence, end_sequence FROM bot_run
				WHERE id = ? AND ended_by IN ('WORKER', 'NEXT_START')""")
			.param(runId)
			.query((row, index) -> new Window(row.getObject(1, UUID.class), row.getLong(2), row.getLong(3)))
			.optional();
		if (found.isEmpty()) {
			return false;
		}
		Window window = found.get();
		List<Instrument> instruments = instruments(runId);
		Map<UUID, Instrument> byId = new HashMap<>();
		instruments.forEach((instrument) -> byId.put(instrument.id(), instrument));
		Map<String, Strategy> strategies = new TreeMap<>();
		jdbc.sql("SELECT strategy, count(*) FROM bot_assignment WHERE run_id = ? GROUP BY strategy")
			.param(runId)
			.query((row) -> {
				strategies.computeIfAbsent(row.getString(1), (name) -> new Strategy()).bots = row.getInt(2);
			});
		commands(runId, window, strategies);
		jdbc.sql("""
				SELECT a.strategy, sum(o.requested_quantity) FROM orders o
				JOIN bot_assignment a ON a.run_id = ? AND a.account_id = o.account_id
				JOIN bot_run_instrument r ON r.run_id = a.run_id AND r.instrument_id = o.instrument_id
				WHERE o.simulation_id = ? AND o.accepted_sequence BETWEEN ? AND ?
				GROUP BY a.strategy""")
			.params(runId, window.simulationId(), window.start(), window.end())
			.query((row) -> {
				strategies.get(row.getString(1)).placedQuantity = row.getLong(2);
			});
		jdbc.sql("""
				SELECT a.strategy, sum(t.quantity) FROM bot_run_instrument r
				JOIN trades t ON t.simulation_id = r.simulation_id AND t.instrument_id = r.instrument_id
				    AND t.trade_sequence > r.start_trade_sequence AND t.trade_sequence <= r.end_trade_sequence
				JOIN orders o ON o.id IN (t.maker_order_id, t.taker_order_id)
				JOIN bot_assignment a ON a.run_id = r.run_id AND a.account_id = o.account_id
				WHERE r.run_id = ? AND o.accepted_sequence BETWEEN ? AND ?
				GROUP BY a.strategy""")
			.params(runId, window.start(), window.end())
			.query((row) -> {
				strategies.get(row.getString(1)).filledQuantity = row.getLong(2);
			});
		holdingsAndCash(runId, byId, strategies);
		return store(runId, instruments, strategies);
	}

	/** Each instrument's trades in the window and its marks at the edges. */
	private List<Instrument> instruments(UUID runId) {
		return jdbc.sql("""
				SELECT r.instrument_id, count(t.id), coalesce(sum(t.quantity), 0),
				    coalesce(sum(t.price_cents::numeric * t.quantity), 0), i.reference_price_cents,
				    (SELECT s.price_cents FROM trades s WHERE s.simulation_id = r.simulation_id
				        AND s.instrument_id = r.instrument_id AND s.trade_sequence = r.start_trade_sequence),
				    (SELECT e.price_cents FROM trades e WHERE e.simulation_id = r.simulation_id
				        AND e.instrument_id = r.instrument_id AND e.trade_sequence = r.end_trade_sequence)
				FROM bot_run_instrument r
				JOIN instrument i ON i.simulation_id = r.simulation_id AND i.id = r.instrument_id
				LEFT JOIN trades t ON t.simulation_id = r.simulation_id AND t.instrument_id = r.instrument_id
				    AND t.trade_sequence > r.start_trade_sequence AND t.trade_sequence <= r.end_trade_sequence
				WHERE r.run_id = ?
				GROUP BY r.run_id, r.simulation_id, r.instrument_id, r.start_trade_sequence, r.end_trade_sequence,
				    i.reference_price_cents""")
			.param(runId)
			.query((row, index) -> {
				long reference = row.getLong(5);
				Long start = row.getObject(6, Long.class);
				Long end = row.getObject(7, Long.class);
				return new Instrument(row.getObject(1, UUID.class), row.getLong(2), row.getLong(3),
						row.getBigDecimal(4), start != null ? start : reference, start != null ? "LAST_TRADE" : "REFERENCE",
						end != null ? end : reference, end != null ? "LAST_TRADE" : "REFERENCE");
			})
			.list();
	}

	/**
	 * Placements and cancels the run's bots had answered in the window, and the refused ones by the code their answer
	 * carried: a placement's rejection code, or a cancel's ORDER_NOT_CANCELABLE or ORDER_NOT_FOUND.
	 */
	private void commands(UUID runId, Window window, Map<String, Strategy> strategies) {
		jdbc.sql("""
				SELECT a.strategy, c.route,
				    CASE WHEN c.route = ? AND c.response_status = 422 THEN c.response_body ->> 'rejection'
				         WHEN c.route = ? AND c.response_body ->> 'status' IN ('NOT_CANCELABLE', 'NOT_FOUND')
				             THEN 'ORDER_' || (c.response_body ->> 'status') END AS refusal,
				    count(*)
				FROM command_result c JOIN bot_assignment a ON a.run_id = ? AND a.account_id = c.principal_id
				WHERE c.simulation_id = ? AND c.command_sequence BETWEEN ? AND ? AND c.route IN (?, ?)
				GROUP BY 1, 2, 3""")
			.params(PLACE, CANCEL, runId, window.simulationId(), window.start(), window.end(), PLACE, CANCEL)
			.query((row) -> {
				Strategy strategy = strategies.get(row.getString(1));
				long count = row.getLong(4);
				if (PLACE.equals(row.getString(2))) {
					strategy.placements += count;
				}
				else {
					strategy.cancels += count;
				}
				String refusal = row.getString(3);
				if (refusal != null) {
					strategy.refusals.merge(refusal, count, Long::sum);
				}
			});
	}

	/**
	 * The strategy's side of every trade in the window, the cash those trades moved, and its holdings of the run's
	 * instruments at both edges: today's holdings less what its bots traded after each edge.
	 */
	private void holdingsAndCash(UUID runId, Map<UUID, Instrument> instruments, Map<String, Strategy> strategies) {
		record Moved(long sharesAfterStart, long sharesAfterEnd) {
		}
		Map<String, Map<UUID, Moved>> moved = new HashMap<>();
		jdbc.sql("""
				WITH sides AS (
				    SELECT t.instrument_id, t.buyer_account_id AS account_id, t.quantity AS shares,
				        -(t.price_cents::numeric * t.quantity) AS cash, t.quantity, t.price_cents::numeric * t.quantity
				            AS notional, t.trade_sequence <= r.end_trade_sequence AS inside
				    FROM bot_run_instrument r
				    JOIN trades t ON t.simulation_id = r.simulation_id AND t.instrument_id = r.instrument_id
				        AND t.trade_sequence > r.start_trade_sequence
				    WHERE r.run_id = :run
				    UNION ALL
				    SELECT t.instrument_id, t.seller_account_id, -t.quantity, t.price_cents::numeric * t.quantity,
				        t.quantity, t.price_cents::numeric * t.quantity, t.trade_sequence <= r.end_trade_sequence
				    FROM bot_run_instrument r
				    JOIN trades t ON t.simulation_id = r.simulation_id AND t.instrument_id = r.instrument_id
				        AND t.trade_sequence > r.start_trade_sequence
				    WHERE r.run_id = :run)
				SELECT a.strategy, s.instrument_id, coalesce(sum(s.cash) FILTER (WHERE s.inside), 0),
				    count(*) FILTER (WHERE s.inside), coalesce(sum(s.quantity) FILTER (WHERE s.inside), 0),
				    coalesce(sum(s.notional) FILTER (WHERE s.inside), 0), sum(s.shares),
				    coalesce(sum(s.shares) FILTER (WHERE NOT s.inside), 0)
				FROM sides s JOIN bot_assignment a ON a.run_id = :run AND a.account_id = s.account_id
				GROUP BY a.strategy, s.instrument_id""")
			.param("run", runId)
			.query((row) -> {
				Strategy strategy = strategies.get(row.getString(1));
				strategy.cashChange = strategy.cashChange.add(row.getBigDecimal(3));
				strategy.trades += row.getLong(4);
				strategy.volume += row.getLong(5);
				strategy.notional = strategy.notional.add(row.getBigDecimal(6));
				moved.computeIfAbsent(row.getString(1), (name) -> new HashMap<>())
					.put(row.getObject(2, UUID.class), new Moved(row.getLong(7), row.getLong(8)));
			});
		jdbc.sql("""
				SELECT a.strategy, r.instrument_id, coalesce(sum(p.total_quantity), 0)
				FROM bot_assignment a JOIN bot_run_instrument r ON r.run_id = a.run_id
				LEFT JOIN position p ON p.account_id = a.account_id AND p.instrument_id = r.instrument_id
				WHERE a.run_id = ?
				GROUP BY a.strategy, r.instrument_id""")
			.param(runId)
			.query((row) -> {
				Strategy strategy = strategies.get(row.getString(1));
				UUID instrumentId = row.getObject(2, UUID.class);
				Instrument instrument = instruments.get(instrumentId);
				Moved since = moved.getOrDefault(row.getString(1), Map.of()).getOrDefault(instrumentId, new Moved(0, 0));
				long now = row.getLong(3);
				strategy.holdingsStart = strategy.holdingsStart
					.add(BigDecimal.valueOf(now - since.sharesAfterStart()).multiply(BigDecimal.valueOf(instrument.startMark())));
				strategy.holdingsEnd = strategy.holdingsEnd
					.add(BigDecimal.valueOf(now - since.sharesAfterEnd()).multiply(BigDecimal.valueOf(instrument.endMark())));
			});
	}

	/** Stores the figures, unless another pass stored this run's first. */
	private boolean store(UUID runId, List<Instrument> instruments, Map<String, Strategy> strategies) {
		if (jdbc.sql("INSERT INTO bot_run_figures (run_id, computed_at) VALUES (?, ?) ON CONFLICT DO NOTHING")
			.params(runId, Timestamp.from(clock.instant()))
			.update() == 0) {
			return false;
		}
		for (Instrument instrument : instruments) {
			jdbc.sql("""
					INSERT INTO bot_run_instrument_figures (run_id, instrument_id, trades, volume, notional_cents,
					    start_mark_cents, start_mark_source, end_mark_cents, end_mark_source)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
				.params(runId, instrument.id(), instrument.trades(), instrument.volume(), instrument.notional(),
						instrument.startMark(), instrument.startSource(), instrument.endMark(), instrument.endSource())
				.update();
		}
		List<Object[]> refusals = new ArrayList<>();
		strategies.forEach((name, strategy) -> {
			BigDecimal pnl = strategy.cashChange.add(strategy.holdingsEnd).subtract(strategy.holdingsStart);
			jdbc.sql("""
					INSERT INTO bot_run_strategy_figures (run_id, strategy, bots, placements, cancels, placed_quantity,
					    filled_quantity, trades, volume, notional_cents, cash_change_cents, holdings_start_cents,
					    holdings_end_cents, pnl_cents)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
				.params(runId, name, strategy.bots, strategy.placements, strategy.cancels, strategy.placedQuantity,
						strategy.filledQuantity, strategy.trades, strategy.volume, strategy.notional,
						strategy.cashChange, strategy.holdingsStart, strategy.holdingsEnd, pnl)
				.update();
			strategy.refusals.forEach((code, commands) -> refusals.add(new Object[] { name, code, commands }));
		});
		for (Object[] refusal : refusals) {
			jdbc.sql("INSERT INTO bot_run_refusals (run_id, strategy, code, commands) VALUES (?, ?, ?, ?)")
				.params(runId, refusal[0], refusal[1], refusal[2])
				.update();
		}
		return true;
	}

}
