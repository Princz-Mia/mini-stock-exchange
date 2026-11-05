-- Indexes for the candles API, each led by the instrument it asks about: a time range of one epoch's bars, how far
-- that epoch's bars have got, and whether the instrument is known at all (bars can arrive before its snapshot).
CREATE INDEX candle_1m_range ON candle_1m (instrument_id, computation_epoch, bucket_start);

DROP INDEX candle_1m_progress;
CREATE INDEX candle_1m_progress ON candle_1m (instrument_id, computation_epoch, source_max_trade_sequence DESC);
