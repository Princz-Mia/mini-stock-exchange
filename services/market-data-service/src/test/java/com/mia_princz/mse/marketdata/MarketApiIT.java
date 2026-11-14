package com.mia_princz.mse.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
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

import com.mia_princz.mse.contracts.events.TradeExecuted;
import com.mia_princz.mse.marketdata.projection.SnapshotProjection;
import com.mia_princz.mse.marketdata.projection.TradeProjection;

/** The public endpoints over HTTP, fed through the projections directly. Both feeds are off. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=market-api", "mse.market-feed.enabled=false",
				"mse.trade-feed.enabled=false" })
class MarketApiIT {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@LocalServerPort
	int port;

	@Autowired
	SnapshotProjection snapshots;

	@Autowired
	TradeProjection trades;

	@Test
	void instrumentsCarryQuotesRulesAndFreshness() throws Exception {
		UUID instrument = UUID.randomUUID();
		snapshots.apply(Snapshots.snapshot(instrument, 12, 3));

		JsonNode body = json(get("/api/v1/instruments"), 200);

		JsonNode entry = find(body.get("instruments"), instrument);
		assertThat(entry.get("symbol").asString()).isEqualTo("NOVA");
		assertThat(entry.get("venueState").asString()).isEqualTo("OPEN");
		assertThat(entry.get("referencePrice").asString()).isEqualTo("100.00");
		assertThat(entry.get("tick").asString()).isEqualTo("0.01");
		assertThat(entry.get("lastPrice").asString()).isEqualTo("100.00");
		assertThat(entry.get("bestBid").get("price").asString()).isEqualTo("100.00");
		assertThat(entry.get("bestAsk").get("price").asString()).isEqualTo("101.00");
		assertThat(entry.get("bestAsk").get("quantity").asLong()).isEqualTo(3);
		assertThat(entry.get("sourceCommandSequence").isString()).as("sequences travel as strings").isTrue();
		assertThat(entry.get("sourceCommandSequence").asString()).isEqualTo("12");
		assertThat(body.get("feed").get("state").asString()).isEqualTo("DISABLED");
	}

	@Test
	void instrumentsCarryTheirSessionFiguresFromTheTape() throws Exception {
		UUID instrument = UUID.randomUUID();
		snapshots.apply(Snapshots.snapshot(instrument, 12, 3));
		JsonNode before = find(json(get("/api/v1/instruments"), 200).get("instruments"), instrument).get("session");
		assertThat(before.toString()).isEqualTo("{\"open\":\"100.00\",\"last\":null,\"change\":null,\"changePercent\":null,"
				+ "\"high\":null,\"low\":null,\"volume\":0,\"tradeCount\":0,\"vwap\":null,\"trend\":[],"
				// The trade feed is off here; it is what the figures come from.
				+ "\"feed\":{\"state\":\"DISABLED\",\"lag\":-1}}");

		Instant minute = Instant.parse("2026-11-12T10:15:00Z");
		trades.apply(trade(instrument, 1, 9_950, 10, minute.plusSeconds(5)));
		trades.apply(trade(instrument, 3, 9_899, 7, minute.plusSeconds(70)));
		// Arrives after the later trade: it counts, but does not become the last price.
		trades.apply(trade(instrument, 2, 10_175, 3, minute.plusSeconds(50)));

		JsonNode session = find(json(get("/api/v1/instruments"), 200).get("instruments"), instrument).get("session");
		assertThat(session.get("open").asString()).isEqualTo("100.00");
		assertThat(session.get("last").asString()).isEqualTo("98.99");
		assertThat(session.get("change").asString()).isEqualTo("-1.01");
		assertThat(session.get("changePercent").asString()).isEqualTo("-1.01");
		assertThat(session.get("high").asString()).isEqualTo("101.75");
		assertThat(session.get("low").asString()).isEqualTo("98.99");
		assertThat(session.get("volume").asLong()).isEqualTo(20);
		assertThat(session.get("tradeCount").asLong()).isEqualTo(3);
		// (99,500 + 30,525 + 69,293) cents / 20 shares = 9,965.9 cents, to the cent half-even.
		assertThat(session.get("vwap").asString()).isEqualTo("99.66");
		assertThat(session.get("trend").toString()).isEqualTo("""
				[{"minute":"2026-11-12T10:15:00Z","price":"101.75"},{"minute":"2026-11-12T10:16:00Z","price":"98.99"}]""");
	}

	@Test
	void theTrendKeepsTheNewestSixtyMinutesThatHadATrade() throws Exception {
		UUID instrument = UUID.randomUUID();
		snapshots.apply(Snapshots.snapshot(instrument, 12, 3));
		Instant start = Instant.parse("2026-11-12T08:00:00Z");
		for (int minute = 0; minute < 70; minute++) {
			// Two trades a minute, the later one by sequence stamped earlier: each minute keeps its last by sequence.
			trades.apply(trade(instrument, 2L * minute + 2, 20_000 + minute, 1, start.plusSeconds(60L * minute + 10)));
			trades.apply(trade(instrument, 2L * minute + 1, 10_000 + minute, 1, start.plusSeconds(60L * minute + 40)));
		}

		JsonNode trend = find(json(get("/api/v1/instruments"), 200).get("instruments"), instrument).get("session")
			.get("trend");

		assertThat(trend.size()).isEqualTo(60);
		assertThat(trend.get(0).get("minute").asString()).isEqualTo("2026-11-12T08:10:00Z");
		assertThat(trend.get(0).get("price").asString()).isEqualTo("200.10");
		assertThat(trend.get(59).get("minute").asString()).isEqualTo("2026-11-12T09:09:00Z");
		assertThat(trend.get(59).get("price").asString()).isEqualTo("200.69");
	}

	/** An instrument deleted during setup leaves the list, and every read of it says it does not exist. */
	@Test
	void aRemovedInstrumentIsNoLongerServed() throws Exception {
		UUID instrument = UUID.randomUUID();
		snapshots.apply(Snapshots.untraded(instrument, 4));
		assertThat(find(json(get("/api/v1/instruments"), 200).get("instruments"), instrument)).isNotNull();

		snapshots.apply(Snapshots.removed(instrument, 6));

		for (JsonNode entry : json(get("/api/v1/instruments"), 200).get("instruments")) {
			assertThat(entry.get("instrumentId").asString()).isNotEqualTo(instrument.toString());
		}
		for (String read : List.of("book", "trades", "candles")) {
			assertThat(code(get("/api/v1/instruments/" + instrument + "/" + read), 404)).as(read)
				.isEqualTo("INSTRUMENT_NOT_FOUND");
		}
	}

	@Test
	void bookIsBoundedToTheRequestedDepth() throws Exception {
		UUID instrument = UUID.randomUUID();
		snapshots.apply(Snapshots.snapshot(instrument, 4, 15));

		JsonNode full = json(get("/api/v1/instruments/" + instrument + "/book"), 200);
		JsonNode top = json(get("/api/v1/instruments/" + instrument + "/book?depth=2"), 200);

		assertThat(full.get("bids").size()).isEqualTo(15);
		assertThat(top.get("bids").size()).isEqualTo(2);
		assertThat(top.get("bids").get(0).get("price").asString()).as("best first").isEqualTo("100.00");
		assertThat(top.get("bids").get(1).get("price").asString()).isEqualTo("99.99");
		assertThat(top.get("asks").size()).isEqualTo(1);
		assertThat(top.get("sourceCommandSequence").asString()).isEqualTo("4");
		assertThat(top.toString()).as("no order or account ids").doesNotContain("orderId", "accountId");
	}

	@Test
	void bookRejectsDepthsOutsideTheContract() throws Exception {
		UUID instrument = UUID.randomUUID();
		snapshots.apply(Snapshots.snapshot(instrument, 1, 1));

		String path = "/api/v1/instruments/" + instrument + "/book";
		assertThat(code(get(path + "?depth=21"), 400)).isEqualTo("VALIDATION_FAILED");
		assertThat(code(get(path + "?depth=0"), 400)).isEqualTo("VALIDATION_FAILED");
		assertThat(code(get(path + "?depth=many"), 400)).isEqualTo("VALIDATION_FAILED");
		assertThat(code(get("/api/v1/instruments/" + UUID.randomUUID() + "/book"), 404))
			.isEqualTo("INSTRUMENT_NOT_FOUND");
		assertThat(code(get("/api/v1/instruments/not-a-uuid/book"), 400)).isEqualTo("VALIDATION_FAILED");
	}

	/** 120 trades in pages of 50, 50 and 20, newest first, with nothing repeated or skipped. */
	@Test
	void tradePagesWalkTheWholeTapeNewestFirst() throws Exception {
		UUID instrument = UUID.randomUUID();
		snapshots.apply(Snapshots.snapshot(instrument, 1, 1));
		for (long sequence = 1; sequence <= 120; sequence++) {
			trades.apply(Trades.trade(instrument, sequence));
		}

		List<Long> seen = new ArrayList<>();
		List<Integer> pageSizes = new ArrayList<>();
		String path = "/api/v1/instruments/" + instrument + "/trades";
		JsonNode page = json(get(path), 200);
		while (true) {
			pageSizes.add(page.get("trades").size());
			page.get("trades").forEach((trade) -> seen.add(Long.parseLong(trade.get("tradeSequence").asString())));
			if (page.get("nextCursor").isNull()) {
				break;
			}
			page = json(get(path + "?cursor=" + page.get("nextCursor").asString()), 200);
		}

		assertThat(pageSizes).containsExactly(50, 50, 20);
		assertThat(seen).hasSize(120).isSortedAccordingTo((a, b) -> Long.compare(b, a)).startsWith(120L).endsWith(1L);
		assertThat(page.get("latestTradeSequence").asString()).isEqualTo("120");
	}

	@Test
	void tradeQueriesAreBoundedAndValidated() throws Exception {
		UUID instrument = UUID.randomUUID();
		trades.apply(Trades.trade(instrument, 1));
		String path = "/api/v1/instruments/" + instrument + "/trades";

		JsonNode beforeAnySnapshot = json(get(path + "?limit=200"), 200);
		assertThat(beforeAnySnapshot.get("trades").get(0).get("price").asString()).isEqualTo("100.01");

		assertThat(code(get(path + "?limit=201"), 400)).isEqualTo("VALIDATION_FAILED");
		assertThat(code(get(path + "?limit=0"), 400)).isEqualTo("VALIDATION_FAILED");
		assertThat(code(get(path + "?cursor=bm90LWEtY3Vyc29y"), 400)).isEqualTo("VALIDATION_FAILED");
		assertThat(code(get(path + "?cursor=%25%25"), 400)).isEqualTo("VALIDATION_FAILED");
		assertThat(code(get("/api/v1/instruments/" + UUID.randomUUID() + "/trades"), 404))
			.isEqualTo("INSTRUMENT_NOT_FOUND");
	}

	@Test
	void onlyTheseReadsAreOpen() throws Exception {
		try (HttpClient client = HttpClient.newHttpClient()) {
			HttpResponse<String> post = client.send(
					HttpRequest.newBuilder(uri("/api/v1/instruments")).POST(HttpRequest.BodyPublishers.noBody()).build(),
					HttpResponse.BodyHandlers.ofString());
			assertThat(post.statusCode()).isIn(401, 403);
		}
		assertThat(get("/api/v1/instruments/" + UUID.randomUUID() + "/orders").statusCode()).isIn(401, 403);
	}

	private HttpResponse<String> get(String path) throws Exception {
		try (HttpClient client = HttpClient.newHttpClient()) {
			return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
		}
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}

	private static JsonNode json(HttpResponse<String> response, int status) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
		return JSON.readTree(response.body());
	}

	private static String code(HttpResponse<String> response, int status) {
		assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
		return json(response, status).get("code").asString();
	}

	private static JsonNode find(JsonNode instruments, UUID instrument) {
		for (JsonNode entry : instruments) {
			if (entry.get("instrumentId").asString().equals(instrument.toString())) {
				return entry;
			}
		}
		throw new AssertionError("instrument not listed: " + instrument);
	}


	/** A trade at a price, quantity and time of its own, after the test's instrument snapshot. */
	private static TradeExecuted trade(UUID instrument, long sequence, long priceCents, long quantity, Instant at) {
		return TradeExecuted.newBuilder(Trades.trade(instrument, sequence))
			.setPriceCents(priceCents)
			.setQuantity(quantity)
			.setExecutedAt(at)
			.build();
	}

}
