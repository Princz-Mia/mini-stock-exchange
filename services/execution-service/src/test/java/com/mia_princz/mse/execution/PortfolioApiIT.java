package com.mia_princz.mse.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.mia_princz.mse.execution.trading.application.TradingEngine;

/**
 * The private reads over HTTP: portfolio, own executions and own orders. Each test gets its own instrument and two
 * traders of its own, so balances start from known amounts: 10,000.00 cash and 100 shares at a 100.00 reference.
 * The instruments exist before the engine starts, because it rebuilds its books only then.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "mse.test.database=portfolio-api", "mse.dev-users.trader-password=" + PortfolioApiIT.PASSWORD,
				"mse.dev-users.operator-password=" + PortfolioApiIT.PASSWORD })
class PortfolioApiIT {

	static final String PASSWORD = "test-portfolio-password";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final List<UUID> INSTRUMENTS = new ArrayList<>();

	private static final AtomicInteger NEXT = new AtomicInteger();

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	TradingEngine engine;

	private UUID instrument;

	private TestBrowser trader;

	private TestBrowser other;

	@BeforeEach
	void market() throws Exception {
		synchronized (INSTRUMENTS) {
			if (INSTRUMENTS.isEmpty()) {
				new TransactionTemplate(transactionManager).executeWithoutResult((status) -> simulation());
				engine.start();
			}
		}
		int n = NEXT.getAndIncrement();
		// One instrument per test; the last two are kept as second instruments for the filter and sell-out tests.
		assertThat(n).as("more tests than instruments: add instruments").isLessThan(INSTRUMENTS.size() - 2);
		instrument = INSTRUMENTS.get(n);
		new TransactionTemplate(transactionManager).executeWithoutResult((status) -> {
			fundedTrader("trader" + n);
			fundedTrader("other" + n);
		});
		trader = TestBrowser.loggedIn(port, "trader" + n, PASSWORD);
		other = TestBrowser.loggedIn(port, "other" + n, PASSWORD);
	}

	@Test
	void portfolioBeforeAnyTradeUsesTheLabelledReference() throws Exception {
		JsonNode portfolio = json(trader.get("/api/v1/portfolio"));

		assertThat(portfolio.get("cash").get("total").asString()).isEqualTo("10000.00");
		assertThat(portfolio.get("cash").get("available").asString()).isEqualTo("10000.00");
		assertThat(portfolio.get("cash").get("reserved").asString()).isEqualTo("0.00");
		JsonNode holding = portfolio.get("holdings").get(0);
		assertThat(holding.get("total").asLong()).isEqualTo(100);
		assertThat(holding.get("mark").get("source").asString()).isEqualTo("REFERENCE");
		assertThat(holding.get("mark").get("price").asString()).isEqualTo("100.00");
		assertThat(holding.get("mark").get("at").isNull()).isTrue();
		assertThat(holding.get("value").asString()).isEqualTo("10000.00");
		assertThat(portfolio.get("equity").asString()).isEqualTo("20000.00");
		// Starting shares at their reference price: no change, no gain yet, half the equity.
		assertThat(holding.get("changePercent").asString()).isEqualTo("0.00");
		assertThat(holding.get("weight").asString()).isEqualTo("50.00");
		assertThat(holding.get("averageCost").asString()).isEqualTo("100.00");
		assertThat(holding.get("unrealized").asString()).isEqualTo("0.00");
		assertThat(holding.get("unrealizedPercent").asString()).isEqualTo("0.00");
		assertThat(portfolio.get("costBasis").asString()).isEqualTo("10000.00");
		assertThat(portfolio.get("unrealized").asString()).isEqualTo("0.00");
	}

	/** Both sides of one trade, by the average-cost rule: the buyer averages in, the seller's average stays. */
	@Test
	void costFiguresFollowTheAccountsOwnTrades() throws Exception {
		place(other, "SELL", 50, "103.00");
		place(trader, "BUY", 50, "103.00");

		JsonNode buyer = json(trader.get("/api/v1/portfolio"));
		JsonNode bought = buyer.get("holdings").get(0);
		assertThat(bought.get("total").asLong()).isEqualTo(150);
		// (100 x 100.00 + 50 x 103.00) / 150 = 101.00; value 150 x 103.00 = 15,450.00 against a cost of 15,150.00.
		assertThat(bought.get("averageCost").asString()).isEqualTo("101.00");
		assertThat(bought.get("unrealized").asString()).isEqualTo("300.00");
		assertThat(bought.get("unrealizedPercent").asString()).isEqualTo("1.98");
		assertThat(bought.get("changePercent").asString()).isEqualTo("3.00");
		// 15,450.00 of equity 4,850.00 cash + 15,450.00 = 20,300.00.
		assertThat(bought.get("weight").asString()).isEqualTo("76.11");
		assertThat(buyer.get("costBasis").asString()).isEqualTo("15150.00");
		assertThat(buyer.get("unrealized").asString()).isEqualTo("300.00");
		assertThat(buyer.get("unrealizedPercent").asString()).isEqualTo("1.98");

		JsonNode sold = json(other.get("/api/v1/portfolio")).get("holdings").get(0);
		assertThat(sold.get("total").asLong()).isEqualTo(50);
		assertThat(sold.get("averageCost").asString()).as("a sale leaves the average alone").isEqualTo("100.00");
		assertThat(sold.get("unrealized").asString()).isEqualTo("150.00");
		assertThat(sold.get("unrealizedPercent").asString()).isEqualTo("3.00");
	}

	/**
	 * Selling everything resets a holding's cost, and the empty holding, whose row stays, does not cost the account its
	 * totals. Trades apply in sequence order and only to their own instrument: ordered by price, the buy would come
	 * before the sale; applied to every holding, the second one's cost would move.
	 */
	@Test
	void sellingOutResetsTheCostAndLeavesTheOtherHoldingsAndTheTotals() throws Exception {
		UUID second = INSTRUMENTS.get(INSTRUMENTS.size() - 2);
		new TransactionTemplate(transactionManager)
			.executeWithoutResult((status) -> fundedTrader("pair", 100L, List.of(instrument, second)));
		TestBrowser pair = TestBrowser.loggedIn(port, "pair", PASSWORD);
		place(pair, "SELL", 100, "99.00");
		place(other, "BUY", 100, "99.00");

		JsonNode out = json(pair.get("/api/v1/portfolio"));
		JsonNode empty = holding(out, instrument);
		assertThat(empty.get("total").asLong()).isZero();
		assertThat(empty.get("averageCost").isNull()).isTrue();
		assertThat(empty.get("unrealized").isNull()).isTrue();
		assertThat(empty.get("weight").asString()).isEqualTo("0.00");
		assertThat(holding(out, second).get("averageCost").asString()).isEqualTo("100.00");
		assertThat(out.get("costBasis").asString()).isEqualTo("10000.00");
		assertThat(out.get("unrealized").asString()).isEqualTo("0.00");
		assertThat(out.get("unrealizedPercent").asString()).isEqualTo("0.00");

		place(other, "SELL", 50, "101.00");
		place(pair, "BUY", 50, "101.00");
		JsonNode back = json(pair.get("/api/v1/portfolio"));
		JsonNode bought = holding(back, instrument);
		assertThat(bought.get("total").asLong()).isEqualTo(50);
		assertThat(bought.get("averageCost").asString()).as("the cost started afresh").isEqualTo("101.00");
		assertThat(bought.get("unrealized").asString()).isEqualTo("0.00");
		assertThat(holding(back, second).get("averageCost").asString()).isEqualTo("100.00");
		// 50 x 101.00 + 100 x 100.00; the second instrument has not traded, so it is marked at its reference.
		assertThat(back.get("costBasis").asString()).isEqualTo("15050.00");
		assertThat(back.get("unrealized").asString()).isEqualTo("0.00");
	}

	/** A history that does not account for the shares held gives no estimate rather than a wrong one, and no error. */
	@Test
	void aHoldingWhoseHistoryDoesNotAddUpHasNoCostFigures() throws Exception {
		new TransactionTemplate(transactionManager).executeWithoutResult((status) -> {
			fundedTrader("damaged", null, List.of(instrument));
			fundedTrader("undercounted", 60L, List.of(instrument));
		});
		// Starting shares on record, but fewer than the 100 held, and no sale beyond them: the cost covers 60 shares.
		JsonNode undercounted = json(TestBrowser.loggedIn(port, "undercounted", PASSWORD).get("/api/v1/portfolio"));
		assertThat(undercounted.get("holdings").get(0).get("averageCost").isNull()).isTrue();
		assertThat(undercounted.get("holdings").get(0).get("unrealized").isNull()).isTrue();
		assertThat(undercounted.get("costBasis").isNull()).isTrue();

		TestBrowser damaged = TestBrowser.loggedIn(port, "damaged", PASSWORD);
		place(damaged, "SELL", 5, "101.00");
		place(other, "BUY", 5, "101.00");

		HttpResponse<String> response = damaged.get("/api/v1/portfolio");
		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode portfolio = json(response);
		JsonNode holding = portfolio.get("holdings").get(0);
		assertThat(holding.get("total").asLong()).isEqualTo(95);
		assertThat(holding.get("averageCost").isNull()).isTrue();
		assertThat(holding.get("unrealized").isNull()).isTrue();
		assertThat(holding.get("unrealizedPercent").isNull()).isTrue();
		assertThat(holding.get("changePercent").asString()).isEqualTo("1.00");
		assertThat(portfolio.get("costBasis").isNull()).isTrue();

		// Selling the rest and buying some back brings the shares held and the shares the history accounts for to the
		// same 10, but the history still does not add up, so there is still no estimate.
		place(damaged, "SELL", 95, "99.00");
		place(other, "BUY", 95, "99.00");
		place(other, "SELL", 10, "99.00");
		place(damaged, "BUY", 10, "99.00");
		JsonNode rebought = json(damaged.get("/api/v1/portfolio"));
		assertThat(rebought.get("holdings").get(0).get("total").asLong()).isEqualTo(10);
		assertThat(rebought.get("holdings").get(0).get("averageCost").isNull()).isTrue();
		assertThat(rebought.get("costBasis").isNull()).isTrue();
	}

	/** Resting orders move cash and shares from available to reserved; equity counts each once, so it holds. */
	@Test
	void reservedCashAndSharesAreCountedOnce() throws Exception {
		place(trader, "BUY", 2, "90.00");
		place(trader, "SELL", 3, "120.00");

		JsonNode portfolio = json(trader.get("/api/v1/portfolio"));
		JsonNode holding = portfolio.get("holdings").get(0);
		assertThat(holding.get("available").asLong()).isEqualTo(97);
		assertThat(holding.get("reserved").asLong()).isEqualTo(3);
		assertThat(holding.get("value").asString()).as("all 100 shares, reserved ones too").isEqualTo("10000.00");

		assertThat(portfolio.get("cash").get("total").asString()).isEqualTo("10000.00");
		assertThat(portfolio.get("cash").get("available").asString()).isEqualTo("9820.00");
		assertThat(portfolio.get("cash").get("reserved").asString()).isEqualTo("180.00");
		assertThat(portfolio.get("equity").asString()).isEqualTo("20000.00");
	}

	@Test
	void aTradeMovesCashSharesAndTheMark() throws Exception {
		place(trader, "SELL", 4, "101.00");
		place(other, "BUY", 4, "101.00");

		JsonNode seller = json(trader.get("/api/v1/portfolio"));
		JsonNode holding = seller.get("holdings").get(0);
		assertThat(seller.get("cash").get("total").asString()).isEqualTo("10404.00");
		assertThat(holding.get("total").asLong()).isEqualTo(96);
		assertThat(holding.get("mark").get("source").asString()).isEqualTo("LAST_TRADE");
		assertThat(holding.get("mark").get("price").asString()).isEqualTo("101.00");
		assertThat(holding.get("mark").get("at").isNull()).isFalse();
		assertThat(holding.get("value").asString()).isEqualTo("9696.00");
		assertThat(seller.get("equity").asString()).isEqualTo("20100.00");
		assertThat(Long.parseLong(seller.get("accountVersion").asString())).isPositive();

		JsonNode sold = json(trader.get("/api/v1/portfolio/trades")).get("trades").get(0);
		assertThat(sold.get("side").asString()).isEqualTo("SELL");
		assertThat(sold.get("liquidity").asString()).isEqualTo("MAKER");
		assertThat(sold.get("notional").asString()).isEqualTo("404.00");
		JsonNode bought = json(other.get("/api/v1/portfolio/trades")).get("trades").get(0);
		assertThat(bought.get("side").asString()).isEqualTo("BUY");
		assertThat(bought.get("liquidity").asString()).isEqualTo("TAKER");
		assertThat(bought.get("tradeId").asString()).isEqualTo(sold.get("tradeId").asString());
	}

	/** Five fills in two commands, in pages of two: newest first, each exactly once, nothing of the other side's. */
	@Test
	void ownTradesPageNewestFirst() throws Exception {
		for (int i = 0; i < 3; i++) {
			place(trader, "SELL", 1, "100.00");
		}
		place(other, "BUY", 3, "100.00");
		place(trader, "SELL", 2, "100.50");
		place(other, "BUY", 1, "100.50");
		place(other, "BUY", 1, "100.50");

		List<String> seen = new ArrayList<>();
		List<Integer> sizes = new ArrayList<>();
		String path = "/api/v1/portfolio/trades?limit=2";
		JsonNode page = json(trader.get(path));
		while (true) {
			sizes.add(page.get("trades").size());
			page.get("trades").forEach((trade) -> seen.add(trade.get("tradeId").asString()));
			if (page.get("nextCursor").isNull()) {
				break;
			}
			page = json(trader.get(path + "&cursor=" + page.get("nextCursor").asString()));
		}

		assertThat(sizes).containsExactly(2, 2, 1);
		assertThat(seen).doesNotHaveDuplicates();
		assertThat(seen.getFirst()).as("the newest command first").isEqualTo(newestTradeId());
	}

	/** Read either way, the executions and the orders are the same rows, each once, in exactly reverse order. */
	@Test
	void historyReadsOldestFirstAsTheExactReverse() throws Exception {
		for (int i = 0; i < 3; i++) {
			place(trader, "SELL", 1, "100.00");
		}
		place(other, "BUY", 3, "100.00");
		place(trader, "SELL", 2, "100.50");
		place(other, "BUY", 1, "100.50");
		place(trader, "BUY", 1, "90.00");
		// The trader buys too, between sells, so pages merge the buy and sell sides in both orders.
		place(other, "SELL", 2, "99.00");
		place(trader, "BUY", 2, "99.00");
		place(trader, "SELL", 1, "100.50");
		place(other, "BUY", 1, "100.50");

		List<String> newestTrades = walk("/api/v1/portfolio/trades?limit=2", "trades", "tradeId");
		List<String> oldestTrades = walk("/api/v1/portfolio/trades?limit=2&order=oldest", "trades", "tradeId");
		assertThat(newestTrades).hasSize(6).doesNotHaveDuplicates();
		assertThat(oldestTrades).isEqualTo(newestTrades.reversed());
		List<String> sides = new ArrayList<>();
		for (String path : List.of("/api/v1/portfolio/trades?limit=200", "/api/v1/portfolio/trades?limit=200&order=oldest")) {
			json(trader.get(path)).get("trades").forEach((trade) -> sides.add(trade.get("side").asString()));
		}
		assertThat(sides).contains("BUY", "SELL");
		assertThat(walk("/api/v1/portfolio/trades?limit=3", "trades", "tradeId")).isEqualTo(newestTrades);

		List<String> newestOrders = walk("/api/v1/orders?limit=2", "orders", "orderId");
		List<String> oldestOrders = walk("/api/v1/orders?limit=2&order=oldest", "orders", "orderId");
		assertThat(newestOrders).as("the trader's own seven").hasSize(7).doesNotHaveDuplicates();
		assertThat(oldestOrders).isEqualTo(newestOrders.reversed());
		assertThat(walk("/api/v1/orders?limit=2&order=newest", "orders", "orderId")).isEqualTo(newestOrders);
	}

	/**
	 * A cursor names the order it was issued in and is refused under the other, where its position means nothing. A
	 * cursor from before either order could be chosen still works in the default one.
	 */
	@Test
	void aCursorWorksOnlyInTheOrderItCameFrom() throws Exception {
		for (int i = 0; i < 3; i++) {
			place(trader, "BUY", 1, (90 + i) + ".00");
		}
		String newest = json(trader.get("/api/v1/orders?limit=1")).get("nextCursor").asString();
		String oldest = json(trader.get("/api/v1/orders?limit=1&order=oldest")).get("nextCursor").asString();
		assertThat(json(trader.get("/api/v1/orders?limit=1&cursor=" + newest)).get("orders")).hasSize(1);
		assertRefusedCursor(trader.get("/api/v1/orders?limit=1&order=oldest&cursor=" + newest));
		assertRefusedCursor(trader.get("/api/v1/orders?limit=1&cursor=" + oldest));

		JsonNode first = json(trader.get("/api/v1/orders?limit=1")).get("orders").get(0);
		String v1 = Base64.getUrlEncoder()
			.withoutPadding()
			.encodeToString(("v1:" + Instant.parse(first.get("createdAt").asString()) + "|" + first.get("orderId").asString())
				.getBytes(StandardCharsets.UTF_8));
		JsonNode afterV1 = json(trader.get("/api/v1/orders?limit=1&cursor=" + v1));
		assertThat(afterV1.get("orders").get(0).get("orderId").asString())
			.isNotEqualTo(first.get("orderId").asString());
		assertRefusedCursor(trader.get("/api/v1/orders?limit=1&order=oldest&cursor=" + v1));

		// Executions from before kept working newest first, and only there.
		place(other, "SELL", 2, "92.00");
		JsonNode trade = json(trader.get("/api/v1/portfolio/trades?limit=1")).get("trades").get(0);
		String v1Trades = Base64.getUrlEncoder()
			.withoutPadding()
			.encodeToString(("v1:" + trade.get("commandSequence").asString() + "|1").getBytes(StandardCharsets.UTF_8));
		assertThat(trader.get("/api/v1/portfolio/trades?cursor=" + v1Trades).statusCode()).isEqualTo(200);
		assertRefusedCursor(trader.get("/api/v1/portfolio/trades?order=oldest&cursor=" + v1Trades));

		// A position no row can have is not a cursor we issued: refused, not an error from the database.
		String farFuture = Base64.getUrlEncoder()
			.withoutPadding()
			.encodeToString(("v2:newest|+300000-01-01T00:00:00Z|" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
		assertRefusedCursor(trader.get("/api/v1/orders?cursor=" + farFuture));

		String trades = Base64.getUrlEncoder().withoutPadding().encodeToString("v2:oldest|1|1".getBytes(StandardCharsets.UTF_8));
		assertRefusedCursor(trader.get("/api/v1/portfolio/trades?cursor=" + trades));
		assertThat(trader.get("/api/v1/portfolio/trades?order=oldest&cursor=" + trades).statusCode()).isEqualTo(200);
	}

	/**
	 * Finished orders come by when they ended, not when they were placed: an order resting a long time and canceled
	 * last is first, ahead of quick fills placed after it. Open orders are not there; filters narrow it; it is one page.
	 */
	@Test
	void finishedOrdersComeByWhenTheyEnded() throws Exception {
		String resting = place(trader, "BUY", 1, "90.00");
		place(trader, "BUY", 1, "91.00");
		for (int i = 0; i < 3; i++) {
			place(trader, "SELL", 1, "100.00");
			place(other, "BUY", 1, "100.00");
		}
		HttpResponse<String> rejected = trader.post("/api/v1/orders", limit("BUY", 10_000, "900.00"), "Idempotency-Key",
				UUID.randomUUID().toString());
		assertThat(rejected.statusCode()).isEqualTo(422);
		assertThat(trader.post("/api/v1/orders/" + resting + "/cancel", "", "Idempotency-Key",
				UUID.randomUUID().toString()).statusCode()).isEqualTo(200);

		JsonNode finished = json(trader.get("/api/v1/orders?status=finished&limit=20"));
		List<String> states = new ArrayList<>();
		finished.get("orders").forEach((order) -> states.add(order.get("state").asString()));
		assertThat(states).as("ended last first; the open 91.00 buy is not finished")
			.containsExactly("CANCELED", "REJECTED", "FILLED", "FILLED", "FILLED");
		assertThat(finished.get("orders").get(0).get("orderId").asString()).isEqualTo(resting);
		assertThat(finished.get("nextCursor").isNull()).isTrue();
		List<String> ended = new ArrayList<>();
		finished.get("orders").forEach((order) -> ended.add(order.get("updatedAt").asString()));
		assertThat(ended).isSortedAccordingTo((a, b) -> Instant.parse(b).compareTo(Instant.parse(a)));

		assertThat(json(trader.get("/api/v1/orders?status=finished&limit=2")).get("orders")).hasSize(2);
		assertThat(json(trader.get("/api/v1/orders?status=finished&side=SELL")).get("orders")).hasSize(3);
		assertThat(json(trader.get("/api/v1/orders?status=finished&instrumentId=" + instrument)).get("orders")).hasSize(5);
		assertThat(json(trader.get("/api/v1/orders?status=finished&instrumentId=" + INSTRUMENTS.getLast())).get("orders"))
			.isEmpty();
		assertThat(trader.get("/api/v1/orders?status=finished&state=FILLED").statusCode()).isEqualTo(400);
		assertThat(trader.get("/api/v1/orders?status=finished&order=oldest").statusCode()).isEqualTo(400);
		String cursor = json(trader.get("/api/v1/orders?limit=1")).get("nextCursor").asString();
		assertThat(trader.get("/api/v1/orders?status=finished&cursor=" + cursor).statusCode()).isEqualTo(400);
		assertThat(trader.get("/api/v1/orders?status=finished&limit=201").statusCode()).isEqualTo(400);
		assertThat(trader.get("/api/v1/orders?status=done").statusCode()).isEqualTo(400);
	}

	/**
	 * Each filter, alone and together, narrows the history to exactly the rows that match, in pages, either way. Its
	 * cursors carry it: one is refused under another filter or none, and one from no filter is refused under a filter.
	 */
	@Test
	void filtersNarrowTheHistory() throws Exception {
		UUID spare = INSTRUMENTS.getLast();
		place(trader, "SELL", 1, "100.00");
		place(trader, "SELL", 1, "100.00");
		place(other, "BUY", 2, "100.00");
		place(other, "SELL", 1, "99.00");
		place(trader, "BUY", 1, "99.00");
		place(trader, spare, "BUY", 1, "50.00");
		String canceled = place(trader, spare, "BUY", 1, "51.00");
		assertThat(trader.post("/api/v1/orders/" + canceled + "/cancel", "", "Idempotency-Key",
				UUID.randomUUID().toString()).statusCode()).isEqualTo(200);
		HttpResponse<String> rejected = trader.post("/api/v1/orders", limit(spare, "BUY", 10_000, "900.00"),
				"Idempotency-Key", UUID.randomUUID().toString());
		assertThat(rejected.statusCode()).isEqualTo(422);

		String trades = "/api/v1/portfolio/trades?limit=1";
		assertThat(walk(trades + "&instrumentId=" + instrument, "trades", "tradeId")).hasSize(3);
		assertThat(walk(trades + "&instrumentId=" + spare, "trades", "tradeId")).isEmpty();
		assertThat(walk(trades + "&side=SELL", "trades", "side")).containsExactly("SELL", "SELL");
		assertThat(walk(trades + "&side=BUY", "trades", "side")).containsExactly("BUY");
		assertThat(walk(trades + "&side=BUY&instrumentId=" + instrument, "trades", "tradeId")).hasSize(1);
		assertThat(walk(trades + "&side=BUY&instrumentId=" + spare, "trades", "tradeId")).isEmpty();
		List<String> sells = walk(trades + "&side=SELL", "trades", "tradeId");
		assertThat(walk(trades + "&side=SELL&order=oldest", "trades", "tradeId")).isEqualTo(sells.reversed());

		String orders = "/api/v1/orders?limit=1";
		assertThat(walk(orders + "&instrumentId=" + spare, "orders", "state")).containsExactly("REJECTED", "CANCELED",
				"OPEN");
		assertThat(walk(orders + "&instrumentId=" + spare + "&state=CANCELED", "orders", "orderId"))
			.containsExactly(canceled);
		assertThat(walk(orders + "&state=FILLED", "orders", "side")).containsExactlyInAnyOrder("SELL", "SELL", "BUY");
		assertThat(walk(orders + "&state=FILLED&side=SELL", "orders", "side")).containsExactly("SELL", "SELL");
		assertThat(walk(orders + "&side=BUY&order=oldest", "orders", "state")).containsExactly("FILLED", "OPEN",
				"CANCELED", "REJECTED");
		assertThat(walk(orders + "&state=PARTIALLY_FILLED", "orders", "orderId")).isEmpty();

		// Cursors belong to their filter.
		String bySell = json(trader.get(trades + "&side=SELL")).get("nextCursor").asString();
		assertThat(json(trader.get(trades + "&side=SELL&cursor=" + bySell)).get("trades")).hasSize(1);
		assertRefusedCursor(trader.get(trades + "&side=BUY&cursor=" + bySell));
		assertRefusedCursor(trader.get(trades + "&cursor=" + bySell));
		String unfiltered = json(trader.get(orders)).get("nextCursor").asString();
		assertRefusedCursor(trader.get(orders + "&state=FILLED&cursor=" + unfiltered));
		String bySpare = json(trader.get(orders + "&instrumentId=" + spare)).get("nextCursor").asString();
		assertRefusedCursor(trader.get(orders + "&instrumentId=" + instrument + "&cursor=" + bySpare));
		assertRefusedCursor(trader.get(orders + "&instrumentId=" + spare + "&state=OPEN&cursor=" + bySpare));
		JsonNode newest = json(trader.get(orders)).get("orders").get(0);
		String v2 = Base64.getUrlEncoder()
			.withoutPadding()
			.encodeToString(("v2:newest|" + Instant.parse(newest.get("createdAt").asString()) + "|"
					+ newest.get("orderId").asString()).getBytes(StandardCharsets.UTF_8));
		assertThat(trader.get(orders + "&cursor=" + v2).statusCode()).isEqualTo(200);
		assertRefusedCursor(trader.get(orders + "&side=BUY&cursor=" + v2));
		String v1 = Base64.getUrlEncoder()
			.withoutPadding()
			.encodeToString(("v1:" + Instant.parse(newest.get("createdAt").asString()) + "|"
					+ newest.get("orderId").asString()).getBytes(StandardCharsets.UTF_8));
		assertThat(trader.get(orders + "&cursor=" + v1).statusCode()).isEqualTo(200);
		assertRefusedCursor(trader.get(orders + "&state=FILLED&cursor=" + v1));

		// Bad filters are refused, and filters are for the history, not the active list.
		assertThat(trader.get("/api/v1/orders?instrumentId=NOVA").statusCode()).isEqualTo(400);
		assertThat(trader.get("/api/v1/orders?side=buy").statusCode()).isEqualTo(400);
		assertThat(trader.get("/api/v1/orders?state=DONE").statusCode()).isEqualTo(400);
		assertThat(trader.get("/api/v1/portfolio/trades?side=BOTH").statusCode()).isEqualTo(400);
		assertThat(trader.get("/api/v1/orders?status=active&side=BUY").statusCode()).isEqualTo(400);
	}

	@Test
	void activeOrdersAndTheFullHistory() throws Exception {
		String resting = place(trader, "BUY", 1, "95.00");
		place(trader, "BUY", 1, "94.00");
		HttpResponse<String> rejected = trader.post("/api/v1/orders", limit("BUY", 10_000, "900.00"), "Idempotency-Key",
				UUID.randomUUID().toString());
		assertThat(rejected.statusCode()).isEqualTo(422);
		trader.post("/api/v1/orders/" + resting + "/cancel", "", "Idempotency-Key", UUID.randomUUID().toString());
		place(other, "BUY", 1, "93.00");

		JsonNode active = json(trader.get("/api/v1/orders?status=active"));
		assertThat(active.get("orders").size()).isEqualTo(1);
		assertThat(active.get("orders").get(0).get("limitPrice").asString()).isEqualTo("94.00");
		assertThat(active.get("orders").get(0).get("symbol").asString()).startsWith("PORT");
		assertThat(active.get("nextCursor").isNull()).isTrue();

		JsonNode first = json(trader.get("/api/v1/orders?limit=2"));
		JsonNode second = json(trader.get("/api/v1/orders?limit=2&cursor=" + first.get("nextCursor").asString()));
		List<String> states = new ArrayList<>();
		first.get("orders").forEach((order) -> states.add(order.get("state").asString()));
		second.get("orders").forEach((order) -> states.add(order.get("state").asString()));
		assertThat(states).as("newest first, rejected included").containsExactly("REJECTED", "OPEN", "CANCELED");
		assertThat(second.get("nextCursor").isNull()).isTrue();

		// Active orders, too, either way.
		place(trader, "BUY", 1, "93.50");
		List<String> newestActive = new ArrayList<>();
		List<String> oldestActive = new ArrayList<>();
		json(trader.get("/api/v1/orders?status=active")).get("orders")
			.forEach((order) -> newestActive.add(order.get("orderId").asString()));
		json(trader.get("/api/v1/orders?status=active&order=oldest")).get("orders")
			.forEach((order) -> oldestActive.add(order.get("orderId").asString()));
		assertThat(newestActive).hasSize(2);
		assertThat(oldestActive).isEqualTo(newestActive.reversed());
		assertThat(trader.get("/api/v1/orders?status=active&order=sideways").statusCode()).isEqualTo(400);
	}

	@Test
	void badPagingRequestsAreRefused() throws Exception {
		assertThat(trader.get("/api/v1/orders?limit=201").statusCode()).isEqualTo(400);
		assertThat(trader.get("/api/v1/orders?status=done").statusCode()).isEqualTo(400);
		assertThat(trader.get("/api/v1/orders?cursor=bm90LWEtY3Vyc29y").statusCode()).isEqualTo(400);
		assertThat(trader.get("/api/v1/portfolio/trades?limit=0").statusCode()).isEqualTo(400);
		assertThat(trader.get("/api/v1/portfolio/trades?limit=many").statusCode()).isEqualTo(400);
		assertThat(trader.get("/api/v1/portfolio/trades?cursor=%25").statusCode()).isEqualTo(400);
		assertThat(trader.get("/api/v1/orders?order=sideways").statusCode()).isEqualTo(400);
		assertThat(trader.get("/api/v1/portfolio/trades?order=OLDEST").statusCode()).isEqualTo(400);
		TestBrowser operator = TestBrowser.loggedIn(port, "operator", PASSWORD);
		assertThat(operator.get("/api/v1/portfolio").statusCode()).isEqualTo(403);
		assertThat(new TestBrowser(port).get("/api/v1/orders").statusCode()).isEqualTo(401);
	}

	private String place(TestBrowser browser, String side, long quantity, String price) throws Exception {
		return place(browser, instrument, side, quantity, price);
	}

	private String place(TestBrowser browser, UUID on, String side, long quantity, String price) throws Exception {
		HttpResponse<String> response = browser.post("/api/v1/orders", limit(on, side, quantity, price),
				"Idempotency-Key", UUID.randomUUID().toString());
		assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
		return JSON.readTree(response.body()).get("orderId").asString();
	}

	private String limit(String side, long quantity, String price) {
		return limit(instrument, side, quantity, price);
	}

	private static String limit(UUID on, String side, long quantity, String price) {
		return """
				{"instrumentId":"%s","side":"%s","type":"LIMIT","timeInForce":"GTC","quantity":%d,"limitPrice":"%s"}"""
			.formatted(on, side, quantity, price);
	}

	private String newestTradeId() {
		return jdbc.queryForObject("""
				SELECT id::text FROM trades WHERE instrument_id = ?
				ORDER BY command_sequence DESC, trade_sequence DESC LIMIT 1""", String.class, instrument);
	}

	/** Every page of a list from the start, as the IDs in the order they came; a list that never ends fails. */
	private List<String> walk(String path, String rows, String id) throws Exception {
		List<String> seen = new ArrayList<>();
		JsonNode page = json(trader.get(path));
		for (int pages = 0; pages < 100; pages++) {
			page.get(rows).forEach((row) -> seen.add(row.get(id).asString()));
			if (page.get("nextCursor").isNull()) {
				return seen;
			}
			page = json(trader.get(path + "&cursor=" + page.get("nextCursor").asString()));
		}
		throw new AssertionError("more than 100 pages: the cursor is not moving on");
	}

	private static void assertRefusedCursor(HttpResponse<String> response) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
		assertThat(response.body()).contains("INVALID_CURSOR");
	}

	private static JsonNode json(HttpResponse<String> response) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		return JSON.readTree(response.body());
	}

	private void simulation() {
		UUID simulation = UUID.randomUUID();
		jdbc.update("INSERT INTO simulation (id, state, opened_at) VALUES (?, 'OPEN', now())", simulation);
		jdbc.update("INSERT INTO account (id, simulation_id, kind, display_name) VALUES (?, ?, 'ISSUANCE', 'Issuance')",
				UUID.randomUUID(), simulation);
		for (int i = 0; i < 18; i++) {
			UUID id = UUID.randomUUID();
			jdbc.update(
					"INSERT INTO instrument (id, simulation_id, symbol, reference_price_cents) VALUES (?, ?, ?, 10000)",
					id, simulation, "PORT" + (char) ('A' + i));
			INSTRUMENTS.add(id);
		}
	}

	private static JsonNode holding(JsonNode portfolio, UUID instrumentId) {
		for (JsonNode holding : portfolio.get("holdings")) {
			if (holding.get("instrumentId").asString().equals(instrumentId.toString())) {
				return holding;
			}
		}
		throw new AssertionError("no holding of " + instrumentId);
	}

	/** A trader user with an account holding 10,000.00 and 100 shares of this test's instrument, from genesis. */
	private void fundedTrader(String username) {
		fundedTrader(username, 100L, List.of(instrument));
	}

	/**
	 * 100 shares of each instrument given, with {@code allocated} of each on record as the starting shares: null leaves
	 * the record out and fewer than 100 undercounts it, as a damaged history would.
	 */
	private void fundedTrader(String username, Long allocated, List<UUID> instruments) {
		UUID user = UUID.randomUUID();
		UUID account = UUID.randomUUID();
		UUID batch = UUID.randomUUID();
		UUID simulation = jdbc.queryForObject("SELECT id FROM simulation", UUID.class);
		UUID issuance = jdbc.queryForObject("SELECT id FROM account WHERE kind = 'ISSUANCE'", UUID.class);
		jdbc.update("INSERT INTO app_user (id, username, password_hash, role) VALUES (?, ?, ?, 'TRADER')", user,
				username, TestBrowser.passwordHash(PASSWORD));
		jdbc.update("""
				INSERT INTO account (id, simulation_id, kind, display_name, owner_user_id)
				VALUES (?, ?, 'TRADER', ?, ?)""", account, simulation, username, user);
		jdbc.update("INSERT INTO journal_batch (id, simulation_id, cause, command_sequence) VALUES (?, ?, 'GENESIS', 1)",
				batch, simulation);
		String post = "INSERT INTO ledger_entry (batch_id, account_id, instrument_id, bucket, delta) VALUES (?, ?, ?, 'AVAILABLE', ?)";
		jdbc.update(post, batch, issuance, null, -1_000_000);
		jdbc.update(post, batch, account, null, 1_000_000);
		jdbc.update("""
				INSERT INTO cash_balance (account_id, account_kind, total_cents, reserved_cents)
				VALUES (?, 'TRADER', 1000000, 0)""", account);
		// As account setup records them: what the account started with, for the cost basis.
		String allocate = "INSERT INTO initial_allocation (account_id, instrument_id, amount, journal_batch_id) VALUES (?, ?, ?, ?)";
		if (allocated != null) {
			jdbc.update(allocate, account, null, 1_000_000, batch);
		}
		for (UUID held : instruments) {
			jdbc.update(post, batch, issuance, held, -100);
			jdbc.update(post, batch, account, held, 100);
			jdbc.update("""
					INSERT INTO position (account_id, account_kind, instrument_id, total_quantity, reserved_quantity)
					VALUES (?, 'TRADER', ?, 100, 0)""", account, held);
			if (allocated != null) {
				jdbc.update(allocate, account, held, allocated, batch);
			}
		}
	}

}
