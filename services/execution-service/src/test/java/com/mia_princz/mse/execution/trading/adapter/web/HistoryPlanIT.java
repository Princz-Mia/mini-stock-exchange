package com.mia_princz.mse.execution.trading.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mia_princz.mse.execution.TestcontainersConfiguration;

/**
 * How the history statements the API really sends are planned. Each is explained as a generic plan, the plan the
 * server may settle on once the driver prepares a statement it has run a few times, which knows no parameter values.
 * A later page of a filtered list must still start at its filter's index range and at the page's position, in either
 * order, with no sort. The test tables are nearly empty, so sequential and bitmap scans are turned off to make the
 * planner show which index fits; this proves the statements can be served this way, not that every data set is.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "mse.test.database=history-plan")
class HistoryPlanIT {

	private static final HistoryFilter BY_INSTRUMENT = new HistoryFilter(UUID.randomUUID(), null, null);

	private static final HistoryFilter BY_STATE = new HistoryFilter(null, null, "FILLED");

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Test
	void laterFilteredPagesStartAtTheirIndexRangeAndPosition() {
		for (PageOrder order : PageOrder.values()) {
			assertThat(plan(TradingQueries.ordersSql(order, BY_INSTRUMENT, true)))
				.contains("orders_recent_by_account_instrument", "(instrument_id = $", "ROW(created_at, id)")
				.doesNotContain("Sort");
			assertThat(plan(TradingQueries.ordersSql(order, BY_STATE, true)))
				.contains("orders_recent_by_account_state", "(state = $", "ROW(created_at, id)")
				.doesNotContain("Sort");
			assertThat(plan(TradingQueries.ownTradesSql(order, new HistoryFilter(BY_INSTRUMENT.instrumentId(), "BUY",
					null), true)))
				.contains("trades_by_buyer_instrument", "(instrument_id = $", "ROW(command_sequence, trade_sequence)")
				.doesNotContain("seller")
				.doesNotContain("Sort");
			assertThat(plan(TradingQueries.ownTradesSql(order, new HistoryFilter(BY_INSTRUMENT.instrumentId(), "SELL",
					null), true)))
				.contains("trades_by_seller_instrument", "(instrument_id = $", "ROW(command_sequence, trade_sequence)")
				.doesNotContain("buyer")
				.doesNotContain("Sort");
		}
	}

	@Test
	void laterUnfilteredPagesStartAtThePagePosition() {
		for (PageOrder order : PageOrder.values()) {
			assertThat(plan(TradingQueries.ordersSql(order, HistoryFilter.NONE, true)))
				.contains("orders_recent_by_account", "ROW(created_at, id)")
				.doesNotContain("Sort");
			assertThat(plan(TradingQueries.ownTradesSql(order, BY_INSTRUMENT, true)))
				.contains("trades_by_buyer_instrument", "trades_by_seller_instrument");
		}
	}

	@Test
	void firstPagesHaveNoPositionToCompare() {
		assertThat(TradingQueries.ordersSql(PageOrder.NEWEST, BY_INSTRUMENT, false)).doesNotContain("afterAt");
		assertThat(TradingQueries.ownTradesSql(PageOrder.OLDEST, HistoryFilter.NONE, false)).doesNotContain("afterCommand");
		assertThat(plan(TradingQueries.ordersSql(PageOrder.OLDEST, BY_STATE, false)))
			.contains("orders_recent_by_account_state")
			.doesNotContain("Sort");
	}

	/**
	 * The statement's generic plan. Its named parameters are numbered as the server's are, it is prepared on the
	 * server, and it is explained with the generic plan forced, so the values passed are ignored.
	 */
	private String plan(String namedSql) {
		Map<String, Integer> numbers = new LinkedHashMap<>();
		Matcher matcher = Pattern.compile("(?<![:\\w]):([A-Za-z]+)").matcher(namedSql);
		StringBuilder numbered = new StringBuilder();
		while (matcher.find()) {
			int number = numbers.computeIfAbsent(matcher.group(1), (name) -> numbers.size() + 1);
			matcher.appendReplacement(numbered, "\\$" + number);
		}
		matcher.appendTail(numbered);
		String nulls = String.join(", ", Collections.nCopies(numbers.size(), "NULL"));
		return new TransactionTemplate(transactionManager).execute((status) -> {
			jdbc.execute("SET LOCAL enable_seqscan = off");
			jdbc.execute("SET LOCAL enable_bitmapscan = off");
			jdbc.execute("SET LOCAL plan_cache_mode = force_generic_plan");
			jdbc.execute("PREPARE history_plan AS " + numbered);
			try {
				return String.join("\n", jdbc.queryForList("EXPLAIN EXECUTE history_plan(" + nulls + ")", String.class));
			}
			finally {
				jdbc.execute("DEALLOCATE history_plan");
			}
		});
	}

}
