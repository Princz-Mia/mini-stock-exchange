package com.mia_princz.mse.execution.trading.adapter.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mia_princz.mse.execution.trading.domain.Money;
import com.mia_princz.mse.execution.trading.domain.Price;
import com.mia_princz.mse.execution.trading.domain.Quantity;

/**
 * The logged-in trader's money: cash, holdings at their marks, estimated equity, and their own executions. Amounts
 * are decimal strings and sequences strings, as everywhere in the API.
 */
@RestController
@RequestMapping("/api/v1/portfolio")
class PortfolioController {

	private final TradingQueries queries;

	PortfolioController(TradingQueries queries) {
		this.queries = queries;
	}

	record Cash(String total, String available, String reserved) {
	}

	record Mark(String price, String source, Instant at) {
	}

	record Holding(String instrumentId, String symbol, long total, long available, long reserved, Mark mark,
			String value) {
	}

	record Portfolio(Cash cash, List<Holding> holdings, String equity, String accountVersion,
			String asOfCommandSequence) {
	}

	record OwnTrade(String tradeId, String instrumentId, String symbol, String side, String liquidity, String price,
			long quantity, String notional, Instant executedAt, String commandSequence) {
	}

	record OwnTradePage(List<OwnTrade> trades, String nextCursor) {
	}

	/** Equity counts total cash and total holdings once each: reserved amounts are part of the totals. */
	@GetMapping
	Portfolio portfolio(Authentication authentication) {
		TradingQueries.Portfolio portfolio = queries.portfolio(Pages.trader(queries, authentication).accountId());
		Money equity = new Money(portfolio.cashTotalCents());
		List<Holding> holdings = new ArrayList<>();
		for (TradingQueries.Holding holding : portfolio.holdings()) {
			Price mark = new Price(holding.markCents());
			// Not Quantity: a holding can grow past the 10,000 shares one order may carry.
			Money value = new Money(Math.multiplyExact(holding.markCents(), holding.total()));
			equity = equity.plus(value);
			holdings.add(new Holding(holding.instrumentId().toString(), holding.symbol(), holding.total(),
					holding.total() - holding.reserved(), holding.reserved(),
					new Mark(mark.format(), holding.lastTrade() ? "LAST_TRADE" : "REFERENCE", holding.markedAt()),
					value.format()));
		}
		long total = portfolio.cashTotalCents();
		long reserved = portfolio.cashReservedCents();
		return new Portfolio(
				new Cash(new Money(total).format(), new Money(total - reserved).format(), new Money(reserved).format()),
				holdings, equity.format(), Long.toString(portfolio.accountVersion()),
				Long.toString(portfolio.asOfCommandSequence()));
	}

	@GetMapping("/trades")
	OwnTradePage trades(@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit,
			@RequestParam(required = false) String order, @RequestParam(required = false) String instrumentId,
			@RequestParam(required = false) String side, Authentication authentication) {
		Pages.checkLimit(limit);
		PageOrder direction = PageOrder.parse(order, PageOrder.NEWEST);
		HistoryFilter filter = HistoryFilter.parse(instrumentId, side, null);
		TradingQueries.TradePosition after = cursor == null ? null : Cursors.trades(cursor, direction, filter);
		List<TradingQueries.OwnTrade> page = queries.ownTrades(Pages.trader(queries, authentication).accountId(), after,
				limit + 1, direction, filter);
		boolean more = page.size() > limit;
		List<TradingQueries.OwnTrade> shown = more ? page.subList(0, limit) : page;
		String next = more ? Cursors.trades(new TradingQueries.TradePosition(shown.getLast().commandSequence(),
				shown.getLast().tradeSequence()), direction, filter) : null;
		return new OwnTradePage(shown.stream().map(PortfolioController::view).toList(), next);
	}

	private static OwnTrade view(TradingQueries.OwnTrade trade) {
		Price price = new Price(trade.priceCents());
		return new OwnTrade(trade.tradeId().toString(), trade.instrumentId().toString(), trade.symbol(), trade.side(),
				trade.liquidity(), price.format(), trade.quantity(), price.times(new Quantity(trade.quantity())).format(),
				trade.executedAt(), Long.toString(trade.commandSequence()));
	}

}
