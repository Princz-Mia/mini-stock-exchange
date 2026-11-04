package com.mia_princz.mse.marketdata.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Read-only queries over the projections. Prices stay in cents here; the controller formats them. */
@Component
class MarketQueries {

	/** Reads the stored JSON with a plain mapper, independent of the web layer's settings. */
	private static final JsonMapper STORED = JsonMapper.builder().build();

	record Level(long priceCents, long quantity, int orderCount) {
	}

	record Instrument(UUID instrumentId, String symbol, String venueState, long referencePriceCents, long tickCents,
			long lotSize, long minQuantity, long maxQuantity, long maxPriceCents, Long lastPriceCents, Level bestBid,
			Level bestAsk, long lastTradeSequence, long sourceCommandSequence, Instant generatedAt) {
	}

	record Book(UUID instrumentId, String symbol, String venueState, List<Level> bids, List<Level> asks,
			long sourceCommandSequence, Instant generatedAt) {
	}

	record Trade(UUID tradeId, long tradeSequence, long priceCents, long quantity, Instant executedAt) {
	}

	private final JdbcClient jdbc;

	MarketQueries(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	List<Instrument> instruments() {
		return jdbc.sql("""
				SELECT i.instrument_id, i.symbol, m.venue_state, i.reference_price_cents, i.tick_cents, i.lot_size,
				    i.min_quantity, i.max_quantity, i.max_price_cents, m.last_price_cents, m.bids -> 0 AS best_bid,
				    m.asks -> 0 AS best_ask, m.last_trade_sequence, m.source_command_sequence, m.generated_at
				FROM instrument_projection i JOIN market_snapshot m ON m.instrument_id = i.instrument_id
				ORDER BY i.symbol""")
			.query((row, index) -> new Instrument(row.getObject("instrument_id", UUID.class), row.getString("symbol"),
					row.getString("venue_state"), row.getLong("reference_price_cents"), row.getLong("tick_cents"),
					row.getLong("lot_size"), row.getLong("min_quantity"), row.getLong("max_quantity"),
					row.getLong("max_price_cents"), row.getObject("last_price_cents", Long.class),
					level(row.getString("best_bid")), level(row.getString("best_ask")),
					row.getLong("last_trade_sequence"), row.getLong("source_command_sequence"),
					row.getTimestamp("generated_at").toInstant()))
			.list();
	}

	Optional<Book> book(UUID instrumentId, int depth) {
		return jdbc.sql("""
				SELECT i.symbol, m.venue_state, m.bids::text AS bids, m.asks::text AS asks, m.source_command_sequence,
				    m.generated_at
				FROM market_snapshot m JOIN instrument_projection i ON i.instrument_id = m.instrument_id
				WHERE m.instrument_id = ?""")
			.param(instrumentId)
			.query((row, index) -> new Book(instrumentId, row.getString("symbol"), row.getString("venue_state"),
					levels(row.getString("bids"), depth), levels(row.getString("asks"), depth),
					row.getLong("source_command_sequence"), row.getTimestamp("generated_at").toInstant()))
			.optional();
	}

	/** Known if either feed has seen it: a trade may arrive before its instrument's first snapshot. */
	boolean isKnown(UUID instrumentId) {
		return jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM instrument_projection WHERE instrument_id = :id)
				    OR EXISTS (SELECT 1 FROM public_trade WHERE instrument_id = :id)""")
			.param("id", instrumentId)
			.query(Boolean.class)
			.single();
	}

	/** Newest first, below {@code beforeSequence} when given; one extra row tells the caller whether more exist. */
	List<Trade> trades(UUID instrumentId, Long beforeSequence, int limit) {
		return jdbc.sql("""
				SELECT trade_id, trade_sequence, price_cents, quantity, executed_at FROM public_trade
				WHERE instrument_id = :id AND (CAST(:before AS BIGINT) IS NULL OR trade_sequence < :before)
				ORDER BY trade_sequence DESC LIMIT :limit""")
			.param("id", instrumentId)
			.param("before", beforeSequence)
			.param("limit", limit)
			.query((row, index) -> new Trade(row.getObject("trade_id", UUID.class), row.getLong("trade_sequence"),
					row.getLong("price_cents"), row.getLong("quantity"), row.getTimestamp("executed_at").toInstant()))
			.list();
	}

	/** The highest trade sequence projected for the instrument, or 0. */
	long latestTradeSequence(UUID instrumentId) {
		return jdbc.sql("SELECT coalesce(max(trade_sequence), 0) FROM public_trade WHERE instrument_id = ?")
			.param(instrumentId)
			.query(Long.class)
			.single();
	}

	private static Level level(String json) {
		return json == null ? null : level(STORED.readTree(json));
	}

	private static Level level(JsonNode node) {
		return new Level(node.get("priceCents").asLong(), node.get("quantity").asLong(), node.get("orderCount").asInt());
	}

	private static List<Level> levels(String json, int depth) {
		JsonNode array = STORED.readTree(json);
		return IntStream.range(0, Math.min(depth, array.size()))
			.mapToObj((i) -> level(array.get(i)))
			.toList();
	}

}
