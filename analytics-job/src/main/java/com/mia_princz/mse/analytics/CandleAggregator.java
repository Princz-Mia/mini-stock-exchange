package com.mia_princz.mse.analytics;

import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

/**
 * Foundation slice of the candle processor: keyed last-seen sequence, per-UTC-minute state and complete
 * aggregates per unique trade. Sequence-gap errors and state cleanup are still to come.
 */
public class CandleAggregator extends KeyedProcessFunction<String, TradeRecord, CandleRecord> {

	private static final long MICROS_PER_MINUTE = 60_000_000L;

	private transient ValueState<Long> lastSequence;
	private transient MapState<Long, CandleRecord> buckets;

	@Override
	public void open(OpenContext context) {
		lastSequence = getRuntimeContext().getState(new ValueStateDescriptor<>("last-trade-sequence", Types.LONG));
		buckets = getRuntimeContext().getMapState(
				new MapStateDescriptor<>("candles-by-minute", Types.LONG, Types.POJO(CandleRecord.class)));
	}

	@Override
	public void processElement(TradeRecord trade, Context context, Collector<CandleRecord> out) throws Exception {
		Long last = lastSequence.value();
		if (last != null && trade.tradeSequence <= last) {
			return;
		}
		lastSequence.update(trade.tradeSequence);

		long bucketStartMillis = Math.floorDiv(trade.executedAtMicros, MICROS_PER_MINUTE) * 60_000L;
		CandleRecord candle = buckets.get(bucketStartMillis);
		if (candle == null) {
			candle = CandleRecord.start(trade, bucketStartMillis);
		}
		candle.add(trade);
		buckets.put(bucketStartMillis, candle);
		out.collect(candle);
	}
}
