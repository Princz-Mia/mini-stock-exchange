package com.mia_princz.mse.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.mia_princz.mse.contracts.events.MarketSnapshot;
import com.mia_princz.mse.contracts.events.TradeExecuted;
import com.mia_princz.mse.marketdata.projection.SnapshotProjection;
import com.mia_princz.mse.marketdata.projection.CandleProjection;
import com.mia_princz.mse.marketdata.projection.TradeProjection;

/**
 * This service against {@code api/openapi.yaml}: every route it serves is in the contract under its tag, and every
 * status it answers with, success or problem, has the body the contract describes.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=api-contract", "mse.market-feed.enabled=false",
				"mse.trade-feed.enabled=false", "mse.candle-feed.enabled=false", "mse.market-events.heartbeat=300ms" })
class ApiContractIT {

	private static final OpenApiContract CONTRACT = OpenApiContract.get();

	@LocalServerPort
	int port;

	@Autowired
	SnapshotProjection snapshots;

	@Autowired
	CandleProjection candles;

	@Autowired
	TradeProjection trades;

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	RequestMappingHandlerMapping mappings;

	@Test
	void servedRoutesAndContractOperationsAreTheSame() {
		Set<String> served = new TreeSet<>();
		mappings.getHandlerMethods().keySet().forEach((info) -> info.getPathPatternsCondition().getPatterns()
			.stream()
			.map((pattern) -> pattern.getPatternString())
			.filter((path) -> path.startsWith("/api/") || path.startsWith("/internal/"))
			.forEach((path) -> info.getMethodsCondition().getMethods()
				.forEach((method) -> served.add(method.name() + " " + OpenApiContract.blankVariables(path)))));

		assertThat(served).isEqualTo(CONTRACT.operations("market-data"));
	}

	/** Every query parameter a route reads is in the contract under its operation, and the other way round. */
	@Test
	void servedQueryParametersAndTheContractsAreTheSame() {
		Set<String> served = new TreeSet<>();
		mappings.getHandlerMethods().forEach((info, handler) -> {
			for (MethodParameter parameter : handler.getMethodParameters()) {
				RequestParam query = parameter.getParameterAnnotation(RequestParam.class);
				if (query == null) {
					continue;
				}
				String name = query.name().isEmpty() ? parameter.getParameter().getName() : query.name();
				info.getPathPatternsCondition().getPatterns()
					.stream()
					.map((pattern) -> pattern.getPatternString())
					.filter((path) -> path.startsWith("/api/") || path.startsWith("/internal/"))
					.forEach((path) -> info.getMethodsCondition().getMethods()
						.forEach((method) -> served
							.add(method.name() + " " + OpenApiContract.blankVariables(path) + "?" + name)));
			}
		});

		assertThat(served).isEqualTo(CONTRACT.queryParameters("market-data"));
	}

	@Test
	void marketReadsMatchTheContract() throws Exception {
		UUID instrument = UUID.randomUUID();
		// One with a display name and one without, so both branches of the field are checked.
		MarketSnapshot named = Snapshots.snapshot(instrument, 3, 2);
		named.setDisplayName("Orbité Labs");
		snapshots.apply(named);
		// Before its first trade: no last price, an empty tape and no book.
		snapshots.apply(Snapshots.untraded(UUID.randomUUID(), 1));
		// A venue that has closed: the list and its book name the state.
		UUID closed = UUID.randomUUID();
		snapshots.apply(Snapshots.closed(closed, 2));
		for (long sequence = 1; sequence <= 3; sequence++) {
			trades.apply(Trades.trade(instrument, sequence));
		}
		// The last trade below the reference price, so the session's change and percentage are negative.
		trades.apply(TradeExecuted.newBuilder(Trades.trade(instrument, 4)).setPriceCents(9_050).build());
		String book = "/api/v1/instruments/{instrumentId}/book";
		String tape = "/api/v1/instruments/{instrumentId}/trades";

		String list = "/api/v1/instruments";
		check(list, list, 200);
		// One simulation's list by its id, a simulation never heard of, which has none, and an id that is not one.
		check(list, list + "?simulationId=" + Snapshots.SIMULATION, 200);
		check(list, list + "?simulationId=" + UUID.randomUUID(), 200);
		check(list, list + "?simulationId=nope", 400);
		check(book, path(book, instrument), 200);
		check(book, path(book, closed), 200);
		check(book, path(book, instrument) + "?depth=99", 400);
		check(book, path(book, UUID.randomUUID()), 404);
		check(tape, path(tape, instrument) + "?limit=2", 200);
		check(tape, path(tape, instrument) + "?cursor=nope", 400);
		check(tape, path(tape, UUID.randomUUID()), 404);

		String bars = "/api/v1/instruments/{instrumentId}/candles";
		candles.apply(Candles.candle(1, instrument, Candles.MINUTE, 3));
		check(bars, path(bars, instrument) + "?from=" + Candles.MINUTE + "&to=" + Candles.MINUTE.plusSeconds(60), 200);
		check(bars, path(bars, instrument), 200);
		check(bars, path(bars, instrument) + "?from=" + Candles.MINUTE + "&to=" + Candles.MINUTE.plusSeconds(86_460), 400);
		check(bars, path(bars, UUID.randomUUID()), 404);

		// A minute with asks only, then one with both and a trade, then a minute held: every nullable field both ways.
		String quality = "/api/v1/instruments/{instrumentId}/market-quality";
		UUID measured = UUID.randomUUID();
		snapshots.apply(Snapshots.book(measured, 1, Candles.MINUTE, null, 10_150L, null));
		snapshots.apply(Snapshots.book(measured, 2, Candles.MINUTE.plusSeconds(60), 10_000L, 10_150L, 10_050L));
		snapshots.apply(Snapshots.book(measured, 3, Candles.MINUTE.plusSeconds(180), 10_010L, 10_150L, 10_110L));
		check(quality, path(quality, measured) + "?fromSequence=1&toSequence=3", 200);
		check(quality, path(quality, measured) + "?fromSequence=9&toSequence=9", 200);
		check(quality, path(quality, measured) + "?fromSequence=1", 400);
		check(quality, path(quality, measured) + "?fromSequence=3&toSequence=2", 400);
		check(quality, path(quality, UUID.randomUUID()) + "?fromSequence=1&toSequence=3", 404);
		// No export token in this context, so reconciliation says the export is unavailable.
		check("/internal/reconciliation/candles", "/internal/reconciliation/candles", 503);
	}

	@Test
	void streamAndItsEventsMatchTheContract() throws Exception {
		UUID instrument = UUID.randomUUID();
		MarketSnapshot named = Snapshots.snapshot(instrument, 1, 3);
		named.setDisplayName("Nova Dynamics");
		snapshots.apply(named);
		trades.apply(Trades.trade(instrument, 1));
		String events = "/api/v1/market-events";

		check(events, events + "?instrumentId=" + UUID.randomUUID(), 404);
		check(events, events, 400);

		HttpResponse<Stream<String>> stream = HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(uri(events + "?instrumentId=" + instrument))
				.header("Accept", "text/event-stream")
				.build(), HttpResponse.BodyHandlers.ofLines());
		assertThat(stream.statusCode()).isEqualTo(200);
		CONTRACT.assertResponse("GET", events, 200, stream.headers().firstValue("Content-Type").orElseThrow(), null);
		try (Stream<String> lines = stream.body()) {
			Iterator<String> reader = lines.iterator();
			boolean frame = false;
			boolean heartbeat = false;
			String name = null;
			while (!(frame && heartbeat) && reader.hasNext()) {
				String line = reader.next();
				if (line.startsWith("event:")) {
					name = line.substring("event:".length()).trim();
				}
				else if (line.startsWith("data:")) {
					String data = line.substring("data:".length());
					if ("market".equals(name)) {
						CONTRACT.assertSchema("MarketFrame", data);
						frame = true;
					}
					else if ("heartbeat".equals(name)) {
						CONTRACT.assertSchema("Heartbeat", data);
						heartbeat = true;
					}
				}
			}
			assertThat(frame && heartbeat).as("a frame and a heartbeat").isTrue();
		}
	}

	private void check(String template, String path, int status) throws Exception {
		try (HttpClient client = HttpClient.newHttpClient()) {
			HttpResponse<String> response = client.send(HttpRequest.newBuilder(uri(path)).build(),
					HttpResponse.BodyHandlers.ofString());
			assertThat(response.statusCode()).as(path).isEqualTo(status);
			CONTRACT.assertResponse("GET", template, status,
					response.headers().firstValue("Content-Type").orElse(null), response.body());
		}
	}

	private static String path(String template, UUID instrument) {
		return template.replace("{instrumentId}", instrument.toString());
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}

}
