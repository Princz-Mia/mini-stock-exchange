package com.mia_princz.mse.marketdata.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.mia_princz.mse.marketdata.projection.MarketFeedConsumer;

/**
 * Public market data, read from this service's projections. Every response says how current it is: the feed's state
 * and lag, so a client can label stale data instead of presenting it as live. Prices are decimal strings and
 * sequences are strings, as in the trading API.
 */
@RestController
@RequestMapping("/api/v1/instruments")
class MarketController {

	static final int MAX_DEPTH = 20;

	static final int DEFAULT_LIMIT = 50;

	static final int MAX_LIMIT = 200;

	/** One bar per minute, so at most a day per request. */
	static final int MAX_CANDLE_MINUTES = 1_440;

	static final Duration DEFAULT_CANDLE_RANGE = Duration.ofHours(2);

	private final MarketQueries queries;

	private final ObjectProvider<MarketFeedConsumer> marketFeed;

	private final ObjectProvider<MarketFeedConsumer> tradeFeed;

	private final ObjectProvider<MarketFeedConsumer> candleFeed;

	private final long servedEpoch;

	private final Clock clock;

	MarketController(MarketQueries queries, @Qualifier("marketFeed") ObjectProvider<MarketFeedConsumer> marketFeed,
			@Qualifier("tradeFeed") ObjectProvider<MarketFeedConsumer> tradeFeed,
			@Qualifier("candleFeed") ObjectProvider<MarketFeedConsumer> candleFeed,
			@Value("${mse.candles.served-epoch:1}") long servedEpoch, Clock clock) {
		this.queries = queries;
		this.marketFeed = marketFeed;
		this.tradeFeed = tradeFeed;
		this.candleFeed = candleFeed;
		this.servedEpoch = servedEpoch;
		this.clock = clock;
	}

	/** {@code state} is the feed's state, or {@code DISABLED} when this instance runs without it. */
	record Feed(String state, long lag) {
	}

	record Quote(String price, long quantity, int orderCount) {
	}

	/** {@code displayName} is null for none, here and in {@link Book}. */
	record InstrumentView(String instrumentId, String symbol, String displayName, String venueState,
			String referencePrice, String tick, long lotSize, long minQuantity, long maxQuantity, String maxPrice, String lastPrice, Quote bestBid,
			Quote bestAsk, String lastTradeSequence, String sourceCommandSequence, Instant generatedAt,
			SessionView session) {
	}

	record TrendPointView(Instant minute, String price) {
	}

	/**
	 * Measured from the reference price, as there is no opening auction: {@code open} is the reference price, and the
	 * change and its percentage are the last price against it, the percentage rounded half-even to two places. VWAP is
	 * the notional over the volume, rounded half-even to the cent, as in the candles. {@code feed} is the trade feed's,
	 * which the figures come from: while it is behind, they are too, even when the market feed is live.
	 */
	record SessionView(String open, String last, String change, String changePercent, String high, String low,
			long volume, long tradeCount, String vwap, List<TrendPointView> trend, Feed feed) {
	}

	record Instruments(List<InstrumentView> instruments, Feed feed) {
	}

	/** {@code sourceCommandSequence} is the market revision: a higher one is newer. */
	record Book(String instrumentId, String symbol, String displayName, String venueState, List<Quote> bids,
			List<Quote> asks, String sourceCommandSequence, Instant generatedAt, Feed feed) {
	}

	record TradeView(String tradeId, String tradeSequence, String price, long quantity, Instant executedAt) {
	}

	/** {@code nextCursor} is null on the last page. */
	record Trades(String instrumentId, List<TradeView> trades, String nextCursor, String latestTradeSequence,
			Feed feed) {
	}

	/** {@code vwap} is notional / volume rounded half-even to the cent; notional and volume are exact. */
	record CandleView(Instant bucketStart, String open, String high, String low, String close, long volume,
			long tradeCount, String notional, String vwap, String sourceMaxTradeSequence) {
	}

	/**
	 * How far the candles have got: the highest trade sequence they include against the newest trade on the tape.
	 * {@code tradesBehind} grows while the analytics job is down or an instrument is stopped by a sequence gap.
	 */
	record CandleFreshness(Feed feed, String candlesThroughTradeSequence, String latestTradeSequence,
			long tradesBehind) {
	}

	record Candles(String instrumentId, long computationEpoch, Instant from, Instant to, List<CandleView> candles,
			CandleFreshness freshness) {
	}

	/**
	 * One-minute bars with {@code from <= bucket < to}, oldest first, from the served computation epoch. Minutes
	 * without trades have no bar: a chart shows the gap rather than an invented candle.
	 */
	@GetMapping("/{instrumentId}/candles")
	Candles candles(@PathVariable UUID instrumentId, @RequestParam(required = false) String from,
			@RequestParam(required = false) String to) {
		Instant end = to == null ? clock.instant().truncatedTo(ChronoUnit.MINUTES).plus(1, ChronoUnit.MINUTES)
				: instant("to", to);
		Instant start = from == null ? end.minus(DEFAULT_CANDLE_RANGE) : instant("from", from);
		if (!start.isBefore(end)) {
			throw ApiException.invalid("from", "INVALID_RANGE", "from must be before to");
		}
		if (Duration.between(start, end).compareTo(Duration.ofMinutes(MAX_CANDLE_MINUTES)) > 0) {
			throw ApiException.invalid("to", "RANGE_TOO_LARGE",
					"at most " + MAX_CANDLE_MINUTES + " minutes of one-minute bars per request");
		}
		if (!queries.isKnown(instrumentId)) {
			throw notFound();
		}
		List<CandleView> bars = queries.candles(instrumentId, servedEpoch, start, end)
			.stream()
			.map(MarketController::candle)
			.toList();
		Long through = queries.candlesThroughTradeSequence(instrumentId, servedEpoch);
		long latest = queries.latestTradeSequence(instrumentId);
		CandleFreshness freshness = new CandleFreshness(feed(candleFeed), through == null ? null : Long.toString(through),
				Long.toString(latest), Math.max(0, latest - (through == null ? 0 : through)));
		return new Candles(instrumentId.toString(), servedEpoch, start, end, bars, freshness);
	}

	@GetMapping
	Instruments instruments() {
		List<MarketQueries.Instrument> listed = queries.instruments();
		Map<UUID, MarketQueries.Session> sessions = queries
			.sessions(listed.stream().map(MarketQueries.Instrument::instrumentId).toList());
		return new Instruments(listed.stream()
			.map((instrument) -> view(instrument,
					sessions.getOrDefault(instrument.instrumentId(), MarketQueries.Session.NONE), feed(tradeFeed)))
			.toList(), feed(marketFeed));
	}

	@GetMapping("/{instrumentId}/book")
	Book book(@PathVariable UUID instrumentId, @RequestParam(defaultValue = "20") int depth) {
		if (depth < 1 || depth > MAX_DEPTH) {
			throw ApiException.invalid("depth", "OUT_OF_RANGE", "depth must be between 1 and " + MAX_DEPTH);
		}
		MarketQueries.Book book = queries.book(instrumentId, depth).orElseThrow(MarketController::notFound);
		return new Book(book.instrumentId().toString(), book.symbol(), book.displayName(), book.venueState(),
				quotes(book.bids()),
				quotes(book.asks()), Long.toString(book.sourceCommandSequence()), book.generatedAt(), feed(marketFeed));
	}

	@GetMapping("/{instrumentId}/trades")
	Trades trades(@PathVariable UUID instrumentId, @RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "50") int limit) {
		if (limit < 1 || limit > MAX_LIMIT) {
			throw ApiException.invalid("limit", "OUT_OF_RANGE", "limit must be between 1 and " + MAX_LIMIT);
		}
		Long before = cursor == null ? null : decodeCursor(cursor);
		if (!queries.isKnown(instrumentId)) {
			throw notFound();
		}
		List<MarketQueries.Trade> page = queries.trades(instrumentId, before, limit + 1);
		boolean more = page.size() > limit;
		List<MarketQueries.Trade> shown = more ? page.subList(0, limit) : page;
		String next = more ? encodeCursor(shown.getLast().tradeSequence()) : null;
		return new Trades(instrumentId.toString(),
				shown.stream()
					.map((trade) -> new TradeView(trade.tradeId().toString(), Long.toString(trade.tradeSequence()),
							price(trade.priceCents()), trade.quantity(), trade.executedAt()))
					.toList(),
				next, Long.toString(queries.latestTradeSequence(instrumentId)), feed(tradeFeed));
	}

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ProblemDetail> problem(ApiException ex) {
		return ResponseEntity.status(ex.status()).body(ex.toProblem());
	}

	/** A malformed id or number: answered here, because an error dispatch would meet the deny-all rule. */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ResponseEntity<ProblemDetail> mismatch(MethodArgumentTypeMismatchException ex) {
		return problem(ApiException.invalid(ex.getName(), "INVALID_VALUE", ex.getName() + " has an invalid value"));
	}

	private static InstrumentView view(MarketQueries.Instrument instrument, MarketQueries.Session session,
			Feed tradeFeed) {
		return new InstrumentView(instrument.instrumentId().toString(), instrument.symbol(), instrument.displayName(),
				instrument.venueState(),
				price(instrument.referencePriceCents()), price(instrument.tickCents()), instrument.lotSize(),
				instrument.minQuantity(), instrument.maxQuantity(), price(instrument.maxPriceCents()),
				instrument.lastPriceCents() == null ? null : price(instrument.lastPriceCents()),
				quote(instrument.bestBid()), quote(instrument.bestAsk()),
				Long.toString(instrument.lastTradeSequence()), Long.toString(instrument.sourceCommandSequence()),
				instrument.generatedAt(), session(instrument.referencePriceCents(), session, tradeFeed));
	}

	static SessionView session(long referenceCents, MarketQueries.Session session, Feed tradeFeed) {
		Long last = session.lastPriceCents();
		String change = null;
		String percent = null;
		String vwap = null;
		if (last != null) {
			long difference = Math.subtractExact(last, referenceCents);
			change = BigDecimal.valueOf(difference, 2).toPlainString();
			percent = BigDecimal.valueOf(difference)
				.movePointRight(2)
				.divide(BigDecimal.valueOf(referenceCents), 2, RoundingMode.HALF_EVEN)
				.toPlainString();
			vwap = session.notionalCents()
				.divide(BigDecimal.valueOf(session.volume()), 0, RoundingMode.HALF_EVEN)
				.movePointLeft(2)
				.setScale(2)
				.toPlainString();
		}
		return new SessionView(price(referenceCents), last == null ? null : price(last), change, percent,
				session.highCents() == null ? null : price(session.highCents()),
				session.lowCents() == null ? null : price(session.lowCents()), session.volume(), session.tradeCount(),
				vwap,
				session.trend()
					.stream()
					.map((point) -> new TrendPointView(point.minute(), price(point.priceCents())))
					.toList(),
				tradeFeed);
	}

	private static CandleView candle(MarketQueries.Candle candle) {
		BigDecimal vwapCents = candle.notionalCents().divide(BigDecimal.valueOf(candle.volume()), 0, RoundingMode.HALF_EVEN);
		return new CandleView(candle.bucketStart(), price(candle.openCents()), price(candle.highCents()),
				price(candle.lowCents()), price(candle.closeCents()), candle.volume(), candle.tradeCount(),
				candle.notionalCents().movePointLeft(2).setScale(2).toPlainString(),
				vwapCents.movePointLeft(2).setScale(2).toPlainString(), Long.toString(candle.sourceMaxTradeSequence()));
	}

	private static Instant instant(String name, String value) {
		try {
			return Instant.parse(value);
		}
		catch (DateTimeParseException ex) {
			throw ApiException.invalid(name, "INVALID_VALUE", name + " must be a UTC time such as 2026-11-05T10:15:00Z");
		}
	}

	private static List<Quote> quotes(List<MarketQueries.Level> levels) {
		return levels.stream().map(MarketController::quote).toList();
	}

	private static Quote quote(MarketQueries.Level level) {
		return level == null ? null : new Quote(price(level.priceCents()), level.quantity(), level.orderCount());
	}

	static String price(long cents) {
		return BigDecimal.valueOf(cents, 2).toPlainString();
	}

	static Feed feed(ObjectProvider<MarketFeedConsumer> provider) {
		MarketFeedConsumer feed = provider.getIfAvailable();
		return feed == null ? new Feed("DISABLED", -1) : new Feed(feed.state().name(), feed.lag());
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

	private static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "INSTRUMENT_NOT_FOUND", "No such instrument");
	}

}
