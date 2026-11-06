package com.mia_princz.mse.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.mia_princz.mse.contracts.events.CandleUpdated;
import com.mia_princz.mse.marketdata.projection.CandleProjection;
import com.mia_princz.mse.marketdata.projection.SnapshotProjection;

/**
 * Reconciliation over HTTP against a stand-in for Execution's export: it pages by two, wants the token, and records
 * what it was asked, so the client's paging is visible. Candles go in through the projection. The feeds are off.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=candle-reconciliation", "mse.market-feed.enabled=false",
				"mse.trade-feed.enabled=false", "mse.candle-feed.enabled=false",
				"mse.exports.token=" + CandleReconciliationIT.TOKEN })
class CandleReconciliationIT {

	static final String TOKEN = "test-reconciliation-token";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final OpenApiContract CONTRACT = OpenApiContract.get();

	private static final String PATH = "/internal/reconciliation/candles";

	/** Instrument to its exported trades: sequence, price in cents, quantity, seconds after the minute. */
	private static final Map<UUID, List<long[]>> EXPORTED = new ConcurrentHashMap<>();

	private static final List<String> ASKED = Collections.synchronizedList(new ArrayList<>());

	private static final HttpServer EXPORT = start();

	@DynamicPropertySource
	static void execution(DynamicPropertyRegistry registry) {
		registry.add("mse.exports.execution-url", () -> "http://localhost:" + EXPORT.getAddress().getPort());
	}

	@AfterAll
	static void stop() {
		EXPORT.stop(0);
	}

	@LocalServerPort
	int port;

	@Autowired
	SnapshotProjection snapshots;

	@Autowired
	CandleProjection candles;

	@Test
	void candlesThatMatchTheExportReconcileAcrossPages() throws Exception {
		UUID instrument = UUID.randomUUID();
		snapshots.apply(Snapshots.snapshot(instrument, 9, 1));
		// Five trades of 5 shares at 100.01 … 100.05, three in the first minute and two in the next.
		EXPORTED.put(instrument, List.of(t(1, 10_001, 5, 0), t(2, 10_002, 5, 10), t(3, 10_003, 5, 20),
				t(4, 10_004, 5, 60), t(5, 10_005, 5, 70)));
		candles.apply(Candles.candle(1, instrument, Candles.MINUTE, 3));
		candles.apply(minute2(instrument));

		HttpResponse<String> response = get("?instrumentId=" + instrument);
		CONTRACT.assertResponse("GET", PATH, 200, response.headers().firstValue("Content-Type").orElse(null),
				response.body());
		JsonNode body = json(response, 200);

		assertThat(body.get("reconciled").asBoolean()).as(response.body()).isTrue();
		JsonNode report = body.get("instruments").get(0);
		assertThat(report.get("candlesChecked").asInt()).isEqualTo(2);
		assertThat(report.get("candlesThroughTradeSequence").asString()).isEqualTo("5");
		List<String> asked = ASKED.stream().filter((query) -> query.contains(instrument.toString())).toList();
		assertThat(asked).as("three pages of two").hasSize(3);
		assertThat(asked.get(0)).doesNotContain("throughSequence");
		assertThat(asked.subList(1, 3)).allSatisfy((query) -> assertThat(query).contains("throughSequence=5"));
	}

	@Test
	void aCandleThatDisagreesWithTheTradesIsReported() throws Exception {
		UUID instrument = UUID.randomUUID();
		snapshots.apply(Snapshots.snapshot(instrument, 9, 1));
		EXPORTED.put(instrument, List.of(t(1, 10_001, 5, 0), t(2, 10_002, 5, 10), t(3, 10_003, 5, 20),
				t(4, 10_004, 5, 60), t(5, 10_005, 5, 70)));
		candles.apply(Candles.candle(1, instrument, Candles.MINUTE, 3));
		// Minute two claims three trades where the export has two.
		candles.apply(CandleUpdated.newBuilder(minute2(instrument)).setTradeCount(3).build());

		JsonNode body = json(get("?instrumentId=" + instrument), 200);

		assertThat(body.get("reconciled").asBoolean()).isFalse();
		JsonNode mismatch = body.get("instruments").get(0).get("mismatches").get(0);
		assertThat(mismatch.get("problem").asString()).isEqualTo("DIFFERENT");
		assertThat(mismatch.get("detail").asString()).isEqualTo("tradeCount trades 2, candle 3");
	}

	@Test
	void everyInstrumentWithASnapshotIsCheckedAndOnlyThose() throws Exception {
		UUID listed = UUID.randomUUID();
		snapshots.apply(Snapshots.snapshot(listed, 9, 1));
		EXPORTED.put(listed, List.of(t(1, 10_001, 5, 0)));
		candles.apply(Candles.candle(1, listed, Candles.MINUTE, 1));
		// Candles for an instrument Execution never had, as the gate's smoke trades are: no snapshot, so not checked.
		candles.apply(Candles.candle(1, UUID.randomUUID(), Candles.MINUTE, 2));

		JsonNode body = json(get(""), 200);

		List<String> checked = new ArrayList<>();
		body.get("instruments").forEach((report) -> checked.add(report.get("instrumentId").asString()));
		assertThat(checked).contains(listed.toString());
		assertThat(body.get("computationEpoch").asLong()).isEqualTo(1);
	}

	@Test
	void anotherEpochIsCheckedOnRequest() throws Exception {
		UUID instrument = UUID.randomUUID();
		snapshots.apply(Snapshots.snapshot(instrument, 9, 1));
		EXPORTED.put(instrument, List.of(t(1, 10_001, 5, 0), t(2, 10_002, 5, 10)));
		candles.apply(Candles.candle(1, instrument, Candles.MINUTE, 2));
		candles.apply(
				CandleUpdated.newBuilder(Candles.candle(2, instrument, Candles.MINUTE, 2)).setTradeCount(1).build());

		assertThat(json(get("?instrumentId=" + instrument), 200).get("reconciled").asBoolean()).isTrue();
		JsonNode two = json(get("?instrumentId=" + instrument + "&epoch=2"), 200);
		assertThat(two.get("computationEpoch").asLong()).isEqualTo(2);
		assertThat(two.get("reconciled").asBoolean()).isFalse();
	}

	@Test
	void anExportThatRefusesUsIsReportedAsUnavailable() throws Exception {
		// The stand-in answers 404 for an instrument it has no trades for. No snapshot, so the other tests' "every
		// instrument" check never meets it.
		UUID instrument = UUID.randomUUID();
		HttpResponse<String> response = get("?instrumentId=" + instrument);
		CONTRACT.assertResponse("GET", PATH, 503, response.headers().firstValue("Content-Type").orElse(null),
				response.body());
		assertThat(json(response, 503).get("code").asString()).isEqualTo("EXPORT_UNAVAILABLE");
	}

	/** The second minute: trades 4 and 5, of 5 shares each. */
	private static CandleUpdated minute2(UUID instrument) {
		return CandleUpdated.newBuilder(Candles.candle(1, instrument, Candles.MINUTE.plusSeconds(60), 1))
			.setOpenCents(10_004)
			.setHighCents(10_005)
			.setLowCents(10_004)
			.setCloseCents(10_005)
			.setVolume(10)
			.setTradeCount(2)
			.setNotionalCents(BigDecimal.valueOf(100_045))
			.setSourceMaxTradeSequence(5)
			.build();
	}

	private static long[] t(long sequence, long priceCents, long quantity, long seconds) {
		return new long[] { sequence, priceCents, quantity, seconds };
	}

	private HttpResponse<String> get(String query) throws Exception {
		try (HttpClient client = HttpClient.newHttpClient()) {
			return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH + query)).build(),
					HttpResponse.BodyHandlers.ofString());
		}
	}

	private static JsonNode json(HttpResponse<String> response, int status) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
		return JSON.readTree(response.body());
	}

	/** Pages of two through a captured high-water mark, like the real export. */
	private static HttpServer start() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			server.createContext("/internal/exports/trades", CandleReconciliationIT::serve);
			server.start();
			return server;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static void serve(HttpExchange exchange) throws IOException {
		String query = exchange.getRequestURI().getRawQuery();
		ASKED.add(query);
		Map<String, String> params = new HashMap<>();
		for (String pair : query.split("&")) {
			String[] parts = pair.split("=", 2);
			params.put(parts[0], URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
		}
		List<long[]> trades = EXPORTED.get(UUID.fromString(params.get("instrumentId")));
		if (!("Bearer " + TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization")) || trades == null) {
			exchange.sendResponseHeaders(trades == null ? 404 : 401, -1);
			exchange.close();
			return;
		}
		long through = params.containsKey("throughSequence") ? Long.parseLong(params.get("throughSequence"))
				: trades.get(trades.size() - 1)[0];
		long after = params.containsKey("cursor") ? Long.parseLong(params.get("cursor")) : 0;
		List<String> rows = new ArrayList<>();
		long last = after;
		for (long[] trade : trades) {
			if (trade[0] > after && trade[0] <= through && rows.size() < 2) {
				rows.add("{\"tradeId\":\"" + UUID.randomUUID() + "\",\"tradeSequence\":\"" + trade[0]
						+ "\",\"priceCents\":" + trade[1] + ",\"quantity\":" + trade[2] + ",\"executedAt\":\""
						+ Candles.MINUTE.plusSeconds(trade[3]) + "\",\"commandSequence\":\"" + trade[0] + "\"}");
				last = trade[0];
			}
		}
		String next = last < through ? "\"" + last + "\"" : "null";
		byte[] body = ("{\"instrumentId\":\"" + params.get("instrumentId")
				+ "\",\"symbol\":\"NOVA\",\"throughSequence\":\"" + through + "\",\"trades\":[" + String.join(",", rows)
				+ "],\"nextCursor\":" + next + "}")
			.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}

}
