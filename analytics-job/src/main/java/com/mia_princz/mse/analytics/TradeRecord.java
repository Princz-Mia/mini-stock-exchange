package com.mia_princz.mse.analytics;

import com.mia_princz.mse.contracts.events.TradeExecuted;

/** Flink POJO view of a decoded {@link TradeExecuted}; avoids Kryo for Avro classes. */
public class TradeRecord {

	public String simulationId;
	public String instrumentId;
	public String symbol;
	public long tradeSequence;
	public long executedAtMicros;
	public long priceCents;
	public long quantity;

	public TradeRecord() {
	}

	static TradeRecord from(TradeExecuted trade) {
		TradeRecord record = new TradeRecord();
		record.simulationId = trade.getSimulationId().toString();
		record.instrumentId = trade.getInstrumentId().toString();
		record.symbol = trade.getSymbol();
		record.tradeSequence = trade.getTradeSequence();
		record.executedAtMicros = Math.addExact(Math.multiplyExact(trade.getExecutedAt().getEpochSecond(), 1_000_000L),
				trade.getExecutedAt().getNano() / 1_000L);
		record.priceCents = trade.getPriceCents();
		record.quantity = trade.getQuantity();
		return record;
	}

	String key() {
		return simulationId + '|' + instrumentId;
	}
}
