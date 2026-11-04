package com.mia_princz.mse.marketdata.api;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
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

	private final MarketQueries queries;

	private final ObjectProvider<MarketFeedConsumer> marketFeed;

	private final ObjectProvider<MarketFeedConsumer> tradeFeed;

	MarketController(MarketQueries queries, @Qualifier("marketFeed") ObjectProvider<MarketFeedConsumer> marketFeed,
			@Qualifier("tradeFeed") ObjectProvider<MarketFeedConsumer> tradeFeed) {
		this.queries = queries;
		this.marketFeed = marketFeed;
		this.tradeFeed = tradeFeed;
	}

	/** {@code state} is the feed's state, or {@code DISABLED} when this instance runs without it. */
	record Feed(String state, long lag) {
	}

	record Quote(String price, long quantity, int orderCount) {
	}

	record InstrumentView(String instrumentId, String symbol, String venueState, String referencePrice, String tick,
			long lotSize, long minQuantity, long maxQuantity, String maxPrice, String lastPrice, Quote bestBid,
			Quote bestAsk, String lastTradeSequence, String sourceCommandSequence, Instant generatedAt) {
	}

	record Instruments(List<InstrumentView> instruments, Feed feed) {
	}

	/** {@code sourceCommandSequence} is the market revision: a higher one is newer. */
	record Book(String instrumentId, String symbol, String venueState, List<Quote> bids, List<Quote> asks,
			String sourceCommandSequence, Instant generatedAt, Feed feed) {
	}

	record TradeView(String tradeId, String tradeSequence, String price, long quantity, Instant executedAt) {
	}

	/** {@code nextCursor} is null on the last page. */
	record Trades(String instrumentId, List<TradeView> trades, String nextCursor, String latestTradeSequence,
			Feed feed) {
	}

	@GetMapping
	Instruments instruments() {
		return new Instruments(queries.instruments().stream().map(MarketController::view).toList(), feed(marketFeed));
	}

	@GetMapping("/{instrumentId}/book")
	Book book(@PathVariable UUID instrumentId, @RequestParam(defaultValue = "20") int depth) {
		if (depth < 1 || depth > MAX_DEPTH) {
			throw ApiException.invalid("depth", "OUT_OF_RANGE", "depth must be between 1 and " + MAX_DEPTH);
		}
		MarketQueries.Book book = queries.book(instrumentId, depth).orElseThrow(MarketController::notFound);
		return new Book(book.instrumentId().toString(), book.symbol(), book.venueState(), quotes(book.bids()),
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

	private static InstrumentView view(MarketQueries.Instrument instrument) {
		return new InstrumentView(instrument.instrumentId().toString(), instrument.symbol(), instrument.venueState(),
				price(instrument.referencePriceCents()), price(instrument.tickCents()), instrument.lotSize(),
				instrument.minQuantity(), instrument.maxQuantity(), price(instrument.maxPriceCents()),
				instrument.lastPriceCents() == null ? null : price(instrument.lastPriceCents()),
				quote(instrument.bestBid()), quote(instrument.bestAsk()),
				Long.toString(instrument.lastTradeSequence()), Long.toString(instrument.sourceCommandSequence()),
				instrument.generatedAt());
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

	private static Feed feed(ObjectProvider<MarketFeedConsumer> provider) {
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
