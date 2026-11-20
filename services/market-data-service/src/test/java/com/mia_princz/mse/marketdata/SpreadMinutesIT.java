package com.mia_princz.mse.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.mia_princz.mse.contracts.events.MarketSnapshot;
import com.mia_princz.mse.marketdata.projection.SnapshotProjection;
import com.mia_princz.mse.marketdata.projection.UnusableRecordException;

/**
 * Each instrument's book minute by minute, against an oracle that sees only the snapshots it sent. A seeded stream
 * per round: sequences shared by three instruments with gaps, as one simulation's are; times mostly rising with the
 * writer's clock stepping back now and then; books two-sided, one-sided or empty; a last price from the first trade
 * on. Delivered in any order with repeats, one by one and in polls of any size, the rows end as the oracle's. Windows
 * over HTTP are measured as the oracle measures them from what it sent. The feeds are off.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=spread-minutes", "mse.market-feed.enabled=false",
				"mse.trade-feed.enabled=false", "mse.candle-feed.enabled=false" })
class SpreadMinutesIT {

	private static final long SEED = 20_261_120L;

	private static final int INSTRUMENTS = 3;

	private static final Instant START = Instant.parse("2026-11-20T09:00:00Z");

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final HttpClient HTTP = HttpClient.newHttpClient();

	@LocalServerPort
	int port;

	@Autowired
	SnapshotProjection snapshots;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PlatformTransactionManager transactionManager;

	/** A snapshot as the oracle knows it: its instrument by number, null for an empty side or no trade yet. */
	record Sent(int instrument, long sequence, Instant at, Long bid, Long ask, Long price) {

		MarketSnapshot snapshot(List<UUID> instruments) {
			return Snapshots.book(instruments.get(instrument), sequence, at, bid, ask, price);
		}

	}

	@Test
	void minutesEndAsTheOracleSaysInAnyOrderOneByOneOrInPolls() {
		Random random = new Random(SEED);
		for (int round = 0; round < 4; round++) {
			List<Sent> sent = stream(random, 400);
			List<UUID> oneByOne = instruments();
			List<UUID> inPolls = instruments();

			List<MarketSnapshot> first = delivery(random, sent, oneByOne);
			inTransaction(() -> first.forEach(snapshots::apply));
			List<MarketSnapshot> second = delivery(random, sent, inPolls);
			for (int from = 0; from < second.size();) {
				List<MarketSnapshot> poll = second.subList(from, Math.min(second.size(), from + 1 + random.nextInt(60)));
				inTransaction(() -> snapshots.applyAll(poll, (instrument) -> {
				}).orElseThrow());
				from += poll.size();
			}

			List<String> expected = Oracle.rows(sent);
			assertThat(rows(oneByOne)).as("round %d one by one", round).isEqualTo(expected).hasSizeGreaterThan(60);
			assertThat(rows(inPolls)).as("round %d in polls", round).isEqualTo(expected);
		}
	}

	@Test
	void aWindowIsMeasuredAsTheOracleMeasuresIt() throws Exception {
		Random random = new Random(SEED + 1);
		List<Sent> sent = stream(random, 600);
		List<UUID> instruments = instruments();
		inTransaction(() -> delivery(random, sent, instruments).forEach(snapshots::apply));
		long last = sent.getLast().sequence();

		int measured = 0;
		for (int window = 0; window < 60; window++) {
			int instrument = random.nextInt(INSTRUMENTS);
			long from = 1 + random.nextInt((int) last + 10);
			long to = window % 10 == 0 ? from : from + random.nextInt((int) last / 3 + 1);
			JsonNode body = quality(instruments.get(instrument), from, to, 200);
			Oracle.Expected expected = Oracle.measure(sent, instrument, from, to);
			assertThat(served(body)).as("instrument %d, %d..%d", instrument, from, to).isEqualTo(expected.served());
			if (expected.returns() >= 2) {
				measured++;
			}
		}
		assertThat(measured).as("windows with a volatility").isGreaterThan(20);
	}

	/**
	 * Worked by hand. Minutes 09:00 to 09:04: spreads 0.20, none (one-sided), 0.03, 0.03 held, 0.03; prices 100.00,
	 * 101.00, 99.99, held, 99.99. The average spread is (20 + 3 + 3 + 3) / 4 cents, 0.0725. The returns are 0.01,
	 * -0.01, 0 and 0, with mean 0, so the sample deviation is sqrt(0.0002 / 3) = 0.008165 to six places. Over the first
	 * three minutes only: (20 + 3) / 2 = 0.1150, and sqrt(0.0002 / 1) = 0.014142.
	 */
	@Test
	void theFiguresAreTheOnesWorkedByHand() throws Exception {
		UUID instrument = UUID.randomUUID();
		snapshots.apply(Snapshots.book(instrument, 10, START.plusSeconds(5), 9_990L, 10_010L, 10_000L));
		snapshots.apply(Snapshots.book(instrument, 11, START.plusSeconds(70), 9_995L, null, 10_100L));
		snapshots.apply(Snapshots.book(instrument, 12, START.plusSeconds(130), 9_998L, 10_001L, 9_999L));
		snapshots.apply(Snapshots.book(instrument, 14, START.plusSeconds(250), 9_998L, 10_001L, 9_999L));

		JsonNode body = quality(instrument, 10, 14, 200);

		assertThat(body.get("minutes")).extracting(SpreadMinutesIT::minute).containsExactly(
				"09:00 99.90 100.10 0.20 100.00 changed", "09:01 99.95 - - 101.00 changed",
				"09:02 99.98 100.01 0.03 99.99 changed", "09:03 99.98 100.01 0.03 99.99 held",
				"09:04 99.98 100.01 0.03 99.99 changed");
		assertThat(body.get("averageSpread").asString()).isEqualTo("0.0725");
		assertThat(body.get("twoSidedMinutes").asInt()).isEqualTo(4);
		assertThat(body.get("volatility").asString()).isEqualTo("0.008165");
		assertThat(body.get("returns").asInt()).isEqualTo(4);

		// Only the first three minutes.
		JsonNode three = quality(instrument, 10, 12, 200);
		assertThat(three.get("averageSpread").asString()).isEqualTo("0.1150");
		assertThat(three.get("volatility").asString()).isEqualTo("0.014142");
		// From the second minute on: the minute before the window is not carried into it.
		JsonNode later = quality(instrument, 11, 14, 200);
		assertThat(later.get("minutes").get(0).get("minute").asString()).isEqualTo("2026-11-20T09:01:00Z");
		assertThat(later.get("minutes")).hasSize(4);
	}

	/**
	 * Of two snapshots with one sequence, the first is kept, one by one and in one poll alike. Execution never sends
	 * two, but a poll and its snapshots one by one must end the same whatever arrives.
	 */
	@Test
	void ofTwoSnapshotsWithOneSequenceTheFirstIsKeptEitherWay() {
		UUID oneByOne = UUID.randomUUID();
		UUID inOnePoll = UUID.randomUUID();
		for (UUID instrument : List.of(oneByOne, inOnePoll)) {
			snapshots.apply(Snapshots.book(instrument, 1, START, 9_000L, 9_100L, null));
		}
		inTransaction(() -> {
			snapshots.apply(Snapshots.book(oneByOne, 7, START.plusSeconds(1), 9_990L, 10_010L, null));
			snapshots.apply(Snapshots.book(oneByOne, 7, START.plusSeconds(2), 9_980L, 10_020L, null));
		});
		inTransaction(() -> snapshots.applyAll(List.of(Snapshots.book(inOnePoll, 7, START.plusSeconds(1), 9_990L, 10_010L, null),
				Snapshots.book(inOnePoll, 7, START.plusSeconds(2), 9_980L, 10_020L, null)), (instrument) -> {
				}).orElseThrow());

		assertThat(rows(List.of(oneByOne))).isEqualTo(rows(List.of(inOnePoll)))
			.containsExactly("instrument 0: " + START + " 1 7 9990 10010 -");
	}

	/**
	 * A sample the table would refuse fails one by one, and fails a poll too, even when a newer snapshot of the same
	 * minute would win the merge and hide it: a poll and its snapshots one by one must stop at the same place. A last
	 * price below 1 is refused by the market's own table first.
	 */
	@Test
	void aSampleTheTableWouldRefuseFailsEvenWhenANewerOneHidesIt() {
		List<Function<UUID, MarketSnapshot>> refused = List.of(
				(instrument) -> Snapshots.book(instrument, 1, START, 0L, 10_010L, null),
				(instrument) -> Snapshots.book(instrument, 1, START, null, 0L, null),
				(instrument) -> Snapshots.book(instrument, 1, START, 9_990L, 10_010L, 0L),
				(instrument) -> Snapshots.book(instrument, 1, START, 10_010L, 10_010L, null));
		for (Function<UUID, MarketSnapshot> bad : refused) {
			UUID oneByOne = UUID.randomUUID();
			assertThatThrownBy(() -> inTransaction(() -> snapshots.apply(bad.apply(oneByOne))))
				.isInstanceOfAny(UnusableRecordException.class, DataIntegrityViolationException.class);
			UUID inOnePoll = UUID.randomUUID();
			List<MarketSnapshot> poll = List.of(bad.apply(inOnePoll),
					Snapshots.book(inOnePoll, 2, START.plusSeconds(1), 9_990L, 10_010L, 10_000L));
			assertThatThrownBy(() -> inTransaction(() -> snapshots.applyAll(poll, (instrument) -> {
			}))).isInstanceOfAny(UnusableRecordException.class, DataIntegrityViolationException.class);
			assertThat(rows(List.of(oneByOne, inOnePoll))).isEmpty();
		}
	}

	/** A sequence between two snapshots of one minute still finds that minute: its sequences meet the window. */
	@Test
	void aMinuteAtTheEdgeCountsWhenItsSequencesMeetTheWindow() throws Exception {
		UUID instrument = UUID.randomUUID();
		snapshots.apply(Snapshots.book(instrument, 10, START, 9_990L, 10_010L, null));
		snapshots.apply(Snapshots.book(instrument, 20, START.plusSeconds(60), 9_980L, 10_020L, null));
		snapshots.apply(Snapshots.book(instrument, 30, START.plusSeconds(90), 9_970L, 10_030L, null));

		JsonNode between = quality(instrument, 21, 25, 200);

		assertThat(between.get("minutes")).extracting(SpreadMinutesIT::minute)
			.containsExactly("09:01 99.70 100.30 0.60 - changed");
		assertThat(quality(instrument, 11, 19, 200).get("minutes")).isEmpty();
		assertThat(quality(instrument, 10, 10, 200).get("minutes")).hasSize(1);
		assertThat(quality(instrument, 30, 30, 200).get("minutes")).hasSize(1);
	}

	@Test
	void anEmptyWindowHasNoMinutesAndNothingMeasured() throws Exception {
		UUID instrument = UUID.randomUUID();
		snapshots.apply(Snapshots.book(instrument, 5, START, 9_990L, 10_010L, 10_000L));

		JsonNode body = quality(instrument, 6, 9, 200);

		assertThat(body.get("minutes")).isEmpty();
		assertThat(body.get("averageSpread").isNull()).isTrue();
		assertThat(body.get("volatility").isNull()).isTrue();
		assertThat(body.get("twoSidedMinutes").asInt()).isZero();
		assertThat(body.get("returns").asInt()).isZero();
		assertThat(body.get("fromSequence").asString()).isEqualTo("6");
		assertThat(body.get("toSequence").asString()).isEqualTo("9");
		assertThat(body.get("feed").get("state").asString()).isEqualTo("DISABLED");
	}

	@Test
	void aWindowOverADayOfMinutesIsRefused() throws Exception {
		UUID day = UUID.randomUUID();
		snapshots.apply(Snapshots.book(day, 1, START, 9_990L, 10_010L, null));
		snapshots.apply(Snapshots.book(day, 2, START.plus(1_439, ChronoUnit.MINUTES), 9_990L, 10_010L, null));
		UUID longer = UUID.randomUUID();
		snapshots.apply(Snapshots.book(longer, 1, START, 9_990L, 10_010L, null));
		snapshots.apply(Snapshots.book(longer, 2, START.plus(1_440, ChronoUnit.MINUTES), 9_990L, 10_010L, null));

		assertThat(quality(day, 1, 2, 200).get("minutes")).hasSize(1_440);
		assertThat(fieldError(get(longer, "?fromSequence=1&toSequence=2"))).isEqualTo("toSequence RANGE_TOO_LARGE");
		assertThat(quality(longer, 1, 1, 200).get("minutes")).hasSize(1);
	}

	@Test
	void parametersAreChecked() throws Exception {
		UUID instrument = UUID.randomUUID();
		snapshots.apply(Snapshots.book(instrument, 5, START, 9_990L, 10_010L, null));

		assertThat(fieldError(get(instrument, "?toSequence=5"))).isEqualTo("fromSequence REQUIRED");
		assertThat(fieldError(get(instrument, "?fromSequence=5"))).isEqualTo("toSequence REQUIRED");
		assertThat(fieldError(get(instrument, "?fromSequence=five&toSequence=5"))).isEqualTo("fromSequence INVALID_VALUE");
		assertThat(fieldError(get(instrument, "?fromSequence=0&toSequence=5"))).isEqualTo("fromSequence OUT_OF_RANGE");
		assertThat(fieldError(get(instrument, "?fromSequence=6&toSequence=5"))).isEqualTo("toSequence INVALID_RANGE");
		HttpResponse<String> unknown = get(UUID.randomUUID(), "?fromSequence=1&toSequence=5");
		assertThat(json(unknown, 404).get("code").asString()).isEqualTo("INSTRUMENT_NOT_FOUND");
	}

	/** The oracle: what each minute should hold, and a window's figures, from the snapshots it sent. */
	private static final class Oracle {

		/** Per instrument and minute: the lowest and highest sequence, and the snapshot at the highest. */
		private static Map<Integer, TreeMap<Instant, long[]>> spans(List<Sent> sent) {
			Map<Integer, TreeMap<Instant, long[]>> spans = new HashMap<>();
			for (Sent one : sent) {
				Instant minute = one.at().truncatedTo(ChronoUnit.MINUTES);
				long[] span = spans.computeIfAbsent(one.instrument(), (ignored) -> new TreeMap<>())
					.computeIfAbsent(minute, (ignored) -> new long[] { Long.MAX_VALUE, Long.MIN_VALUE });
				span[0] = Math.min(span[0], one.sequence());
				span[1] = Math.max(span[1], one.sequence());
			}
			return spans;
		}

		private static Sent at(List<Sent> sent, int instrument, long sequence) {
			return sent.stream()
				.filter((one) -> one.instrument() == instrument && one.sequence() == sequence)
				.findFirst()
				.orElseThrow();
		}

		static List<String> rows(List<Sent> sent) {
			List<String> rows = new ArrayList<>();
			spans(sent).forEach((instrument, minutes) -> minutes.forEach((minute, span) -> {
				Sent state = at(sent, instrument, span[1]);
				rows.add("instrument " + instrument + ": " + minute + " " + span[0] + " " + span[1] + " " + cents(state.bid())
						+ " " + cents(state.ask()) + " " + cents(state.price()));
			}));
			rows.sort(null);
			return rows;
		}

		record Expected(String served, int returns) {
		}

		/** The minutes whose sequences meet the window, gaps held from the minute before, and their figures. */
		static Expected measure(List<Sent> sent, int instrument, long from, long to) {
			List<Map.Entry<Instant, long[]>> chosen = spans(sent).getOrDefault(instrument, new TreeMap<>())
				.entrySet()
				.stream()
				.filter((entry) -> entry.getValue()[0] <= to && entry.getValue()[1] >= from)
				.toList();
			List<String> minutes = new ArrayList<>();
			List<Long> spreads = new ArrayList<>();
			List<Long> prices = new ArrayList<>();
			Sent held = null;
			Instant next = null;
			for (Map.Entry<Instant, long[]> entry : chosen) {
				while (next != null && next.isBefore(entry.getKey())) {
					minutes.add(line(next, held, false));
					spreads.add(spread(held));
					prices.add(held.price());
					next = next.plus(1, ChronoUnit.MINUTES);
				}
				held = at(sent, instrument, entry.getValue()[1]);
				minutes.add(line(entry.getKey(), held, true));
				spreads.add(spread(held));
				prices.add(held.price());
				next = entry.getKey().plus(1, ChronoUnit.MINUTES);
			}
			List<Long> sides = spreads.stream().filter((spread) -> spread != null).toList();
			String average = sides.isEmpty() ? "-"
					: BigDecimal.valueOf(sides.stream().mapToLong(Long::longValue).sum())
						.divide(BigDecimal.valueOf(100L * sides.size()), 4, RoundingMode.HALF_EVEN)
						.toPlainString();
			List<BigDecimal> returns = new ArrayList<>();
			for (int i = 1; i < prices.size(); i++) {
				if (prices.get(i) != null && prices.get(i - 1) != null) {
					returns.add(new BigDecimal(prices.get(i)).divide(new BigDecimal(prices.get(i - 1)), MathContext.DECIMAL128)
						.subtract(BigDecimal.ONE));
				}
			}
			String volatility = "-";
			if (returns.size() >= 2) {
				BigDecimal n = new BigDecimal(returns.size());
				BigDecimal mean = returns.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(n, MathContext.DECIMAL128);
				BigDecimal sum = BigDecimal.ZERO;
				for (BigDecimal value : returns) {
					BigDecimal off = value.subtract(mean);
					sum = sum.add(off.multiply(off, MathContext.DECIMAL128));
				}
				volatility = sum.divide(n.subtract(BigDecimal.ONE), MathContext.DECIMAL128)
					.sqrt(MathContext.DECIMAL128)
					.setScale(6, RoundingMode.HALF_EVEN)
					.toPlainString();
			}
			return new Expected(String.join("\n", minutes) + "\naverage " + average + " over " + sides.size()
					+ "\nvolatility " + volatility + " over " + returns.size(), returns.size());
		}

		private static Long spread(Sent state) {
			return state.bid() == null || state.ask() == null ? null : state.ask() - state.bid();
		}

		private static String line(Instant minute, Sent state, boolean changed) {
			return minute + " " + price(state.bid()) + " " + price(state.ask()) + " " + price(spread(state)) + " "
					+ price(state.price()) + (changed ? " changed" : " held");
		}

	}

	/** What the endpoint served, in the oracle's form. */
	private static String served(JsonNode body) {
		List<String> minutes = new ArrayList<>();
		for (JsonNode minute : body.get("minutes")) {
			minutes.add(minute.get("minute").asString() + " " + text(minute.get("bestBid")) + " "
					+ text(minute.get("bestAsk")) + " " + text(minute.get("spread")) + " " + text(minute.get("lastPrice"))
					+ (minute.get("changed").asBoolean() ? " changed" : " held"));
		}
		return String.join("\n", minutes) + "\naverage " + text(body.get("averageSpread")) + " over "
				+ body.get("twoSidedMinutes").asInt() + "\nvolatility " + text(body.get("volatility")) + " over "
				+ body.get("returns").asInt();
	}

	/** A minute as "HH:mm bid ask spread price changed|held". */
	private static String minute(JsonNode minute) {
		return minute.get("minute").asString().substring(11, 16) + " " + text(minute.get("bestBid")) + " "
				+ text(minute.get("bestAsk")) + " " + text(minute.get("spread")) + " " + text(minute.get("lastPrice"))
				+ (minute.get("changed").asBoolean() ? " changed" : " held");
	}

	private static String text(JsonNode value) {
		return value.isNull() ? "-" : value.asString();
	}

	private static String cents(Long cents) {
		return cents == null ? "-" : cents.toString();
	}

	private static String price(Long cents) {
		return cents == null ? "-" : BigDecimal.valueOf(cents, 2).toPlainString();
	}

	/**
	 * Sequences shared by the instruments with gaps between them; the clock mostly rising, stepping back a minute and
	 * a half now and then; a fifth of the books one-sided or empty; a trade a third of the time, at a price in the book.
	 */
	private static List<Sent> stream(Random random, int count) {
		List<Sent> sent = new ArrayList<>();
		Map<Integer, Long> prices = new HashMap<>();
		long sequence = 0;
		Instant clock = START;
		for (int i = 0; i < count; i++) {
			sequence += 1 + random.nextInt(3);
			clock = random.nextInt(40) == 0 ? clock.minusSeconds(90) : clock.plusSeconds(random.nextInt(25));
			int instrument = random.nextInt(INSTRUMENTS);
			long bid = 2 + random.nextInt(20_000);
			long ask = bid + 1 + random.nextInt(60);
			if (random.nextInt(3) == 0) {
				prices.put(instrument, bid + random.nextInt((int) (ask - bid) + 1));
			}
			int shape = random.nextInt(15);
			sent.add(new Sent(instrument, sequence, clock.plusMillis(random.nextInt(1_000)), shape == 0 || shape == 2 ? null : bid,
					shape == 1 || shape == 2 ? null : ask, prices.get(instrument)));
		}
		return sent;
	}

	/** The stream's snapshots in a shuffled order, about one in ten delivered twice. */
	private static List<MarketSnapshot> delivery(Random random, List<Sent> sent, List<UUID> instruments) {
		List<MarketSnapshot> delivered = new ArrayList<>();
		for (Sent one : sent) {
			MarketSnapshot snapshot = one.snapshot(instruments);
			delivered.add(snapshot);
			if (random.nextInt(10) == 0) {
				delivered.add(snapshot);
			}
		}
		Collections.shuffle(delivered, random);
		return delivered;
	}

	private static List<UUID> instruments() {
		List<UUID> instruments = new ArrayList<>();
		for (int i = 0; i < INSTRUMENTS; i++) {
			instruments.add(UUID.randomUUID());
		}
		return instruments;
	}

	/** The stored rows of these instruments, each named by its number, in the oracle's form. */
	private List<String> rows(List<UUID> instruments) {
		String in = String.join(", ", instruments.stream().map((id) -> "'" + id + "'").toList());
		List<String> rows = new ArrayList<>(jdbc.query("""
				SELECT instrument_id, minute_start, first_sequence, last_sequence, best_bid_cents, best_ask_cents,
				    last_price_cents
				FROM spread_minute WHERE instrument_id IN (%s)""".formatted(in), (row, index) -> "instrument "
				+ instruments.indexOf(row.getObject("instrument_id", UUID.class)) + ": "
				+ row.getTimestamp("minute_start").toInstant() + " " + row.getLong("first_sequence") + " "
				+ row.getLong("last_sequence") + " " + cents(row.getObject("best_bid_cents", Long.class)) + " "
				+ cents(row.getObject("best_ask_cents", Long.class)) + " "
				+ cents(row.getObject("last_price_cents", Long.class))));
		rows.sort(null);
		return rows;
	}

	private JsonNode quality(UUID instrument, long from, long to, int status) throws Exception {
		return json(get(instrument, "?fromSequence=" + from + "&toSequence=" + to), status);
	}

	private HttpResponse<String> get(UUID instrument, String query) throws Exception {
		return HTTP.send(HttpRequest
			.newBuilder(URI.create("http://localhost:" + port + "/api/v1/instruments/" + instrument + "/market-quality" + query))
			.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static JsonNode json(HttpResponse<String> response, int status) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
		return JSON.readTree(response.body());
	}

	/** A 400 names the field and why, under the usual VALIDATION_FAILED. */
	private static String fieldError(HttpResponse<String> problem) {
		JsonNode body = json(problem, 400);
		assertThat(body.get("code").asString()).isEqualTo("VALIDATION_FAILED");
		JsonNode error = body.get("errors").get(0);
		return error.get("field").asString() + " " + error.get("code").asString();
	}

	private void inTransaction(Runnable work) {
		new TransactionTemplate(transactionManager).executeWithoutResult((status) -> work.run());
	}

}
