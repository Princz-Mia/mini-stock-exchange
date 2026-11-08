package com.mia_princz.mse.execution.trading.domain;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * The matcher alone: one book's in-memory work per command, as the writer does it, with no database, Kafka or HTTP.
 * Each invocation replays the same recorded workload from a fresh book, so every run, before or after a change,
 * measures identical data. Run with the {@code jmh} profile; see the handbook for the command.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
@Fork(value = 2, jvmArgsAppend = { "-Xms1g", "-Xmx1g" })
public class MatcherBenchmark {

	static final int COMMANDS = 10_000;

	static final long SEED = 20261108L;

	/** Resting orders in the book before the stream starts: a quiet book and a busy one. */
	@Param({ "200", "5000" })
	int resting;

	private MatcherWorkload workload;

	private OrderBook book;

	@Setup(Level.Trial)
	public void record() {
		workload = MatcherWorkload.record(SEED, resting, COMMANDS);
	}

	@Setup(Level.Invocation)
	public void freshBook() {
		book = workload.book();
	}

	/** Nanoseconds per command: plan, delta, both sides' snapshot depth, apply or cancel. */
	@Benchmark
	@OperationsPerInvocation(COMMANDS)
	public long command() {
		return workload.replay(book);
	}

}
