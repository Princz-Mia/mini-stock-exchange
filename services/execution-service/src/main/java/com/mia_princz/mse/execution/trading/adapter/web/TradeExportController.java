package com.mia_princz.mse.execution.trading.adapter.web;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every trade of one instrument, in sequence order, for other services to rebuild or check their projections from
 * the authoritative record. Internal: the proxy never forwards it, and only the service token opens it.
 * <p>
 * The first page captures the instrument's newest trade sequence as {@code throughSequence}; passing it back with
 * each cursor keeps the export to that fixed set while new trades arrive. Rows carry the public facts only: no order,
 * account or owner. Reads never go through the writer.
 */
@RestController
@RequestMapping("/internal/exports")
class TradeExportController {

	static final int DEFAULT_LIMIT = 500;

	static final int MAX_LIMIT = 1_000;

	private final JdbcClient jdbc;

	TradeExportController(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Integer cents and exact quantities: a machine reads this, and checks sums against it. */
	record ExportedTrade(String tradeId, String tradeSequence, long priceCents, long quantity, Instant executedAt,
			String commandSequence) {
	}

	/** {@code nextCursor} is null on the last page up to {@code throughSequence}. */
	record TradeExport(String instrumentId, String symbol, String throughSequence, List<ExportedTrade> trades,
			String nextCursor) {
	}

	private record Instrument(UUID simulationId, String symbol, long lastTradeSequence) {
	}

	private record Row(UUID id, long sequence, long priceCents, long quantity, Instant executedAt,
			long commandSequence) {
	}

	@GetMapping("/trades")
	TradeExport trades(@RequestParam UUID instrumentId, @RequestParam(required = false) Long throughSequence,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "500") int limit) {
		if (limit < 1 || limit > MAX_LIMIT) {
			throw ApiException.invalid("limit", "OUT_OF_RANGE", "limit must be between 1 and " + MAX_LIMIT);
		}
		if (throughSequence != null && throughSequence < 0) {
			throw ApiException.invalid("throughSequence", "OUT_OF_RANGE", "throughSequence must not be negative");
		}
		long after = cursor == null ? 0 : decodeCursor(cursor);
		Instrument instrument = jdbc.sql("SELECT simulation_id, symbol, last_trade_sequence FROM instrument WHERE id = ?")
			.param(instrumentId)
			.query((row, index) -> new Instrument(row.getObject("simulation_id", UUID.class), row.getString("symbol"),
					row.getLong("last_trade_sequence")))
			.optional()
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "INSTRUMENT_NOT_FOUND", "No such instrument"));
		long through = throughSequence == null ? instrument.lastTradeSequence() : throughSequence;
		List<Row> page = jdbc.sql("""
				SELECT id, trade_sequence, price_cents, quantity, executed_at, command_sequence FROM trades
				WHERE simulation_id = ? AND instrument_id = ? AND trade_sequence > ? AND trade_sequence <= ?
				ORDER BY trade_sequence LIMIT ?""")
			.params(instrument.simulationId(), instrumentId, after, through, limit + 1)
			.query((row, index) -> new Row(row.getObject("id", UUID.class), row.getLong("trade_sequence"),
					row.getLong("price_cents"), row.getLong("quantity"),
					row.getTimestamp("executed_at").toInstant(), row.getLong("command_sequence")))
			.list();
		boolean more = page.size() > limit;
		List<Row> shown = more ? page.subList(0, limit) : page;
		return new TradeExport(instrumentId.toString(), instrument.symbol(), Long.toString(through),
				shown.stream()
					.map((trade) -> new ExportedTrade(trade.id().toString(), Long.toString(trade.sequence()),
							trade.priceCents(), trade.quantity(), trade.executedAt(),
							Long.toString(trade.commandSequence())))
					.toList(),
				more ? encodeCursor(shown.getLast().sequence()) : null);
	}

	/** Opaque to clients; versioned so its form can change without breaking a cursor already handed out. */
	static String encodeCursor(long tradeSequence) {
		return Base64.getUrlEncoder()
			.withoutPadding()
			.encodeToString(("v1:" + tradeSequence).getBytes(StandardCharsets.UTF_8));
	}

	private static long decodeCursor(String cursor) {
		try {
			String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			if (decoded.startsWith("v1:")) {
				long sequence = Long.parseLong(decoded.substring(3));
				if (sequence > 0) {
					return sequence;
				}
			}
		}
		catch (IllegalArgumentException ex) {
			// Not base64, or not a number: the same answer as any other unknown cursor.
		}
		throw ApiException.invalid("cursor", "INVALID_CURSOR", "cursor is not one this API issued");
	}

}
