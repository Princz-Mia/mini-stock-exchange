package com.mia_princz.mse.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.mia_princz.mse.contracts.events.CandleUpdated;
import com.mia_princz.mse.marketdata.projection.CandleProjection;
import com.mia_princz.mse.marketdata.projection.SnapshotProjection;
import com.mia_princz.mse.marketdata.projection.TradeProjection;

/** One-minute bars over HTTP, fed through the projections directly. The feeds are off. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=candle-api", "mse.market-feed.enabled=false", "mse.trade-feed.enabled=false",
				"mse.candle-feed.enabled=false" })
class CandleApiIT {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Instant M = Candles.MINUTE;

	@LocalServerPort
	int port;

	@Autowired
	SnapshotProjection snapshots;

	@Autowired
	TradeProjection trades;

	@Autowired
	CandleProjection candles;

	@Test
	void barsComeOldestFirstFromTheServedEpochWithGapsLeftOut() throws Exception {
		UUID instrument = UUID.randomUUID();
		snapshots.apply(Snapshots.snapshot(instrument, 5, 1));
		for (long sequence = 1; sequence <= 4; sequence++) {
			trades.apply(Trades.trade(instrument, sequence));
		}
		candles.apply(Candles.candle(1, instrument, M.plusSeconds(120), 4));
		candles.apply(Candles.candle(1, instrument, M, 3));
		candles.apply(Candles.candle(2, instrument, M, 1));
		// Another epoch, further along and in a minute epoch 1 has no bar for: neither its bars nor its progress show.
		candles.apply(Candles.candle(2, instrument, M.plusSeconds(60), 6));
		candles.apply(Candles.candle(1, instrument, M.plusSeconds(600), 4));

		JsonNode body = json(get(instrument, "?from=" + M.minusSeconds(60) + "&to=" + M.plusSeconds(600)), 200);

		assertThat(body.get("computationEpoch").asLong()).isEqualTo(1);
		assertThat(starts(body)).as("the minute between has no trades, so no bar; to is exclusive")
			.containsExactly(M.toString(), M.plusSeconds(120).toString());
		JsonNode first = body.get("candles").get(0);
		assertThat(first.get("open").asString()).isEqualTo("100.01");
		assertThat(first.get("high").asString()).isEqualTo("100.03");
		assertThat(first.get("close").asString()).isEqualTo("100.03");
		assertThat(first.get("volume").asLong()).isEqualTo(15);
		assertThat(first.get("tradeCount").asLong()).as("epoch 1, not epoch 2's single trade").isEqualTo(3);
		assertThat(first.get("notional").asString()).isEqualTo("1500.30");
		assertThat(first.get("vwap").asString()).isEqualTo("100.02");
		assertThat(first.get("sourceMaxTradeSequence").asString()).isEqualTo("3");
		JsonNode freshness = body.get("freshness");
		assertThat(freshness.get("candlesThroughTradeSequence").asString()).isEqualTo("4");
		assertThat(freshness.get("latestTradeSequence").asString()).isEqualTo("4");
		assertThat(freshness.get("tradesBehind").asLong()).isZero();
		assertThat(freshness.get("feed").get("state").asString()).isEqualTo("DISABLED");

		trades.apply(Trades.trade(instrument, 5));
		trades.apply(Trades.trade(instrument, 6));
		assertThat(json(get(instrument, ""), 200).get("freshness").get("tradesBehind").asLong())
			.as("two trades on the tape that no bar includes yet")
			.isEqualTo(2);
	}

	@Test
	void vwapRoundsHalfToEvenAndNotionalStaysExact() throws Exception {
		UUID instrument = UUID.randomUUID();
		candles.apply(bar(instrument, M, 12, 13, 2, BigDecimal.valueOf(25)));
		candles.apply(bar(instrument, M.plusSeconds(60), 17, 18, 2, BigDecimal.valueOf(35)));
		BigDecimal huge = BigDecimal.valueOf(10_001).multiply(BigDecimal.valueOf(Long.MAX_VALUE));
		candles.apply(bar(instrument, M.plusSeconds(120), 10_001, 10_001, Long.MAX_VALUE, huge));

		JsonNode bars = json(get(instrument, "?from=" + M + "&to=" + M.plusSeconds(180)), 200).get("candles");

		assertThat(bars.get(0).get("vwap").asString()).as("12.5 cents rounds to the even 12").isEqualTo("0.12");
		assertThat(bars.get(0).get("notional").asString()).isEqualTo("0.25");
		assertThat(bars.get(1).get("vwap").asString()).as("17.5 cents rounds to the even 18").isEqualTo("0.18");
		assertThat(bars.get(2).get("notional").asString()).isEqualTo(huge.movePointLeft(2).toPlainString());
		assertThat(bars.get(2).get("vwap").asString()).isEqualTo("100.01");
	}

	@Test
	void withoutARangeTheLastTwoHoursEndingAfterTheCurrentMinute() throws Exception {
		UUID instrument = UUID.randomUUID();
		trades.apply(Trades.trade(instrument, 1));

		JsonNode body = json(get(instrument, ""), 200);

		Instant from = Instant.parse(body.get("from").asString());
		Instant to = Instant.parse(body.get("to").asString());
		assertThat(Duration.between(from, to)).isEqualTo(Duration.ofHours(2));
		assertThat(to).isEqualTo(to.truncatedTo(ChronoUnit.MINUTES)).isAfter(Instant.now());
		assertThat(body.get("candles").size()).isZero();
		assertThat(body.get("freshness").get("candlesThroughTradeSequence").isNull()).isTrue();
		assertThat(body.get("freshness").get("tradesBehind").asLong()).isEqualTo(1);
	}

	@Test
	void rangesAreBoundedAndValidated() throws Exception {
		UUID instrument = UUID.randomUUID();
		candles.apply(Candles.candle(1, instrument, M, 1));

		assertThat(get(instrument, "?from=" + M + "&to=" + M.plus(Duration.ofMinutes(1_440))).statusCode())
			.as("exactly a day of bars").isEqualTo(200);
		assertThat(fieldError(get(instrument, "?from=" + M + "&to=" + M.plus(Duration.ofMinutes(1_441)))))
			.isEqualTo("to RANGE_TOO_LARGE");
		assertThat(fieldError(get(instrument, "?from=" + M + "&to=" + M))).isEqualTo("from INVALID_RANGE");
		assertThat(fieldError(get(instrument, "?from=yesterday"))).isEqualTo("from INVALID_VALUE");
		assertThat(code(get(UUID.randomUUID(), ""), 404)).isEqualTo("INSTRUMENT_NOT_FOUND");
		assertThat(get(instrument, "?from=" + M + "&to=" + M.plusSeconds(60)).statusCode())
			.as("known from its candles alone, before any snapshot or trade")
			.isEqualTo(200);
	}

	private static CandleUpdated bar(UUID instrument, Instant minute, long low, long high, long volume,
			BigDecimal notional) {
		return CandleUpdated.newBuilder(Candles.candle(1, instrument, minute, 1))
			.setOpenCents(low)
			.setLowCents(low)
			.setHighCents(high)
			.setCloseCents(high)
			.setVolume(volume)
			.setNotionalCents(notional)
			.build();
	}

	private static List<String> starts(JsonNode body) {
		List<String> starts = new ArrayList<>();
		body.get("candles").forEach((candle) -> starts.add(Instant.parse(candle.get("bucketStart").asString()).toString()));
		return starts;
	}

	private HttpResponse<String> get(UUID instrument, String query) throws Exception {
		try (HttpClient client = HttpClient.newHttpClient()) {
			return client.send(HttpRequest
				.newBuilder(URI.create("http://localhost:" + port + "/api/v1/instruments/" + instrument + "/candles" + query))
				.build(), HttpResponse.BodyHandlers.ofString());
		}
	}

	private static JsonNode json(HttpResponse<String> response, int status) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
		return JSON.readTree(response.body());
	}

	private static String code(HttpResponse<String> problem, int status) {
		return json(problem, status).get("code").asString();
	}

	/** A 400 names the field and why, under the usual VALIDATION_FAILED. */
	private static String fieldError(HttpResponse<String> problem) {
		JsonNode body = json(problem, 400);
		assertThat(body.get("code").asString()).isEqualTo("VALIDATION_FAILED");
		JsonNode error = body.get("errors").get(0);
		return error.get("field").asString() + " " + error.get("code").asString();
	}

}
