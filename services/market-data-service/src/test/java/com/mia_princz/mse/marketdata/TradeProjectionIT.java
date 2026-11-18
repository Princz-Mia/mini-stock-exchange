package com.mia_princz.mse.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mia_princz.mse.contracts.events.TradeExecuted;
import com.mia_princz.mse.marketdata.projection.TradeProjection;
import com.mia_princz.mse.marketdata.projection.UnusableRecordException;

/** The tape rules on their own: one row per trade id, whatever the redelivery. The feeds are off. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "mse.market-feed.enabled=false", "mse.trade-feed.enabled=false" })
class TradeProjectionIT {

	@Autowired
	TradeProjection projection;

	@Autowired
	JdbcTemplate jdbc;

	/**
	 * The tape's pages, newest first by trade sequence for one instrument, come from a backward scan of the unique
	 * index, with and without a cursor; the index that duplicated it is gone. Sequential and bitmap scans are off for the
	 * plan, since on a test's few rows the planner would rather read the table and sort.
	 */
	@Test
	void tapePagesComeFromABackwardScanOfTheUniqueIndex() {
		UUID instrument = UUID.randomUUID();
		for (int sequence = 1; sequence <= 5; sequence++) {
			projection.apply(Trades.trade(instrument, sequence));
		}
		for (String before : new String[] { "NULL", "4" }) {
			String plan = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) (connection) -> {
				try (var statement = connection.createStatement()) {
					statement.execute("SET enable_seqscan = off");
					statement.execute("SET enable_bitmapscan = off");
					StringBuilder lines = new StringBuilder();
					try (var rows = statement.executeQuery("""
							EXPLAIN SELECT trade_id, trade_sequence, price_cents, quantity, executed_at FROM public_trade
							WHERE instrument_id = '%s' AND (CAST(%s AS BIGINT) IS NULL OR trade_sequence < %s)
							ORDER BY trade_sequence DESC LIMIT 51""".formatted(instrument, before, before))) {
						while (rows.next()) {
							lines.append(rows.getString(1)).append('\n');
						}
					}
					statement.execute("RESET enable_seqscan");
					statement.execute("RESET enable_bitmapscan");
					return lines.toString();
				}
			});

			assertThat(plan).as("before " + before)
				.contains("Index Scan Backward using public_trade_instrument_id_trade_sequence_key")
				.doesNotContain("Sort");
		}
		assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'public_trade'", String.class))
			.doesNotContain("public_trade_tape");
	}

	@Test
	void newTradeJoinsTheTape() {
		UUID instrument = UUID.randomUUID();
		TradeExecuted trade = Trades.trade(instrument, 7);

		assertThat(projection.apply(trade)).isTrue();

		Map<String, Object> row = jdbc.queryForMap("""
				SELECT symbol, trade_sequence, command_sequence, price_cents, quantity FROM public_trade
				WHERE trade_id = ?""", trade.getTradeId());
		assertThat(row).containsEntry("symbol", "NOVA")
			.containsEntry("trade_sequence", 7L)
			.containsEntry("price_cents", 10_007L)
			.containsEntry("quantity", 5L);
	}

	@Test
	void redeliveredEventChangesNothing() {
		TradeExecuted trade = Trades.trade(UUID.randomUUID(), 1);
		projection.apply(trade);

		assertThat(projection.apply(trade)).isFalse();
	}

	@Test
	void sameTradeUnderANewEventIdIsNotAddedTwice() {
		UUID instrument = UUID.randomUUID();
		TradeExecuted trade = Trades.trade(instrument, 1);
		projection.apply(trade);

		assertThat(projection.apply(TradeExecuted.newBuilder(trade).setEventId(UUID.randomUUID()).build())).isFalse();

		assertThat(jdbc.queryForObject("SELECT count(*) FROM public_trade WHERE instrument_id = ?", Long.class,
				instrument)).isEqualTo(1);
	}

	@Test
	void differentTradeUnderAnExistingSequenceIsRefused() {
		UUID instrument = UUID.randomUUID();
		projection.apply(Trades.trade(instrument, 4));

		assertThatThrownBy(() -> projection.apply(Trades.trade(instrument, 4)))
			.isInstanceOf(UnusableRecordException.class)
			.hasMessageContaining("sequence 4");
	}

}
