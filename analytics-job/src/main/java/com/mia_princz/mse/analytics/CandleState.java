package com.mia_princz.mse.analytics;

/**
 * What one instrument's candle logic remembers between trades. In the job this is Flink keyed state, so it is part of
 * every checkpoint; in tests it is a plain map that can be copied to stand in for one.
 */
interface CandleState {

	/** The last trade sequence accepted, or null before the first. */
	Long lastSequence() throws Exception;

	void lastSequence(long sequence) throws Exception;

	/** The sequence that was expected when a gap was found, or null while the instrument is healthy. */
	Long gapExpected() throws Exception;

	void gapExpected(long sequence) throws Exception;

	CandleRecord bucket(long bucketStartMillis) throws Exception;

	void putBucket(long bucketStartMillis, CandleRecord candle) throws Exception;

	Iterable<Long> bucketStarts() throws Exception;

	void removeBucket(long bucketStartMillis) throws Exception;

	/** The execution time after which old buckets are next pruned, or null before the first trade. */
	Long nextCleanupMicros() throws Exception;

	void nextCleanupMicros(long micros) throws Exception;

}
