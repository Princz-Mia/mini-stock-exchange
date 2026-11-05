package com.mia_princz.mse.analytics;

import java.time.Instant;

import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.metrics.Counter;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keyed by simulation and instrument: runs {@link CandleLogic} over checkpointed keyed state and emits the complete
 * updated candle after each unique trade. A sequence gap is logged once as an error and counted, and that instrument
 * emits nothing more; the others carry on.
 */
public class CandleAggregator extends KeyedProcessFunction<String, TradeRecord, CandleRecord> {

	private static final long serialVersionUID = 1L;

	private static final Logger LOG = LoggerFactory.getLogger(CandleAggregator.class);

	private final CandleLogic logic;

	private transient FlinkState state;

	private transient Counter gaps;

	private transient Counter held;

	/** @param replayFrom null to start from each instrument's first trade */
	public CandleAggregator(Instant replayFrom) {
		this.logic = new CandleLogic(replayFrom);
	}

	public CandleAggregator() {
		this(null);
	}

	@Override
	public void open(OpenContext context) {
		state = new FlinkState(
				getRuntimeContext().getState(new ValueStateDescriptor<>("last-trade-sequence", Types.LONG)),
				getRuntimeContext().getState(new ValueStateDescriptor<>("gap-expected-sequence", Types.LONG)),
				getRuntimeContext().getMapState(
						new MapStateDescriptor<>("candles-by-minute", Types.LONG, Types.POJO(CandleRecord.class))),
				getRuntimeContext().getState(new ValueStateDescriptor<>("next-cleanup-micros", Types.LONG)));
		gaps = getRuntimeContext().getMetricGroup().counter("tradeSequenceGaps");
		held = getRuntimeContext().getMetricGroup().counter("tradesHeldAfterGap");
	}

	@Override
	public void processElement(TradeRecord trade, Context context, Collector<CandleRecord> out) throws Exception {
		CandleLogic.Outcome outcome = logic.process(trade, state);
		switch (outcome.kind()) {
			case EMITTED -> out.collect(outcome.candle());
			case GAP -> {
				gaps.inc();
				LOG.error("Trade sequence gap for {} ({} {}): expected {}, got {}. Candles for it stop until it is "
						+ "replayed into a new computation epoch.", trade.symbol, trade.simulationId, trade.instrumentId,
						outcome.expectedSequence(), trade.tradeSequence);
			}
			case HELD -> held.inc();
			case DUPLICATE, BEFORE_BOUNDARY -> {
			}
		}
	}

	private record FlinkState(ValueState<Long> last, ValueState<Long> gap, MapState<Long, CandleRecord> buckets,
			ValueState<Long> nextCleanup) implements CandleState {

		@Override
		public Long lastSequence() throws Exception {
			return last.value();
		}

		@Override
		public void lastSequence(long sequence) throws Exception {
			last.update(sequence);
		}

		@Override
		public Long gapExpected() throws Exception {
			return gap.value();
		}

		@Override
		public void gapExpected(long sequence) throws Exception {
			gap.update(sequence);
		}

		@Override
		public CandleRecord bucket(long bucketStartMillis) throws Exception {
			return buckets.get(bucketStartMillis);
		}

		@Override
		public void putBucket(long bucketStartMillis, CandleRecord candle) throws Exception {
			buckets.put(bucketStartMillis, candle);
		}

		@Override
		public Iterable<Long> bucketStarts() throws Exception {
			return buckets.keys();
		}

		@Override
		public void removeBucket(long bucketStartMillis) throws Exception {
			buckets.remove(bucketStartMillis);
		}

		@Override
		public Long nextCleanupMicros() throws Exception {
			return nextCleanup.value();
		}

		@Override
		public void nextCleanupMicros(long micros) throws Exception {
			nextCleanup.update(micros);
		}

	}

}
