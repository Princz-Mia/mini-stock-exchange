package com.mia_princz.mse.marketdata.reconciliation;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Checks the candles of one computation epoch against Execution's trade export, for every instrument this service
 * has a snapshot of, or for one. Internal: the proxy never forwards it. Read-only on both sides.
 */
@RestController
@RequestMapping("/internal/reconciliation")
class ReconciliationController {

	private final JdbcClient jdbc;

	private final TradeExportClient export;

	private final long servedEpoch;

	ReconciliationController(JdbcClient jdbc, @Value("${mse.exports.execution-url}") URI execution,
			@Value("${mse.exports.token:}") String token, @Value("${mse.exports.timeout:10s}") Duration timeout,
			@Value("${mse.candles.served-epoch:1}") long servedEpoch) {
		this.jdbc = jdbc;
		this.export = new TradeExportClient(execution, token, timeout);
		this.servedEpoch = servedEpoch;
	}

	record MismatchView(Instant bucketStart, String problem, String detail) {
	}

	record InstrumentReport(String instrumentId, String candlesThroughTradeSequence,
			String exportedThroughTradeSequence, int candlesChecked, boolean reconciled, List<MismatchView> mismatches) {
	}

	record Reconciliation(long computationEpoch, boolean reconciled, List<InstrumentReport> instruments) {
	}

	@GetMapping("/candles")
	ResponseEntity<?> candles(@RequestParam(required = false) UUID instrumentId,
			@RequestParam(required = false) Long epoch) {
		long checked = epoch == null ? servedEpoch : epoch;
		List<UUID> instruments = instrumentId != null ? List.of(instrumentId)
				: jdbc.sql("SELECT instrument_id FROM instrument_projection ORDER BY symbol").query(UUID.class).list();
		List<InstrumentReport> reports = new ArrayList<>();
		try {
			for (UUID instrument : instruments) {
				CandleReconciler.Report report = CandleReconciler.reconcile(instrument, export.trades(instrument),
						candles(instrument, checked));
				reports.add(new InstrumentReport(instrument.toString(),
						Long.toString(report.candlesThroughTradeSequence()),
						Long.toString(report.exportedThroughTradeSequence()), report.candlesChecked(),
						report.reconciled(),
						report.mismatches()
							.stream()
							.map((mismatch) -> new MismatchView(mismatch.bucketStart(), mismatch.problem(),
									mismatch.detail()))
							.toList()));
			}
		}
		catch (TradeExportClient.ExportUnavailableException ex) {
			ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
			problem.setProperty("code", "EXPORT_UNAVAILABLE");
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
		}
		return ResponseEntity.ok(new Reconciliation(checked,
				reports.stream().allMatch(InstrumentReport::reconciled), reports));
	}

	private List<CandleReconciler.Candle> candles(UUID instrument, long epoch) {
		return jdbc.sql("""
				SELECT bucket_start, open_cents, high_cents, low_cents, close_cents, volume, trade_count,
				    notional_cents, source_max_trade_sequence
				FROM candle_1m WHERE instrument_id = ? AND computation_epoch = ? ORDER BY bucket_start""")
			.params(instrument, epoch)
			.query((row, index) -> new CandleReconciler.Candle(row.getTimestamp("bucket_start").toInstant(),
					row.getLong("open_cents"), row.getLong("high_cents"), row.getLong("low_cents"),
					row.getLong("close_cents"), row.getLong("volume"), row.getLong("trade_count"),
					row.getBigDecimal("notional_cents").toBigIntegerExact(), row.getLong("source_max_trade_sequence")))
			.list();
	}

}
