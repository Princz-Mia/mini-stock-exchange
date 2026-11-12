package com.mia_princz.mse.execution.trading.adapter.web;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mia_princz.mse.execution.identity.BotPrincipal;
import com.mia_princz.mse.execution.trading.domain.CostBasis;
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

	/**
	 * {@code changePercent} is the mark against the reference price and {@code weight} the value's share of equity,
	 * both in percent to two places. {@code averageCost}, {@code unrealized} and {@code unrealizedPercent} are estimates
	 * by the average-cost rule, null for a bot's read.
	 */
	record Holding(String instrumentId, String symbol, long total, long available, long reserved, Mark mark,
			String value, String changePercent, String weight, String averageCost, String unrealized,
			String unrealizedPercent) {
	}

	/** {@code costBasis}, {@code unrealized} and {@code unrealizedPercent} total the holdings' estimates. */
	record Portfolio(Cash cash, List<Holding> holdings, String equity, String accountVersion,
			String asOfCommandSequence, String costBasis, String unrealized, String unrealizedPercent) {
	}

	record OwnTrade(String tradeId, String instrumentId, String symbol, String side, String liquidity, String price,
			long quantity, String notional, Instant executedAt, String commandSequence) {
	}

	record OwnTradePage(List<OwnTrade> trades, String nextCursor) {
	}

	/**
	 * Equity counts total cash and total holdings once each: reserved amounts are part of the totals. The cost figures
	 * are worked out from the account's own trades on every read, so they are for people: a bot's read leaves them out.
	 */
	@GetMapping
	Portfolio portfolio(Authentication authentication) {
		boolean person = !(authentication.getPrincipal() instanceof BotPrincipal);
		TradingQueries.Portfolio portfolio = queries.portfolio(Pages.trader(queries, authentication).accountId(), person);
		Money equity = new Money(portfolio.cashTotalCents());
		for (TradingQueries.Holding holding : portfolio.holdings()) {
			// Not Quantity: a holding can grow past the 10,000 shares one order may carry.
			equity = equity.plus(new Money(Math.multiplyExact(holding.markCents(), holding.total())));
		}
		List<Holding> holdings = new ArrayList<>();
		BigDecimal costBasis = BigDecimal.ZERO;
		BigDecimal unrealized = BigDecimal.ZERO;
		for (TradingQueries.Holding holding : portfolio.holdings()) {
			long valueCents = Math.multiplyExact(holding.markCents(), holding.total());
			String averageCost = null;
			String gain = null;
			String gainPercent = null;
			CostBasis basis = portfolio.costs().get(holding.instrumentId());
			// Only a cost that accounts for exactly the shares held: anything else would be a wrong estimate.
			if (person && basis != null && basis.quantity() == holding.total() && basis.averageCents().isPresent()) {
				BigDecimal average = basis.averageCents().orElseThrow();
				BigDecimal cost = basis.costCents();
				BigDecimal difference = BigDecimal.valueOf(valueCents).subtract(cost);
				averageCost = cents(average);
				gain = cents(difference);
				gainPercent = percent(difference, cost);
				costBasis = costBasis.add(cost);
				unrealized = unrealized.add(difference);
			}
			holdings.add(new Holding(holding.instrumentId().toString(), holding.symbol(), holding.total(),
					holding.total() - holding.reserved(), holding.reserved(),
					new Mark(new Price(holding.markCents()).format(), holding.lastTrade() ? "LAST_TRADE" : "REFERENCE",
							holding.markedAt()),
					new Money(valueCents).format(),
					percent(BigDecimal.valueOf(holding.markCents() - holding.referencePriceCents()),
							BigDecimal.valueOf(holding.referencePriceCents())),
					percent(BigDecimal.valueOf(valueCents), BigDecimal.valueOf(equity.cents())),
					averageCost, gain, gainPercent));
		}
		long total = portfolio.cashTotalCents();
		long reserved = portfolio.cashReservedCents();
		// A holding sold down to no shares keeps its row but has no cost to count; with no shares at all, the totals are 0.
		boolean costed = person
				&& holdings.stream().allMatch((holding) -> holding.total() == 0 || holding.averageCost() != null);
		return new Portfolio(
				new Cash(new Money(total).format(), new Money(total - reserved).format(), new Money(reserved).format()),
				holdings, equity.format(), Long.toString(portfolio.accountVersion()),
				Long.toString(portfolio.asOfCommandSequence()), costed ? cents(costBasis) : null,
				costed ? cents(unrealized) : null, costed ? percent(unrealized, costBasis) : null);
	}

	/** Cents to a decimal with two places, rounded half-even. */
	static String cents(BigDecimal cents) {
		return cents.movePointLeft(2).setScale(2, RoundingMode.HALF_EVEN).toPlainString();
	}

	/** A share in percent to two places, rounded half-even; 0.00 of nothing. */
	static String percent(BigDecimal part, BigDecimal whole) {
		if (whole.signum() == 0) {
			return "0.00";
		}
		return part.movePointRight(2).divide(whole, 2, RoundingMode.HALF_EVEN).toPlainString();
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
