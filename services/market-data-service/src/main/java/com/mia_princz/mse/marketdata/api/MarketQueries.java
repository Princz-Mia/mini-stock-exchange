package com.mia_princz.mse.marketdata.api;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Read-only queries over the projections. Prices stay in cents here; the controller formats them. */
@Component
class MarketQueries {

	/** Reads the stored JSON with a plain mapper, independent of the web layer's settings. */
	private static final JsonMapper STORED = JsonMapper.builder().build();

	record Level(long priceCents, long quantity, int orderCount) {
	}

	/** {@code displayName} is null for none, here and in {@link Book} and {@link Live}. */
	record Instrument(UUID instrumentId, String symbol, String displayName, String venueState, long referencePriceCents,
			long tickCents, long lotSize, long minQuantity, long maxQuantity, long maxPriceCents, Long lastPriceCents,
			Level bestBid, Level bestAsk, long lastTradeSequence, long sourceCommandSequence, Instant generatedAt) {
	}

	record Book(UUID instrumentId, String symbol, String displayName, String venueState, List<Level> bids,
			List<Level> asks, long sourceCommandSequence, Instant generatedAt) {
	}

	record Trade(UUID tradeId, long tradeSequence, long priceCents, long quantity, Instant executedAt) {
	}

	/** A tape entry as the snapshot carries it, with the side that took liquidity. */
	record TapeTrade(UUID tradeId, long tradeSequence, long priceCents, long quantity, String aggressorSide,
			Instant executedAt) {
	}

	/** Everything a live frame shows, from one row: the complete public state of the instrument. */
	record Live(UUID instrumentId, String symbol, String displayName, String venueState, long referencePriceCents,
			List<Level> bids, List<Level> asks, Long lastPriceCents, List<TapeTrade> recentTrades, long lastTradeSequence,
			long sourceCommandSequence, Instant generatedAt) {
	}

	/** How many minutes with trades the trend line covers, newest last. */
	static final int TREND_MINUTES = 60;

	record TrendPoint(Instant minute, long priceCents) {
	}

	/** An instrument's figures since trading began; all but the trend are null or zero before its first trade. */
	record Session(long tradeCount, long volume, BigDecimal notionalCents, Long highCents, Long lowCents,
			Long lastPriceCents, List<TrendPoint> trend) {

		static final Session NONE = new Session(0, 0, BigDecimal.ZERO, null, null, null, List.of());

	}

	private final JdbcClient jdbc;

	MarketQueries(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	List<Instrument> instruments() {
		return jdbc.sql("""
				SELECT i.instrument_id, i.symbol, i.display_name, m.venue_state, i.reference_price_cents, i.tick_cents,
				    i.lot_size,
				    i.min_quantity, i.max_quantity, i.max_price_cents, m.last_price_cents, m.bids -> 0 AS best_bid,
				    m.asks -> 0 AS best_ask, m.last_trade_sequence, m.source_command_sequence, m.generated_at
				FROM instrument_projection i JOIN market_snapshot m ON m.instrument_id = i.instrument_id
				ORDER BY i.symbol""")
			.query((row, index) -> new Instrument(row.getObject("instrument_id", UUID.class), row.getString("symbol"),
					row.getString("display_name"), row.getString("venue_state"), row.getLong("reference_price_cents"), row.getLong("tick_cents"),
					row.getLong("lot_size"), row.getLong("min_quantity"), row.getLong("max_quantity"),
					row.getLong("max_price_cents"), row.getObject("last_price_cents", Long.class),
					level(row.getString("best_bid")), level(row.getString("best_ask")),
					row.getLong("last_trade_sequence"), row.getLong("source_command_sequence"),
					row.getTimestamp("generated_at").toInstant()))
			.list();
	}

	/** A live frame's market row and session figures, read together. */
	record LiveSession(Live live, Session session) {
	}

	/**
	 * The session figures of each instrument asked for, with no entry for one that has not traded. The figures and the
	 * trend come from two statements in one repeatable-read transaction, so they come from the same moment.
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	Map<UUID, Session> sessions(Collection<UUID> instrumentIds) {
		if (instrumentIds.isEmpty()) {
			return Map.of();
		}
		UUID[] ids = instrumentIds.toArray(UUID[]::new);
		Map<UUID, List<TrendPoint>> trends = new HashMap<>();
		jdbc.sql("""
				SELECT instrument_id, minute_start, last_price_cents FROM (
				    SELECT instrument_id, minute_start, last_price_cents,
				        row_number() OVER (PARTITION BY instrument_id ORDER BY minute_start DESC) AS age
				    FROM session_minute WHERE instrument_id = ANY(:ids)) recent
				WHERE age <= :minutes
				ORDER BY instrument_id, minute_start""")
			.param("ids", ids)
			.param("minutes", TREND_MINUTES)
			.query((row, index) -> trends.computeIfAbsent(row.getObject("instrument_id", UUID.class), (id) -> new ArrayList<>())
				.add(new TrendPoint(row.getTimestamp("minute_start").toInstant(), row.getLong("last_price_cents"))))
			.list();
		Map<UUID, Session> sessions = new HashMap<>();
		jdbc.sql("""
				SELECT instrument_id, trade_count, volume, notional_cents, high_cents, low_cents, last_price_cents
				FROM instrument_session WHERE instrument_id = ANY(:ids)""")
			.param("ids", ids)
			.query((row, index) -> {
				UUID id = row.getObject("instrument_id", UUID.class);
				return sessions.put(id, new Session(row.getLong("trade_count"), row.getLong("volume"),
						row.getBigDecimal("notional_cents"), row.getLong("high_cents"), row.getLong("low_cents"),
						row.getLong("last_price_cents"), List.copyOf(trends.getOrDefault(id, List.of()))));
			})
			.list();
		return sessions;
	}

	/** The market row and the session figures from one moment, so the frame's last price and figures agree. */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	Optional<LiveSession> liveSession(UUID instrumentId) {
		return live(instrumentId).map((live) -> new LiveSession(live,
				sessions(List.of(instrumentId)).getOrDefault(instrumentId, Session.NONE)));
	}

	Optional<Book> book(UUID instrumentId, int depth) {
		return jdbc.sql("""
				SELECT i.symbol, i.display_name, m.venue_state, m.bids::text AS bids, m.asks::text AS asks,
				    m.source_command_sequence, m.generated_at
				FROM market_snapshot m JOIN instrument_projection i ON i.instrument_id = m.instrument_id
				WHERE m.instrument_id = ?""")
			.param(instrumentId)
			.query((row, index) -> new Book(instrumentId, row.getString("symbol"), row.getString("display_name"),
					row.getString("venue_state"),
					levels(row.getString("bids"), depth), levels(row.getString("asks"), depth),
					row.getLong("source_command_sequence"), row.getTimestamp("generated_at").toInstant()))
			.optional();
	}

	Optional<Live> live(UUID instrumentId) {
		return jdbc.sql("""
				SELECT i.symbol, i.display_name, m.venue_state, i.reference_price_cents, m.bids::text AS bids,
				    m.asks::text AS asks,
				    m.last_price_cents, m.recent_trades::text AS recent_trades, m.last_trade_sequence,
				    m.source_command_sequence, m.generated_at
				FROM market_snapshot m JOIN instrument_projection i ON i.instrument_id = m.instrument_id
				WHERE m.instrument_id = ?""")
			.param(instrumentId)
			.query((row, index) -> new Live(instrumentId, row.getString("symbol"), row.getString("display_name"),
					row.getString("venue_state"),
					row.getLong("reference_price_cents"), levels(row.getString("bids"), Integer.MAX_VALUE),
					levels(row.getString("asks"), Integer.MAX_VALUE),
					row.getObject("last_price_cents", Long.class), tape(row.getString("recent_trades")),
					row.getLong("last_trade_sequence"), row.getLong("source_command_sequence"),
					row.getTimestamp("generated_at").toInstant()))
			.optional();
	}

	/** Known if any feed has seen it: trades and candles may arrive before the instrument's first snapshot. */
	boolean isKnown(UUID instrumentId) {
		return jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM instrument_projection WHERE instrument_id = :id)
				    OR EXISTS (SELECT 1 FROM public_trade WHERE instrument_id = :id)
				    OR EXISTS (SELECT 1 FROM candle_1m WHERE instrument_id = :id)""")
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
	/** A stored one-minute candle; the notional is exact. */
	record Candle(Instant bucketStart, long openCents, long highCents, long lowCents, long closeCents, long volume,
			long tradeCount, BigDecimal notionalCents, long sourceMaxTradeSequence) {
	}

	/** One epoch's candles for an instrument with {@code from <= bucket < to}, oldest first. */
	List<Candle> candles(UUID instrumentId, long epoch, Instant from, Instant to) {
		return jdbc.sql("""
				SELECT bucket_start, open_cents, high_cents, low_cents, close_cents, volume, trade_count,
				    notional_cents, source_max_trade_sequence
				FROM candle_1m
				WHERE computation_epoch = ? AND instrument_id = ? AND bucket_start >= ? AND bucket_start < ?
				ORDER BY bucket_start""")
			.params(epoch, instrumentId, Timestamp.from(from), Timestamp.from(to))
			.query((row, index) -> new Candle(row.getTimestamp("bucket_start").toInstant(), row.getLong("open_cents"),
					row.getLong("high_cents"), row.getLong("low_cents"), row.getLong("close_cents"),
					row.getLong("volume"), row.getLong("trade_count"), row.getBigDecimal("notional_cents"),
					row.getLong("source_max_trade_sequence")))
			.list();
	}

	/** The highest trade sequence any of the epoch's candles for the instrument include, or null before the first. */
	Long candlesThroughTradeSequence(UUID instrumentId, long epoch) {
		return jdbc.sql("""
				SELECT max(source_max_trade_sequence) FROM candle_1m WHERE computation_epoch = ? AND instrument_id = ?""")
			.params(epoch, instrumentId)
			.query(Long.class)
			.optional()
			.orElse(null);
	}

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

	private static List<TapeTrade> tape(String json) {
		List<TapeTrade> trades = new ArrayList<>();
		for (JsonNode trade : STORED.readTree(json)) {
			trades.add(new TapeTrade(UUID.fromString(trade.get("tradeId").asString()), trade.get("tradeSequence").asLong(),
					trade.get("priceCents").asLong(), trade.get("quantity").asLong(),
					trade.get("aggressorSide").asString(), Instant.parse(trade.get("executedAt").asString())));
		}
		return trades;
	}

	private static List<Level> levels(String json, int depth) {
		JsonNode array = STORED.readTree(json);
		return IntStream.range(0, Math.min(depth, array.size()))
			.mapToObj((i) -> level(array.get(i)))
			.toList();
	}

}
