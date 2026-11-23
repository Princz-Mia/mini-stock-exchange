package com.mia_princz.mse.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.mia_princz.mse.contracts.events.MarketSnapshot;
import com.mia_princz.mse.contracts.events.TradeExecuted;
import com.mia_princz.mse.marketdata.projection.CandleProjection;
import com.mia_princz.mse.marketdata.projection.SnapshotProjection;
import com.mia_princz.mse.marketdata.projection.TradeProjection;

/**
 * The instrument list once more than one simulation has instruments, usually under the same symbols: one simulation's
 * at a time, the current one's unless another is asked for. Which simulation is current depends on every market
 * stored, so each test starts from empty projections. The feeds are off.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=instrument-list", "mse.market-feed.enabled=false",
				"mse.trade-feed.enabled=false", "mse.candle-feed.enabled=false" })
class InstrumentListIT {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String LIST = "/api/v1/instruments";

	/** The older simulation opened, ran for an hour and closed; the newer one started five minutes after that. */
	private static final Instant OPENED = Instant.parse("2026-11-20T09:00:00Z");

	private static final Instant CLOSED = OPENED.plusSeconds(3_600);

	private static final Instant STARTED = CLOSED.plusSeconds(300);

	@LocalServerPort
	int port;

	@Autowired
	SnapshotProjection snapshots;

	@Autowired
	TradeProjection trades;

	@Autowired
	CandleProjection candles;

	@Autowired
	PostgreSQLContainer postgres;

	@Autowired
	PlatformTransactionManager transactionManager;

	private final UUID older = UUID.randomUUID();

	private final UUID newer = UUID.randomUUID();

	private final UUID oldNova = UUID.randomUUID();

	private final UUID oldVega = UUID.randomUUID();

	private final UUID newNova = UUID.randomUUID();

	private final UUID newVega = UUID.randomUUID();

	@BeforeEach
	void emptyProjections() throws Exception {
		wipeProjections();
	}

	/** Two simulations with the same symbols, the older one closed: each list is one simulation's own. */
	@Test
	void theListIsOneSimulationsAndTheNewerOnesByDefault() throws Exception {
		// The older simulation counted far more commands: a sequence does not say which simulation is newer.
		snapshots.apply(Snapshots.in(older, "NOVA", CLOSED, Snapshots.closed(oldNova, 9_000)));
		snapshots.apply(Snapshots.in(older, "VEGA", CLOSED, Snapshots.closed(oldVega, 9_000)));
		snapshots.apply(Snapshots.in(newer, "VEGA", STARTED, Snapshots.setup(newVega, 1)));
		snapshots.apply(Snapshots.in(newer, "NOVA", STARTED.plusSeconds(60), Snapshots.untraded(newNova, 2)));
		for (long sequence = 1; sequence <= 3; sequence++) {
			trades.apply(trade(older, oldNova, sequence));
		}

		JsonNode current = json(get(LIST), 200);

		assertThat(current.get("simulationId").asString()).isEqualTo(newer.toString());
		assertThat(listed(current)).as("the newer simulation's only, by symbol").containsExactly(newNova, newVega);
		assertThat(current.get("instruments").get(0).get("symbol").asString()).isEqualTo("NOVA");
		assertThat(current.get("instruments").get(0).get("venueState").asString()).isEqualTo("OPEN");
		assertThat(current.get("instruments").get(0).get("session").get("tradeCount").asLong())
			.as("the other simulation's NOVA traded, this one has not")
			.isZero();
		assertThat(current.get("instruments").get(1).get("venueState").asString()).isEqualTo("SETUP");

		assertThat(json(get(LIST + "?simulationId=" + newer), 200)).as("asked for by id").isEqualTo(current);
		JsonNode closed = json(get(LIST + "?simulationId=" + older), 200);
		assertThat(closed.get("simulationId").asString()).isEqualTo(older.toString());
		assertThat(listed(closed)).containsExactly(oldNova, oldVega);
		assertThat(closed.get("instruments").get(0).get("venueState").asString()).isEqualTo("CLOSED");
		assertThat(closed.get("instruments").get(0).get("session").get("tradeCount").asLong()).isEqualTo(3);
	}

	/** Nothing is running: the list is still one simulation's, the one that closed last. */
	@Test
	void withEverySimulationClosedTheListIsTheNewestOnes() throws Exception {
		snapshots.apply(Snapshots.in(newer, "NOVA", STARTED.plusSeconds(7_200), Snapshots.closed(newNova, 40)));
		snapshots.apply(Snapshots.in(older, "NOVA", CLOSED, Snapshots.closed(oldNova, 9_000)));
		snapshots.apply(Snapshots.in(older, "VEGA", CLOSED, Snapshots.closed(oldVega, 9_000)));

		JsonNode current = json(get(LIST), 200);

		assertThat(current.get("simulationId").asString()).isEqualTo(newer.toString());
		assertThat(listed(current)).containsExactly(newNova);
		assertThat(current.get("instruments").get(0).get("venueState").asString()).isEqualTo("CLOSED");
	}

	/**
	 * A rebuild reads the topic's partitions in no set order, so the older simulation's snapshots may be applied after
	 * the newer one's, and each instrument's newest before its earlier ones. Applied one by one in the opposite order,
	 * and again as one poll with the newer simulation first, both lists come out as they were.
	 */
	@Test
	void aRebuildThatReadsTheNewerSimulationFirstListsTheSame() throws Exception {
		List<MarketSnapshot> past = List.of(Snapshots.in(older, "NOVA", OPENED, Snapshots.snapshot(oldNova, 400, 2)),
				Snapshots.in(older, "NOVA", CLOSED, Snapshots.closed(oldNova, 9_000)),
				Snapshots.in(older, "VEGA", CLOSED, Snapshots.closed(oldVega, 9_000)));
		List<MarketSnapshot> present = List.of(Snapshots.in(newer, "NOVA", STARTED, Snapshots.setup(newNova, 1)),
				Snapshots.in(newer, "VEGA", STARTED.plusSeconds(30), Snapshots.setup(newVega, 2)),
				Snapshots.in(newer, "NOVA", STARTED.plusSeconds(60), Snapshots.snapshot(newNova, 5, 1)));
		past.forEach(snapshots::apply);
		present.forEach(snapshots::apply);
		String current = get(LIST).body();
		String closed = get(LIST + "?simulationId=" + older).body();
		assertThat(listed(JSON.readTree(current))).containsExactly(newNova, newVega);
		assertThat(listed(JSON.readTree(closed))).containsExactly(oldNova, oldVega);

		wipeProjections();
		present.reversed().forEach(snapshots::apply);
		past.reversed().forEach(snapshots::apply);

		assertThat(get(LIST).body()).as("one by one, the newer simulation first").isEqualTo(current);
		assertThat(get(LIST + "?simulationId=" + older).body()).isEqualTo(closed);

		wipeProjections();
		List<MarketSnapshot> poll = new ArrayList<>(present);
		poll.addAll(past);
		new TransactionTemplate(transactionManager).executeWithoutResult((status) -> snapshots.applyAll(poll, (changed) -> {
		}));

		assertThat(get(LIST).body()).as("in one poll, the newer simulation first").isEqualTo(current);
		assertThat(get(LIST + "?simulationId=" + older).body()).isEqualTo(closed);
	}

	/**
	 * A new simulation is known here from its first instrument on, even one still being set up. Before that the
	 * default is the closed simulation's list, and only asking for the new one by id shows that it has none yet.
	 */
	@Test
	void aNewSimulationIsCurrentFromItsFirstInstrumentOn() throws Exception {
		snapshots.apply(Snapshots.in(older, "NOVA", CLOSED, Snapshots.closed(oldNova, 9_000)));
		snapshots.apply(Snapshots.in(older, "VEGA", CLOSED, Snapshots.closed(oldVega, 9_000)));

		assertThat(listed(json(get(LIST), 200))).containsExactly(oldNova, oldVega);
		JsonNode nothingYet = json(get(LIST + "?simulationId=" + newer), 200);
		assertThat(nothingYet.get("simulationId").asString()).isEqualTo(newer.toString());
		assertThat(nothingYet.get("instruments")).isEmpty();

		snapshots.apply(Snapshots.in(newer, "NOVA", STARTED, Snapshots.setup(newNova, 1)));

		JsonNode current = json(get(LIST), 200);
		assertThat(current.get("simulationId").asString()).isEqualTo(newer.toString());
		assertThat(listed(current)).containsExactly(newNova);
		assertThat(current.get("instruments").get(0).get("venueState").asString()).isEqualTo("SETUP");
		assertThat(listed(json(get(LIST + "?simulationId=" + older), 200))).containsExactly(oldNova, oldVega);
	}

	/**
	 * An instrument of the older simulation never got its closing snapshot and still says OPEN, while the newer
	 * simulation has closed. The state a market was left in does not make its simulation the current one.
	 */
	@Test
	void anOlderSimulationLeftOpenIsNotTheCurrentOne() throws Exception {
		snapshots.apply(Snapshots.in(older, "NOVA", OPENED.plusSeconds(1_800), Snapshots.snapshot(oldNova, 400, 1)));
		snapshots.apply(Snapshots.in(older, "VEGA", CLOSED, Snapshots.closed(oldVega, 9_000)));
		snapshots.apply(Snapshots.in(newer, "NOVA", STARTED.plusSeconds(7_200), Snapshots.closed(newNova, 40)));

		JsonNode current = json(get(LIST), 200);

		assertThat(current.get("simulationId").asString()).isEqualTo(newer.toString());
		assertThat(listed(current)).containsExactly(newNova);
		JsonNode left = json(get(LIST + "?simulationId=" + older), 200);
		assertThat(listed(left)).containsExactly(oldNova, oldVega);
		assertThat(left.get("instruments").get(0).get("venueState").asString()).isEqualTo("OPEN");
	}

	/**
	 * An instrument removed during setup leaves its simulation's list as before, and the other simulation's
	 * instrument with its symbol stays. With its last instrument removed the new simulation is no longer known here,
	 * so the default is the closed one's list again; asked for by id, the new one has none.
	 */
	@Test
	void aRemovedInstrumentLeavesItsSimulationsList() throws Exception {
		snapshots.apply(Snapshots.in(older, "NOVA", CLOSED, Snapshots.closed(oldNova, 9_000)));
		snapshots.apply(Snapshots.in(newer, "NOVA", STARTED, Snapshots.setup(newNova, 1)));
		snapshots.apply(Snapshots.in(newer, "VEGA", STARTED.plusSeconds(10), Snapshots.setup(newVega, 2)));
		assertThat(listed(json(get(LIST), 200))).containsExactly(newNova, newVega);

		snapshots.apply(Snapshots.in(newer, "VEGA", STARTED.plusSeconds(20), Snapshots.removed(newVega, 3)));

		assertThat(listed(json(get(LIST), 200))).containsExactly(newNova);
		assertThat(get(LIST + "/" + newVega + "/book").statusCode()).isEqualTo(404);

		snapshots.apply(Snapshots.in(newer, "NOVA", STARTED.plusSeconds(30), Snapshots.removed(newNova, 4)));

		JsonNode fallenBack = json(get(LIST), 200);
		assertThat(fallenBack.get("simulationId").asString()).isEqualTo(older.toString());
		assertThat(listed(fallenBack)).containsExactly(oldNova);
		JsonNode emptied = json(get(LIST + "?simulationId=" + newer), 200);
		assertThat(emptied.get("simulationId").asString()).isEqualTo(newer.toString());
		assertThat(emptied.get("instruments")).isEmpty();
	}

	/** A simulation never heard of has no instruments; an id that is not one, or an empty one, is refused. */
	@Test
	void anUnknownSimulationHasNoneAndABadIdIsRefused() throws Exception {
		snapshots.apply(Snapshots.in(older, "NOVA", CLOSED, Snapshots.closed(oldNova, 9_000)));
		UUID unknown = UUID.randomUUID();

		JsonNode none = json(get(LIST + "?simulationId=" + unknown), 200);

		assertThat(none.get("simulationId").asString()).isEqualTo(unknown.toString());
		assertThat(none.get("instruments")).isEmpty();
		for (String bad : List.of("nope", "", "%20", older + "0")) {
			HttpResponse<String> refused = get(LIST + "?simulationId=" + bad);
			assertThat(refused.headers().firstValue("Content-Type")).as(bad).hasValue("application/problem+json");
			JsonNode problem = json(refused, 400);
			assertThat(problem.get("code").asString()).as(bad).isEqualTo("VALIDATION_FAILED");
			assertThat(problem.get("errors").get(0).get("field").asString()).isEqualTo("simulationId");
			OpenApiContract.get().assertResponse("GET", LIST, 400, "application/problem+json", refused.body());
		}
	}

	/** Before any instrument exists there is no simulation to name: an empty list, as the contract allows. */
	@Test
	void withNoInstrumentAtAllTheListNamesNoSimulation() throws Exception {
		HttpResponse<String> response = get(LIST);

		JsonNode empty = json(response, 200);
		assertThat(empty.get("simulationId").isNull()).isTrue();
		assertThat(empty.get("instruments")).isEmpty();
		OpenApiContract.get()
			.assertResponse("GET", LIST, 200, response.headers().firstValue("Content-Type").orElseThrow(),
					response.body());
	}

	/**
	 * Two simulations whose newest snapshots carry the same instant: the one with the higher id is listed, whichever
	 * was stored first, so the answer never depends on where a row happens to lie. The lower simulation's
	 * instrument ids and command sequences lie on both sides of the higher one's, so that neither end of either picks
	 * the simulation its own id does.
	 */
	@Test
	void simulationsNewestAtTheSameInstantAreToldApartById() throws Exception {
		UUID low = UUID.fromString("0fffffff-ffff-4fff-8fff-ffffffffffff");
		UUID high = UUID.fromString("f0000000-0000-4000-8000-000000000000");
		UUID lowest = UUID.fromString("10000000-0000-4000-8000-000000000000");
		UUID lower = UUID.fromString("40000000-0000-4000-8000-000000000000");
		UUID higher = UUID.fromString("b0000000-0000-4000-8000-000000000000");
		UUID highest = UUID.fromString("e0000000-0000-4000-8000-000000000000");
		List<MarketSnapshot> ofLow = List.of(Snapshots.in(low, "NOVA", CLOSED, Snapshots.closed(lowest, 1)),
				Snapshots.in(low, "VEGA", CLOSED, Snapshots.closed(highest, 9_000)));
		List<MarketSnapshot> ofHigh = List.of(Snapshots.in(high, "NOVA", CLOSED, Snapshots.closed(lower, 40)),
				Snapshots.in(high, "VEGA", CLOSED, Snapshots.closed(higher, 40)));

		ofLow.forEach(snapshots::apply);
		ofHigh.forEach(snapshots::apply);
		JsonNode storedLast = json(get(LIST), 200);
		assertThat(storedLast.get("simulationId").asString()).isEqualTo(high.toString());
		assertThat(listed(storedLast)).containsExactly(lower, higher);

		wipeProjections();
		ofHigh.reversed().forEach(snapshots::apply);
		ofLow.reversed().forEach(snapshots::apply);
		JsonNode storedFirst = json(get(LIST), 200);
		assertThat(storedFirst.get("simulationId").asString()).isEqualTo(high.toString());
		assertThat(listed(storedFirst)).containsExactly(lower, higher);
	}

	/** A simulation that is no longer listed keeps its history: every read by instrument id still serves it. */
	@Test
	void anInstrumentNoLongerListedIsStillReadById() throws Exception {
		snapshots.apply(Snapshots.in(older, "NOVA", OPENED, Snapshots.book(oldNova, 400, OPENED, 9_990L, 10_010L, 10_000L)));
		snapshots.apply(Snapshots.in(older, "NOVA", CLOSED, Snapshots.closed(oldNova, 9_000)));
		for (long sequence = 1; sequence <= 3; sequence++) {
			trades.apply(trade(older, oldNova, sequence));
		}
		candles.apply(Candles.candle(1, oldNova, Candles.MINUTE, 3));
		snapshots.apply(Snapshots.in(newer, "NOVA", STARTED, Snapshots.setup(newNova, 1)));
		assertThat(listed(json(get(LIST), 200))).containsExactly(newNova);
		String byId = LIST + "/" + oldNova;

		JsonNode book = json(get(byId + "/book"), 200);
		assertThat(book.get("venueState").asString()).isEqualTo("CLOSED");
		assertThat(book.get("sourceCommandSequence").asString()).isEqualTo("9000");
		assertThat(json(get(byId + "/trades"), 200).get("trades")).hasSize(3);
		assertThat(json(get(byId + "/candles?from=" + Candles.MINUTE + "&to=" + Candles.MINUTE.plusSeconds(60)), 200)
			.get("candles")).hasSize(1);
		assertThat(json(get(byId + "/market-quality?fromSequence=1&toSequence=9000"), 200).get("minutes")).isNotEmpty();
		HttpResponse<Stream<String>> stream = HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(uri("/api/v1/market-events?instrumentId=" + oldNova))
				.header("Accept", "text/event-stream")
				.build(), HttpResponse.BodyHandlers.ofLines());
		assertThat(stream.statusCode()).isEqualTo(200);
		try (Stream<String> lines = stream.body()) {
			String frame = lines.filter((line) -> line.startsWith("data:")).findFirst().orElseThrow();
			JsonNode market = JSON.readTree(frame.substring("data:".length()));
			assertThat(market.get("instrumentId").asString()).isEqualTo(oldNova.toString());
			assertThat(market.get("venueState").asString()).isEqualTo("CLOSED");
		}
	}

	/** Empties what the market topic is projected into, as the owning role: the runtime role may not. */
	private void wipeProjections() throws Exception {
		try (Connection owner = DriverManager.getConnection(TestcontainersConfiguration.jdbcUrl(postgres, "market_data_db"),
				"market_data_owner", TestcontainersConfiguration.MARKET_DATA_OWNER_PASSWORD);
				Statement wipe = owner.createStatement()) {
			wipe.execute("TRUNCATE market_snapshot, instrument_projection, processed_event, spread_minute");
		}
	}

	private static TradeExecuted trade(UUID simulation, UUID instrument, long sequence) {
		return TradeExecuted.newBuilder(Trades.trade(instrument, sequence)).setSimulationId(simulation).build();
	}

	/** The listed instruments' ids, in the order served. */
	private static List<UUID> listed(JsonNode body) {
		List<UUID> ids = new ArrayList<>();
		body.get("instruments").forEach((entry) -> ids.add(UUID.fromString(entry.get("instrumentId").asString())));
		return ids;
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

}
