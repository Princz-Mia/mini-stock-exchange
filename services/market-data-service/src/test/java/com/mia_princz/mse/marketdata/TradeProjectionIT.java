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
