package com.mia_princz.mse.marketdata.api;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.mia_princz.mse.marketdata.projection.MarketFeedConsumer;

/**
 * The public live market stream: {@code market} events carry the complete state of one instrument, and
 * {@code heartbeat} events the feed's state when nothing changes. Each frame replaces the previous one entirely, so a
 * client that misses frames, or sees the revision jump, is still correct. A new {@code bootId} means this service
 * restarted: replace everything.
 */
@RestController
class MarketEventsController {

	private final MarketStream stream;

	private final MarketQueries queries;

	MarketEventsController(MarketStream stream, MarketQueries queries) {
		this.stream = stream;
		this.queries = queries;
	}

	record TapeView(String tradeId, String tradeSequence, String price, long quantity, String aggressorSide,
			Instant executedAt) {
	}

	/** {@code displayName} is null for none. */
	record MarketFrame(String bootId, String instrumentId, String symbol, String displayName, String venueState,
			String sourceCommandSequence, List<MarketController.Quote> bids, List<MarketController.Quote> asks,
			String lastPrice, List<TapeView> recentTrades, String lastTradeSequence, Instant generatedAt,
			MarketController.Feed feed, MarketController.SessionView session) {
	}

	record Heartbeat(String bootId, MarketController.Feed feed, Instant at) {
	}

	@GetMapping("/api/v1/market-events")
	SseEmitter events(@RequestParam UUID instrumentId) {
		Object current = stream.current(instrumentId)
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "INSTRUMENT_NOT_FOUND",
					"No market for this instrument yet"));
		try {
			return stream.subscribe(instrumentId, current);
		}
		catch (MarketStream.TooManySubscribersException ex) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TOO_MANY_STREAMS", ex.getMessage());
		}
	}

	/** Problems keep their own content type, so a client that asked for an event stream still gets the status. */
	@ExceptionHandler(ApiException.class)
	ResponseEntity<ProblemDetail> problem(ApiException ex) {
		ResponseEntity.BodyBuilder response = ResponseEntity.status(ex.status())
			.contentType(MediaType.APPLICATION_PROBLEM_JSON);
		if (ex.status() == HttpStatus.SERVICE_UNAVAILABLE) {
			response.header("Retry-After", "5");
		}
		return response.body(ex.toProblem());
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ResponseEntity<ProblemDetail> mismatch(MethodArgumentTypeMismatchException ex) {
		return problem(ApiException.invalid(ex.getName(), "INVALID_VALUE", ex.getName() + " has an invalid value"));
	}

	@ExceptionHandler(MissingServletRequestParameterException.class)
	ResponseEntity<ProblemDetail> missing(MissingServletRequestParameterException ex) {
		return problem(ApiException.invalid(ex.getParameterName(), "REQUIRED", ex.getParameterName() + " is required"));
	}

	/** The frames the stream sends, built from the projections and the feed's current state. */
	static MarketStream.Frames frames(MarketQueries queries, ObjectProvider<MarketFeedConsumer> marketFeed,
			ObjectProvider<MarketFeedConsumer> tradeFeed, UUID bootId, Clock clock) {
		return new MarketStream.Frames() {

			@Override
			public Optional<Object> frame(UUID instrumentId) {
				return queries.liveSession(instrumentId)
					.map((both) -> marketFrame(bootId, instrumentId, both.live(), both.session(), marketFeed, tradeFeed));
			}

			@Override
			public Object heartbeat() {
				return new Heartbeat(bootId.toString(), feed(marketFeed), clock.instant());
			}

		};
	}

	private static MarketFrame marketFrame(UUID bootId, UUID instrumentId, MarketQueries.Live live,
			MarketQueries.Session session, ObjectProvider<MarketFeedConsumer> marketFeed,
			ObjectProvider<MarketFeedConsumer> tradeFeed) {
		return new MarketFrame(bootId.toString(), instrumentId.toString(), live.symbol(), live.displayName(),
				live.venueState(),
				Long.toString(live.sourceCommandSequence()),
				live.bids().stream().map(MarketEventsController::quote).toList(),
				live.asks().stream().map(MarketEventsController::quote).toList(),
				live.lastPriceCents() == null ? null : MarketController.price(live.lastPriceCents()),
				live.recentTrades()
					.stream()
					.map((trade) -> new TapeView(trade.tradeId().toString(), Long.toString(trade.tradeSequence()),
							MarketController.price(trade.priceCents()), trade.quantity(), trade.aggressorSide(),
							trade.executedAt()))
					.toList(),
				Long.toString(live.lastTradeSequence()), live.generatedAt(), feed(marketFeed),
				MarketController.session(live.referencePriceCents(), session, feed(tradeFeed)));
	}

	private static MarketController.Quote quote(MarketQueries.Level level) {
		return new MarketController.Quote(MarketController.price(level.priceCents()), level.quantity(),
				level.orderCount());
	}

	private static MarketController.Feed feed(ObjectProvider<MarketFeedConsumer> provider) {
		MarketFeedConsumer feed = provider.getIfAvailable();
		return feed == null ? new MarketController.Feed("DISABLED", -1)
				: new MarketController.Feed(feed.state().name(), feed.lag());
	}

}
